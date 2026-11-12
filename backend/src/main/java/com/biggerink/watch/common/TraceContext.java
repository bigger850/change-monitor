package com.biggerink.watch.common;

/**
 * 全链路 traceId 上下文。TraceIdFilter 在请求入口写入，响应结束后清理。
 */
public final class TraceContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static String getOrCreate() {
        String id = HOLDER.get();
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            HOLDER.set(id);
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
