package com.biggerink.watch.service;

import com.biggerink.watch.config.WatchMetrics;
import com.biggerink.watch.entity.ChangeEvent;
import com.biggerink.watch.entity.NotificationRecord;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.entity.UserAccount;
import com.biggerink.watch.mapper.ChangeEventMapper;
import com.biggerink.watch.mapper.NotificationRecordMapper;
import com.biggerink.watch.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知分发：站内落库 + SSE 推送；邮件在 SMTP 未配置或发送失败时自动降级为仅记日志，
 * 绝不导致启动/扫描失败。含用户级每日频率上限与幂等去重。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRecordMapper recordMapper;
    private final ChangeEventMapper eventMapper;
    private final UserAccountMapper userMapper;
    private final MetaService meta;
    private final SseService sse;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final WatchMetrics metrics;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    @Value("${watch.notify.default-freq-limit:20}")
    private int defaultFreqLimit;

    public NotificationService(NotificationRecordMapper recordMapper,
                               ChangeEventMapper eventMapper,
                               UserAccountMapper userMapper,
                               MetaService meta,
                               SseService sse,
                               ObjectProvider<JavaMailSender> mailSenderProvider,
                               WatchMetrics metrics) {
        this.recordMapper = recordMapper;
        this.eventMapper = eventMapper;
        this.userMapper = userMapper;
        this.meta = meta;
        this.sse = sse;
        this.mailSenderProvider = mailSenderProvider;
        this.metrics = metrics;
    }

    public void notifyChange(ChangeEvent event, Subscription sub) {
        List<Map<String, Object>> channels = meta.listChannels(event.getUserId());
        if (channels.isEmpty()) {
            channels = List.of(
                    Map.of("channel", "SITE", "enabled", 1, "freq_limit_per_day", defaultFreqLimit),
                    Map.of("channel", "EMAIL", "enabled", 1, "freq_limit_per_day", defaultFreqLimit));
        }
        UserAccount user = userMapper.selectById(event.getUserId());
        String email = user == null ? null : user.getEmail();

        int sent = 0;
        int failed = 0;
        int skipped = 0;
        for (Map<String, Object> channel : channels) {
            Object rawChannel = channel.get("channel");
            if (rawChannel == null) {
                continue;
            }
            String name = String.valueOf(rawChannel);
            int enabled = intOf(channel.get("enabled"), 1);
            int freqLimit = intOf(channel.get("freq_limit_per_day"), defaultFreqLimit);
            String target = channel.get("target") == null ? "" : String.valueOf(channel.get("target"));

            NotificationRecord record = buildRecord(event, name, target);
            if (enabled != 1) {
                record.setStatus("SKIPPED");
                record.setErrorMsg("渠道已关闭");
                skipped++;
            } else if (meta.countSentToday(event.getUserId(), name) >= freqLimit) {
                record.setStatus("SKIPPED");
                record.setErrorMsg("超过每日频率上限 " + freqLimit);
                skipped++;
            } else if ("SITE".equals(name)) {
                record.setStatus("SENT");
                record.setSentAt(LocalDateTime.now(ZoneOffset.UTC));
                sent++;
            } else if ("EMAIL".equals(name)) {
                if (!smtpConfigured()) {
                    record.setStatus("SKIPPED");
                    record.setErrorMsg("SMTP 未配置，降级为仅记日志");
                    log.info("[MAIL-DOWNGRADE] eventNo={} to={} subject={} digest={}",
                            event.getEventNo(), email, record.getSubject(), record.getContentDigest());
                    skipped++;
                } else {
                    boolean ok = sendMail(email, sub, record);
                    if (ok) {
                        record.setStatus("SENT");
                        record.setSentAt(LocalDateTime.now(ZoneOffset.UTC));
                        sent++;
                    } else {
                        record.setStatus("FAILED");
                        record.setErrorMsg("邮件发送失败，已降级为日志");
                        failed++;
                    }
                }
            } else {
                record.setStatus("SKIPPED");
                record.setErrorMsg("P0 未启用通道 " + name + "（企业微信机器人留到 P1）");
                skipped++;
            }

            if ("EMAIL".equals(name) && (target.isEmpty())) {
                record.setTarget(email);
            }
            persistRecord(record);
            if ("SENT".equals(record.getStatus())) {
                meta.incrNotifyCount(event.getUserId());
            }
        }

        String notifyStatus;
        if (failed > 0 && sent == 0) {
            notifyStatus = "FAILED";
        } else if (failed > 0 || skipped > 0) {
            notifyStatus = "PARTIAL";
        } else {
            notifyStatus = "SENT";
        }
        event.setNotifyStatus(notifyStatus);
        eventMapper.updateById(event);

        // SSE 推送给该用户（大屏与时间线共用）
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventNo", event.getEventNo());
        payload.put("subscriptionId", String.valueOf(event.getSubscriptionId()));
        payload.put("changeType", event.getChangeType());
        payload.put("diffSummary", event.getDiffSummary());
        payload.put("notifyStatus", notifyStatus);
        payload.put("createdAt", String.valueOf(event.getCreatedAt()));
        sse.push(event.getUserId(), event.getEventNo(), payload);
        metrics.incNotify();
    }

    private NotificationRecord buildRecord(ChangeEvent event, String channel, String target) {
        NotificationRecord record = new NotificationRecord();
        record.setEventId(event.getId());
        record.setUserId(event.getUserId());
        record.setChannel(channel);
        record.setTarget(target);
        record.setSubject("网页变更提醒 " + event.getEventNo());
        record.setContentDigest(truncate(event.getDiffSummary(), 400));
        record.setStatus("PENDING");
        record.setRetryCount(0);
        // uniq(idempotency_key) 防重复通知
        record.setIdempotencyKey(event.getEventNo() + ":" + channel);
        return record;
    }

    private void persistRecord(NotificationRecord record) {
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("通知记录幂等去重命中 eventNo-part={}", record.getIdempotencyKey());
        } catch (Exception e) {
            log.warn("通知记录写入失败 key={} err={}", record.getIdempotencyKey(), e.getMessage());
        }
    }

    private boolean smtpConfigured() {
        return mailHost != null && !mailHost.isBlank()
                && mailSenderProvider.getIfAvailable() != null;
    }

    private boolean sendMail(String to, Subscription sub, NotificationRecord record) {
        if (to == null || to.isBlank()) {
            log.info("[MAIL-DOWNGRADE] 用户无邮箱，跳过 eventNo={}", record.getSubject());
            return false;
        }
        try {
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            if (sender == null) {
                return false;
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(mailFrom == null || mailFrom.isBlank() ? "noreply@bigger.ink" : mailFrom);
            msg.setTo(to);
            msg.setSubject(record.getSubject());
            msg.setText("订阅「" + (sub == null ? "" : sub.getName())
                    + "」检测到变更：\n" + record.getContentDigest()
                    + "\n\n本邮件仅含差异摘要，不含网页原文。");
            sender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("[MAIL-DOWNGRADE] 邮件发送失败，仅记日志 to={} err={}", to, e.getMessage());
            return false;
        }
    }

    private static int intOf(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
