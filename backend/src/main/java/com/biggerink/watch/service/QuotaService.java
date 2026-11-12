package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.entity.UserAccount;
import com.biggerink.watch.mapper.SubscriptionMapper;
import com.biggerink.watch.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 配额：Redis 计数为主 + quota_usage 日落库对账；订阅数用 DB 计数。
 */
@Service
public class QuotaService {

    private final UserAccountMapper userMapper;
    private final SubscriptionMapper subscriptionMapper;
    private final RedisOps redis;
    private final MetaService meta;

    public QuotaService(UserAccountMapper userMapper,
                        SubscriptionMapper subscriptionMapper,
                        RedisOps redis,
                        MetaService meta) {
        this.userMapper = userMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.redis = redis;
        this.meta = meta;
    }

    public void checkSubscriptionQuota(Long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Long used = subscriptionMapper.selectCount(new LambdaQueryWrapper<Subscription>()
                .eq(Subscription::getUserId, userId)
                .ne(Subscription::getStatus, "TERMINATED"));
        long count = used == null ? 0L : used;
        if (count >= user.getQuotaSubscription()) {
            throw new BizException(ErrorCode.QUOTA_EXHAUSTED, "订阅数配额用尽");
        }
    }

    public void checkScanQuota(Long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String key = "quota:scan:" + userId + ":" + LocalDate.now(ZoneOffset.UTC);
        if (redis.getLong(key) >= user.getQuotaScanDaily()) {
            throw new BizException(ErrorCode.QUOTA_EXHAUSTED, "今日扫描配额用尽");
        }
        redis.incr(key, Duration.ofDays(1));
        meta.incrScanCount(userId);
    }
}
