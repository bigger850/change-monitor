package com.biggerink.watch.controller;

import com.biggerink.watch.common.R;
import com.biggerink.watch.service.RedisOps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量存活探测（Nginx / LB）：不依赖 MySQL，DB 不可用时仍返回 UP（readiness 交给 actuator）。
 */
@RestController
public class HealthController {

    private final RedisOps redis;

    public HealthController(RedisOps redis) {
        this.redis = redis;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "change-monitor");
        data.put("redisUp", redis.ping());
        data.put("time", LocalDateTime.now(ZoneOffset.UTC).toString());
        return R.ok(data);
    }
}
