package com.biggerink.watch.common;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 请求/响应 DTO 集中定义（避免大量零碎文件）。
 * 使用处形如 ApiDtos.RegisterReq。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ---------------- 认证 ----------------
    @Data
    public static class RegisterReq {
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64)
        private String username;
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度 6-64")
        private String password;
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式非法")
        private String email;
        private String phone;
    }

    @Data
    public static class LoginReq {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class RefreshReq {
        @NotBlank(message = "refreshToken 不能为空")
        private String refreshToken;
    }

    @Data
    public static class TokenVO {
        private String accessToken;
        private String refreshToken;
        private String tokenType = "Bearer";
        private long expiresIn;
    }

    @Data
    public static class UserVO {
        private String id;
        private String username;
        private String email;
        private String phone;
        private String role;
        private String status;
        private Integer quotaSubscription;
        private Integer quotaScanDaily;
        private LocalDateTime lastLoginAt;
    }

    // ---------------- 订阅 ----------------
    @Data
    public static class SubscriptionCreateReq {
        @NotBlank(message = "名称不能为空")
        private String name;
        @NotBlank(message = "URL 不能为空")
        @Size(max = 1024)
        private String url;
        @NotBlank(message = "selectorType 不能为空")
        private String selectorType;   // CSS / KEYWORD / REGEX
        private String selectorExpr;
        private String keyword;
        @NotNull
        @Min(value = 300, message = "检查间隔不得小于 300 秒")
        private Integer checkIntervalSec;
        private List<String> notifyChannels;
    }

    @Data
    public static class SubscriptionUpdateReq {
        private String name;
        private String selectorType;
        private String selectorExpr;
        private String keyword;
        @Min(value = 300, message = "检查间隔不得小于 300 秒")
        private Integer checkIntervalSec;
        private List<String> notifyChannels;
    }

    @Data
    public static class StatusReq {
        @NotBlank(message = "status 不能为空")
        private String status;   // ACTIVE / PAUSED
    }

    @Data
    public static class SubscriptionVO {
        private String id;
        private String userId;
        /** 管理端列表填充：订阅归属用户的邮箱（无邮箱时退化为用户名） */
        private String ownerEmail;
        private String name;
        private String url;
        private String domain;
        private String selectorType;
        private String selectorExpr;
        private String keyword;
        private Integer checkIntervalSec;
        private String status;
        private Integer failCount;
        private Integer consecutiveDiffCount;
        private LocalDateTime lastScanAt;
        private LocalDateTime lastChangeAt;
        private LocalDateTime nextScanAt;
        private LocalDateTime createdAt;
        private Map<String, Object> lastScan;
    }

    @Data
    public static class ScanTriggerVO {
        private String taskId;
        private String batchNo;
        private String message;
    }

    // ---------------- 变更事件 ----------------
    @Data
    public static class ChangeEventVO {
        private String id;
        private String eventNo;
        private String subscriptionId;
        private String changeType;
        private String diffSummary;
        private Boolean confirmed;
        private Boolean readFlag;
        private String notifyStatus;
        private LocalDateTime createdAt;
    }

    // ---------------- 通知渠道 ----------------
    @Data
    public static class ChannelConfigVO {
        private String channel;
        private String target;
        private Boolean enabled;
        private Integer freqLimitPerDay;
        private LocalDateTime lastSentAt;
    }

    @Data
    public static class ChannelConfigReq {
        @NotBlank(message = "channel 不能为空")
        private String channel;
        private String target;
        private Boolean enabled;
        private Integer freqLimitPerDay;
    }

    // ---------------- 管理端 ----------------
    @Data
    public static class RejectReq {
        private String reason;
    }

    @Data
    public static class BlacklistReq {
        @NotBlank(message = "domainPattern 不能为空")
        private String domainPattern;
        private String reason;
    }

    @Data
    public static class BlacklistVO {
        private String id;
        private String domainPattern;
        private String reason;
        private String status;
        private String createdBy;
        private LocalDateTime createdAt;
    }

    @Data
    public static class QuotaReq {
        @NotNull
        @Min(0)
        private Integer quotaSubscription;
        @NotNull
        @Min(0)
        private Integer quotaScanDaily;
    }

    @Data
    public static class ConfigReq {
        @NotBlank(message = "configKey 不能为空")
        private String configKey;
        @NotBlank(message = "configValue 不能为空")
        private String configValue;
        private String remark;
    }

    @Data
    public static class ScanTaskVO {
        private String id;
        private String subscriptionId;
        private String userId;
        private String triggerType;
        private String batchNo;
        private String status;
        private Integer httpStatus;
        private Integer costMs;
        private String errorCode;
        private String errorMsg;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private LocalDateTime createdAt;
    }

    // ---------------- 大盘 ----------------
    @Data
    public static class DashboardOverviewVO {
        private long subscriptionTotal;
        private long scanToday;
        private long changeToday;
        private double successRate;
        private long p95CostMs;
        private long queueBacklog;
    }

    @Data
    public static class TrendPointVO {
        private String statDate;
        private long scanCount;
        private long changeCount;
        private long successCount;
    }
}
