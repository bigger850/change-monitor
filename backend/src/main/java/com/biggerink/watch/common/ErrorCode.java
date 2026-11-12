package com.biggerink.watch.common;

import lombok.Getter;

/**
 * 统一错误码，与 02-接口契约.md 第 2 节一致。
 */
@Getter
public enum ErrorCode {

    OK(0, 200, "ok"),
    PARAM_INVALID(40001, 400, "参数校验失败"),
    UNAUTHORIZED(40101, 401, "未登录"),
    TOKEN_INVALID(40102, 401, "token 过期或无效"),
    FORBIDDEN(40301, 403, "无权限（角色或资源归属不符）"),
    NOT_FOUND(40401, 404, "资源不存在"),
    IDEMPOTENT_CONFLICT(40901, 409, "幂等键冲突 / 重复提交"),
    QUOTA_EXHAUSTED(40902, 409, "配额用尽"),
    STATUS_NOT_ALLOWED(42201, 422, "订阅状态不允许该操作"),
    RATE_LIMITED(42901, 429, "请求过于频繁"),
    INTERNAL_ERROR(50001, 500, "服务内部错误"),
    DEPENDENCY_UNAVAILABLE(50301, 503, "依赖不可用");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
