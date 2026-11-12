package com.biggerink.watch.service;

import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 幂等：Idempotency-Key -> Redis SETNX TTL 24h；作用域 userId + 接口路径 + key。
 * 手动扫描额外做同一订阅 5 分钟窗口去重。
 */
@Service
public class IdempotencyService {

    private final RedisOps redis;

    @Value("${watch.idempotency.ttl-hours:24}")
    private long ttlHours;

    @Value("${watch.idempotency.manual-scan-gap-sec:300}")
    private long manualScanGapSec;

    public IdempotencyService(RedisOps redis) {
        this.redis = redis;
    }

    public void require(String rawKey, String scope) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 Idempotency-Key 请求头");
        }
        String key = "idem:" + scope + ":" + rawKey;
        if (!redis.setIfAbsent(key, "1", Duration.ofHours(ttlHours))) {
            throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, "幂等键冲突 / 重复提交");
        }
    }

    public void manualScanGuard(Long subscriptionId) {
        if (!redis.setIfAbsent("manual:scan:" + subscriptionId, "1",
                Duration.ofSeconds(manualScanGapSec))) {
            throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT,
                    "同一订阅 5 分钟内重复触发扫描");
        }
    }

    public static String scope(Long userId, String path) {
        return userId + ":" + path;
    }
}
