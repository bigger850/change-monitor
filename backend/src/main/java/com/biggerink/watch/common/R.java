package com.biggerink.watch.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体 {code, message, data, traceId}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data, TraceContext.get());
    }

    public static R<Void> ok() {
        return new R<>(0, "ok", null, TraceContext.get());
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return new R<>(ec.getCode(), ec.getMessage(), null, TraceContext.get());
    }

    public static <T> R<T> fail(ErrorCode ec, String message) {
        return new R<>(ec.getCode(), message, null, TraceContext.get());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, TraceContext.get());
    }
}
