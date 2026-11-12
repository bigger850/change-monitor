package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.common.R;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.IdempotencyService;
import com.biggerink.watch.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final IdempotencyService idempotency;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  IdempotencyService idempotency) {
        this.subscriptionService = subscriptionService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public R<PageResult<ApiDtos.SubscriptionVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        size = Math.min(Math.max(size, 1), 100);
        return R.ok(subscriptionService.list(AuthContext.userId(), page, size, status));
    }

    @PostMapping
    public R<ApiDtos.SubscriptionVO> create(
            @Valid @RequestBody ApiDtos.SubscriptionCreateReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/subscriptions"));
        return R.ok(subscriptionService.create(userId, req));
    }

    @GetMapping("/{id:\\d+}")
    public R<ApiDtos.SubscriptionVO> detail(@PathVariable Long id) {
        return R.ok(subscriptionService.detail(AuthContext.userId(), id));
    }

    @PatchMapping("/{id:\\d+}")
    public R<ApiDtos.SubscriptionVO> update(
            @PathVariable Long id,
            @Valid @RequestBody ApiDtos.SubscriptionUpdateReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/subscriptions/" + id));
        return R.ok(subscriptionService.update(userId, id, req));
    }

    @PatchMapping("/{id:\\d+}/status")
    public R<ApiDtos.SubscriptionVO> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ApiDtos.StatusReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/subscriptions/" + id + "/status"));
        return R.ok(subscriptionService.changeStatus(userId, id, req.getStatus()));
    }

    @DeleteMapping("/{id:\\d+}")
    public R<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/subscriptions/" + id));
        subscriptionService.delete(userId, id);
        return R.ok();
    }

    @PostMapping("/{id:\\d+}/scan")
    public R<ApiDtos.ScanTriggerVO> scan(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/subscriptions/" + id + "/scan"));
        return R.ok(subscriptionService.triggerScan(userId, id, idemKey));
    }
}
