package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.entity.ChangeEvent;
import com.biggerink.watch.mapper.ChangeEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 站内 SSE 推送：按 userId 维护连接，变更事件生成后实时推送；定时心跳保活。
 * 断线重连按 Last-Event-ID 补推（只读库、不写库、失败不影响订阅建立）。
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int REPLAY_MAX = 100;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();
    private final ChangeEventMapper eventMapper;

    public SseService(ChangeEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public SseEmitter subscribe(Long userId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        try {
            emitter.send(SseEmitter.event().name("connected")
                    .data(Map.of("ok", true, "userId", String.valueOf(userId)),
                            MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            cleanup.run();
        }
        // emitter 刚创建时先发 connected 再补推
        replay(userId, lastEventId, emitter);
        return emitter;
    }

    /** 断线补推：该用户 event_no > lastEventId 的已确认事件，按 event_no 升序，最多 100 条。 */
    private void replay(Long userId, String lastEventId, SseEmitter emitter) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return;
        }
        try {
            List<ChangeEvent> events = eventMapper.selectList(
                    new LambdaQueryWrapper<ChangeEvent>()
                            .eq(ChangeEvent::getUserId, userId)
                            .eq(ChangeEvent::getConfirmed, 1)
                            .gt(ChangeEvent::getEventNo, lastEventId)
                            .orderByAsc(ChangeEvent::getEventNo)
                            .last("LIMIT " + REPLAY_MAX));
            for (ChangeEvent e : events) {
                emitter.send(SseEmitter.event().id(e.getEventNo()).name("change")
                        .data(ChangeEventService.toVO(e), MediaType.APPLICATION_JSON));
            }
        } catch (Exception ex) {
            // 补推失败不影响订阅建立
            log.warn("SSE 断线补推失败 userId={} lastEventId={} err={}",
                    userId, lastEventId, ex.getMessage());
        }
    }

    /** 实时推送：带 SSE id(eventNo)，浏览器据此更新 Last-Event-ID。 */
    public void push(Long userId, String eventNo, Object payload) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().id(eventNo).name("change")
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("SSE 推送失败，移除连接 userId={} err={}", userId, e.getMessage());
                remove(userId, emitter);
            }
        }
    }

    public void pushAll(String eventNo, Object payload) {
        for (Long userId : emitters.keySet()) {
            push(userId, eventNo, payload);
        }
    }

    public int connectionCount() {
        int total = 0;
        for (List<SseEmitter> list : emitters.values()) {
            total += list.size();
        }
        return total;
    }

    @Scheduled(fixedDelayString = "${watch.sse.heartbeat-ms:15000}")
    public void heartbeat() {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(userId, list);
            }
        }
    }
}
