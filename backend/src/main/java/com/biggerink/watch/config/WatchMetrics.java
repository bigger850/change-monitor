package com.biggerink.watch.config;

import com.biggerink.watch.service.SseService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Prometheus 业务指标：集中注册 Counter / Gauge，供各业务组件埋点。
 * Gauge 直接绑定 SseService.connectionCount()，无需额外维护计数器。
 */
@Component
public class WatchMetrics {

    private final Counter scanTotal;
    private final Counter changeTotal;
    private final Counter notifyTotal;
    private final Counter ratelimitRejected;

    public WatchMetrics(MeterRegistry registry, SseService sse) {
        this.scanTotal = Counter.builder("watch.scan.total")
                .description("扫描任务执行总数")
                .register(registry);
        this.changeTotal = Counter.builder("watch.change.total")
                .description("变更事件生成总数")
                .register(registry);
        this.notifyTotal = Counter.builder("watch.notify.total")
                .description("通知分发总数")
                .register(registry);
        this.ratelimitRejected = Counter.builder("watch.ratelimit.rejected")
                .tag("result", "rejected")
                .description("限流拒绝请求总数")
                .register(registry);
        Gauge.builder("watch.sse.connections", sse, s -> s.connectionCount())
                .description("当前 SSE 连接数")
                .register(registry);
    }

    public void incScan() {
        scanTotal.increment();
    }

    public void incChange() {
        changeTotal.increment();
    }

    public void incNotify() {
        notifyTotal.increment();
    }

    public void incRateLimitRejected() {
        ratelimitRejected.increment();
    }
}
