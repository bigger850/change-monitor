package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.R;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.IdempotencyService;
import com.biggerink.watch.service.NotifyChannelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notification-channels")
public class NotifyChannelController {

    private final NotifyChannelService channelService;
    private final IdempotencyService idempotency;

    public NotifyChannelController(NotifyChannelService channelService,
                                   IdempotencyService idempotency) {
        this.channelService = channelService;
        this.idempotency = idempotency;
    }

    @GetMapping
    public R<List<ApiDtos.ChannelConfigVO>> list() {
        return R.ok(channelService.list(AuthContext.userId()));
    }

    @PutMapping
    public R<Void> update(
            @Valid @RequestBody ApiDtos.ChannelConfigReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = AuthContext.userId();
        idempotency.require(idemKey, IdempotencyService.scope(userId, "/notification-channels"));
        channelService.update(userId, req);
        return R.ok();
    }
}
