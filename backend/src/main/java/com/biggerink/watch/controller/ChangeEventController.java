package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.common.R;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.ChangeEventService;
import com.biggerink.watch.service.IdempotencyService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/change-events")
public class ChangeEventController {

    private final ChangeEventService changeEventService;
    private final IdempotencyService idempotency;

    public ChangeEventController(ChangeEventService changeEventService,
                                 IdempotencyService idempotency) {
        this.changeEventService = changeEventService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public R<PageResult<ApiDtos.ChangeEventVO>> timeline(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long subscriptionId,
            @RequestParam(required = false) String keyword) {
        size = Math.min(Math.max(size, 1), 100);
        return R.ok(changeEventService.timeline(AuthContext.userId(), page, size, subscriptionId, keyword));
    }

    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long subscriptionId,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws IOException {
        changeEventService.exportCsv(AuthContext.userId(), subscriptionId, keyword, response);
    }

    @GetMapping("/{id:\\d+}")
    public R<ApiDtos.ChangeEventVO> detail(@PathVariable Long id) {
        return R.ok(changeEventService.detail(AuthContext.userId(), id));
    }

    @PostMapping("/{id:\\d+}/read")
    public R<Void> markRead(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/change-events/" + id + "/read"));
        changeEventService.markRead(userId, id);
        return R.ok();
    }
}
