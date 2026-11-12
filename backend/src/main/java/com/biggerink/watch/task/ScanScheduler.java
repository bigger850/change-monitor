package com.biggerink.watch.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.common.TraceContext;
import com.biggerink.watch.entity.ScanTask;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.mapper.ScanTaskMapper;
import com.biggerink.watch.mapper.SubscriptionMapper;
import com.biggerink.watch.service.RedisOps;
import com.biggerink.watch.service.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 定时调度：按 subscription.next_scan_at 捞取到期 ACTIVE 订阅，
 * Redis 分布式锁防重复，同域名限速（并发 1 / 间隔 >=30s），写 scan_task 并串行执行。
 * P0 单机单线程调度；集群分片（XXL-Job）、Kafka 解耦留到 P1。
 */
@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final SubscriptionMapper subscriptionMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final ScanService scanService;
    private final RedisOps redis;

    @Value("${watch.scan.scheduler-batch-size:50}")
    private int batchSize;

    @Value("${watch.scan.scheduler-lock-ttl-sec:60}")
    private long schedulerLockTtlSec;

    @Value("${watch.scan.domain-min-gap-sec:30}")
    private long domainGapSec;

    public ScanScheduler(SubscriptionMapper subscriptionMapper,
                         ScanTaskMapper scanTaskMapper,
                         ScanService scanService,
                         RedisOps redis) {
        this.subscriptionMapper = subscriptionMapper;
        this.scanTaskMapper = scanTaskMapper;
        this.scanService = scanService;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${watch.scan.scheduler-fixed-delay-ms:30000}",
            initialDelayString = "${watch.scan.scheduler-initial-delay-ms:10000}")
    public void scanDueSubscriptions() {
        // 定时任务不在 HTTP 请求上下文，手动建立 traceId
        TraceContext.set(UUID.randomUUID().toString().replace("-", ""));
        boolean lockAcquired = false;
        try {
            // 调度器级分布式锁：多实例时仅一个执行
            lockAcquired = redis.tryLock("lock:scheduler", Duration.ofSeconds(schedulerLockTtlSec));
            if (!lockAcquired) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String batchNo = "S" + System.currentTimeMillis();
            List<Subscription> due = subscriptionMapper.selectList(
                    new LambdaQueryWrapper<Subscription>()
                            .eq(Subscription::getStatus, "ACTIVE")
                            .le(Subscription::getNextScanAt, now)
                            .orderByAsc(Subscription::getNextScanAt)
                            .last("LIMIT " + batchSize));
            int executed = 0;
            for (Subscription sub : due) {
                String lockKey = "lock:scan:" + sub.getId();
                if (!redis.tryLock(lockKey, Duration.ofSeconds(schedulerLockTtlSec))) {
                    continue;   // 同一订阅正在被其它实例扫描
                }
                try {
                    // 同域名限速：SETNX 成功才放行，TTL=gap 同时保证并发 1 与间隔 >= gap
                    if (!redis.acquireDomainSlot(sub.getDomain(), domainGapSec)) {
                        continue;
                    }
                    ScanTask task = new ScanTask();
                    task.setSubscriptionId(sub.getId());
                    task.setUserId(sub.getUserId());
                    task.setTriggerType("SCHEDULED");
                    task.setBatchNo(batchNo);
                    task.setStatus("PENDING");
                    task.setIdempotencyKey("SCH-" + sub.getId() + "-" + batchNo);
                    scanTaskMapper.insert(task);
                    scanService.execute(task.getId());
                    executed++;
                } catch (Exception e) {
                    log.warn("调度执行异常 subId={} err={}", sub.getId(), e.getMessage());
                } finally {
                    redis.unlock(lockKey);
                }
            }
            if (executed > 0) {
                log.info("调度批次 {} 完成，执行 {} 条扫描", batchNo, executed);
            }
        } catch (Exception e) {
            log.warn("调度批次异常: {}", e.getMessage());
        } finally {
            if (lockAcquired) {
                redis.unlock("lock:scheduler");
            }
            TraceContext.clear();
        }
    }
}
