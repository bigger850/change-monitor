package com.biggerink.watch.config;

import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.security.LoginUser;
import com.biggerink.watch.service.RedisOps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 接口级令牌桶限流：key = userId + HTTP 方法 + 路径分组，Redis + Lua 原子补充/扣减。
 * 超限时抛 BizException(RATE_LIMITED)，由 GlobalExceptionHandler 返回统一 R(HTTP 429)。
 * Redis 不可用时降级放行，绝不因限流组件阻断业务。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String[] WHITELIST = {
            "/health", "/actuator/**",
            "/auth/login", "/auth/register", "/auth/refresh",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /** 真令牌桶：hash 存 {tokens, ts}，按时间差补充后扣减，返回 1 放行 / 0 拒绝。 */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>(
            "local capacity = tonumber(ARGV[1]); "
                    + "local refill = tonumber(ARGV[2]); "
                    + "local now = tonumber(ARGV[3]); "
                    + "local requested = tonumber(ARGV[4]); "
                    + "local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts'); "
                    + "local tokens = tonumber(b[1]); "
                    + "local ts = tonumber(b[2]); "
                    + "if tokens == nil then tokens = capacity; ts = now end; "
                    + "local delta = math.max(0, now - ts) / 1000.0; "
                    + "tokens = math.min(capacity, tokens + delta * refill); "
                    + "local allowed = 0; "
                    + "if tokens >= requested then tokens = tokens - requested; allowed = 1 end; "
                    + "redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now); "
                    + "redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill * 2) + 1); "
                    + "return allowed",
            Long.class);

    private final RedisOps redisOps;
    private final WatchMetrics metrics;

    @Value("${watch.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${watch.rate-limit.capacity:60}")
    private long capacity;

    @Value("${watch.rate-limit.refill-per-sec:20}")
    private long refillPerSec;

    public RateLimitInterceptor(RedisOps redisOps, WatchMetrics metrics) {
        this.redisOps = redisOps;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!enabled || capacity <= 0 || refillPerSec <= 0
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String pattern : WHITELIST) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }

        LoginUser user = AuthContext.current();
        String subject = user == null || user.getUserId() == null
                ? "anon" : String.valueOf(user.getUserId());
        String key = "watch:ratelimit:" + subject + ":" + request.getMethod()
                + ":" + pathGroup(uri);
        try {
            Long allowed = redisOps.redisTemplate().execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(capacity), String.valueOf(refillPerSec),
                    String.valueOf(System.currentTimeMillis()), "1");
            if (allowed != null && allowed == 0L) {
                metrics.incRateLimitRejected();
                throw new BizException(ErrorCode.RATE_LIMITED);
            }
        } catch (BizException e) {
            throw e;                       // 交给 GlobalExceptionHandler 统一响应
        } catch (Exception e) {
            log.warn("限流 Redis 不可用，降级放行 key={} err={}", key, e.getMessage());
        }
        return true;
    }

    /** 路径分组：把纯数字段（资源 id）归一为 {id}，避免每个 id 独立计数。 */
    private static String pathGroup(String uri) {
        StringBuilder sb = new StringBuilder();
        for (String part : uri.split("/")) {
            sb.append('/');
            sb.append(part.matches("\\d+") ? "{id}" : part);
        }
        return sb.toString();
    }
}
