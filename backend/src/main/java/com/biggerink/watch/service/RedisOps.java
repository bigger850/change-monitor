package com.biggerink.watch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 能力封装：分布式锁、幂等 SETNX、配额计数、域名限速、JWT 注销黑名单。
 * 依赖不可用时的降级策略：
 *  - 锁/幂等/计数：fail-open（放行，靠 DB 唯一索引兜底），保证可用性
 *  - 域名限速：fail-closed（不放行），守住合规红线
 */
@Service
public class RedisOps {

    private static final Logger log = LoggerFactory.getLogger(RedisOps.class);

    private final StringRedisTemplate redis;

    public RedisOps(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 暴露底层客户端，供限流 Lua 等扩展复用同一连接配置，避免重复建连。 */
    public StringRedisTemplate redisTemplate() {
        return redis;
    }

    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
        } catch (Exception e) {
            log.warn("Redis 不可用，SETNX 降级放行 key={} err={}", key, e.getMessage());
            return true;
        }
    }

    public boolean tryLock(String key, Duration ttl) {
        return setIfAbsent(key, UUID.randomUUID().toString(), ttl);
    }

    public void unlock(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis 解锁失败 key={} err={}", key, e.getMessage());
        }
    }

    public long incr(String key, Duration ttl) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, ttl.getSeconds(), TimeUnit.SECONDS);
            }
            return value == null ? 0L : value;
        } catch (Exception e) {
            log.warn("Redis 计数降级 key={} err={}", key, e.getMessage());
            return 0L;
        }
    }

    public long getLong(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis set 失败 key={} err={}", key, e.getMessage());
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 同域名并发 1 + 间隔 >= gapSec：SETNX 成功才放行，TTL 到期前同一域名不可再扫。
     */
    public boolean acquireDomainSlot(String domain, long gapSec) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    "limit:domain:" + domain,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofSeconds(gapSec)));
        } catch (Exception e) {
            log.warn("Redis 不可用，域名限速 fail-closed，跳过 domain={}", domain);
            return false;
        }
    }

    public void revoke(String tokenHash, long ttlMs) {
        set("jwt:revoked:" + tokenHash, "1", Duration.ofMillis(ttlMs));
    }

    public boolean isRevoked(String tokenHash) {
        return exists("jwt:revoked:" + tokenHash);
    }

    public boolean ping() {
        try {
            redis.hasKey("__watch_ping__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
