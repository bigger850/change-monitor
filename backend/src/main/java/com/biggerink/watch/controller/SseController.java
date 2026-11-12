package com.biggerink.watch.controller;

import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.SseService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 实时推送（P0）。EventSource 无法带 header，可用 ?token=<access_token> 鉴权。
 * 断线重连的 Last-Event-ID 由前端携带，后端补推按 event_no 处理，不重复写库。
 */
@RestController
public class SseController {

    private final SseService sse;

    public SseController(SseService sse) {
        this.sse = sse;
    }

    @GetMapping(value = "/stream/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return sse.subscribe(AuthContext.userId(), lastEventId);
    }
}
