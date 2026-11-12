package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.common.R;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.AdminService;
import com.biggerink.watch.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：SecurityConfig 已对 /admin/** 做 hasRole("ADMIN") 二次校验，
 * AdminService 内再以 AuthContext.requireAdmin() 兜底。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final IdempotencyService idempotency;

    public AdminController(AdminService adminService, IdempotencyService idempotency) {
        this.adminService = adminService;
        this.idempotency = idempotency;
    }

    @GetMapping("/subscriptions")
    public R<PageResult<ApiDtos.SubscriptionVO>> subscriptions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return R.ok(adminService.listSubscriptions(page, clamp(size), status));
    }

    @PostMapping("/subscriptions/{id:\\d+}/approve")
    public R<Void> approve(@PathVariable Long id,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/subscriptions/" + id + "/approve");
        adminService.approve(id);
        return R.ok();
    }

    @PostMapping("/subscriptions/{id:\\d+}/reject")
    public R<Void> reject(@PathVariable Long id,
                          @RequestBody(required = false) ApiDtos.RejectReq req,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/subscriptions/" + id + "/reject");
        adminService.reject(id, req == null ? null : req.getReason());
        return R.ok();
    }

    @GetMapping("/blacklist")
    public R<List<ApiDtos.BlacklistVO>> blacklist() {
        return R.ok(adminService.blacklist());
    }

    @PostMapping("/blacklist")
    public R<Void> addBlacklist(
            @Valid @RequestBody ApiDtos.BlacklistReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/blacklist");
        adminService.addBlacklist(req);
        return R.ok();
    }

    @DeleteMapping("/blacklist/{id:\\d+}")
    public R<Void> deleteBlacklist(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/blacklist/" + id);
        adminService.deleteBlacklist(id);
        return R.ok();
    }

    @GetMapping("/scan-tasks")
    public R<PageResult<ApiDtos.ScanTaskVO>> scanTasks(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorCode) {
        return R.ok(adminService.scanTasks(page, clamp(size), status, errorCode));
    }

    @PostMapping("/scan-tasks/{id:\\d+}/retry")
    public R<Void> retryScanTask(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/scan-tasks/" + id + "/retry");
        adminService.retryScanTask(id);
        return R.ok();
    }

    @GetMapping("/users")
    public R<PageResult<ApiDtos.UserVO>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(adminService.users(page, clamp(size)));
    }

    @PutMapping("/users/{id:\\d+}/quota")
    public R<Void> updateQuota(
            @PathVariable Long id,
            @Valid @RequestBody ApiDtos.QuotaReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/users/" + id + "/quota");
        adminService.updateQuota(id, req);
        return R.ok();
    }

    @GetMapping("/config")
    public R<Map<String, String>> config() {
        return R.ok(adminService.config());
    }

    @PutMapping("/config")
    public R<Void> updateConfig(
            @Valid @RequestBody ApiDtos.ConfigReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        require(idemKey, "/admin/config");
        adminService.updateConfig(req);
        return R.ok();
    }

    @GetMapping("/notification-records")
    public R<PageResult<Map<String, Object>>> notificationRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return R.ok(adminService.notificationRecords(page, clamp(size), status));
    }

    @GetMapping("/audit-logs")
    public R<PageResult<Map<String, Object>>> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Long userId) {
        return R.ok(adminService.auditLogs(page, clamp(size), traceId, userId));
    }

    private void require(String idemKey, String path) {
        idempotency.require(idemKey, IdempotencyService.scope(AuthContext.userId(), path));
    }

    private static long clamp(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
