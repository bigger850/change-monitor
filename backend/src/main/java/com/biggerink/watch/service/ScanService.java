package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.common.HashUtil;
import com.biggerink.watch.config.WatchMetrics;
import com.biggerink.watch.entity.ChangeEvent;
import com.biggerink.watch.entity.PageSnapshot;
import com.biggerink.watch.entity.ScanTask;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.mapper.ChangeEventMapper;
import com.biggerink.watch.mapper.PageSnapshotMapper;
import com.biggerink.watch.mapper.ScanTaskMapper;
import com.biggerink.watch.mapper.SubscriptionMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取与比对闭环：Jsoup（超时/重试/robots 基本校验）-> SHA-256 指纹 ->
 * 连续两次指纹不同才生成 change_event（二次确认）-> 通知。
 * 合规：抓取原文仅在内存中用于指纹计算，用完即弃；只把指纹/长度/差异摘要写入 DB。
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final DateTimeFormatter EVENT_NO_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ScanTaskMapper taskMapper;
    private final SubscriptionMapper subscriptionMapper;
    private final PageSnapshotMapper snapshotMapper;
    private final ChangeEventMapper eventMapper;
    private final NotificationService notificationService;
    private final MetaService meta;
    private final WatchMetrics metrics;

    @Value("${watch.scan.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${watch.scan.read-timeout-ms:8000}")
    private int readTimeoutMs;

    @Value("${watch.scan.max-retry:2}")
    private int maxRetry;

    @Value("${watch.scan.user-agent:BiggerInkWatchBot/0.1}")
    private String userAgent;

    @Value("${watch.scan.diff-confirm-threshold:2}")
    private int diffConfirmThreshold;

    @Value("${watch.scan.max-fail-count:5}")
    private int maxFailCount;

    @Value("${watch.scan.diff-summary-max-len:500}")
    private int diffSummaryMaxLen;

    public ScanService(ScanTaskMapper taskMapper,
                       SubscriptionMapper subscriptionMapper,
                       PageSnapshotMapper snapshotMapper,
                       ChangeEventMapper eventMapper,
                       NotificationService notificationService,
                       MetaService meta,
                       WatchMetrics metrics) {
        this.taskMapper = taskMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.snapshotMapper = snapshotMapper;
        this.eventMapper = eventMapper;
        this.notificationService = notificationService;
        this.meta = meta;
        this.metrics = metrics;
    }

    /** 执行一条 scan_task（手动 / 调度 / 重试共用），不向上抛异常，保证调度不被拖垮。 */
    public void execute(Long taskId) {
        ScanTask task = taskMapper.selectById(taskId);
        if (task == null || "SUCCESS".equals(task.getStatus())) {
            return;
        }
        metrics.incScan();
        Subscription sub = subscriptionMapper.selectById(task.getSubscriptionId());
        if (sub == null) {
            markFailure(task, null, 0L, "SUBSCRIPTION_NOT_FOUND", "订阅不存在", false);
            return;
        }
        long start = System.currentTimeMillis();
        task.setStatus("RUNNING");
        task.setStartAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(task);
        try {
            FetchResult result = fetch(sub);
            handleSuccess(task, sub, result, System.currentTimeMillis() - start);
        } catch (ScanFetchException se) {
            markFailure(task, sub, System.currentTimeMillis() - start,
                    se.code, se.getMessage(), "FETCH_TIMEOUT".equals(se.code));
        } catch (Exception e) {
            log.warn("扫描异常 taskId={} subId={} err={}", taskId, sub.getId(), e.getMessage());
            markFailure(task, sub, System.currentTimeMillis() - start,
                    "FETCH_ERROR", String.valueOf(e.getMessage()), false);
        }
    }

    private void handleSuccess(ScanTask task, Subscription sub, FetchResult result, long costMs) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String fingerprint = HashUtil.sha256Hex(result.text());
        String oldFingerprint = sub.getLastFingerprint();
        boolean keywordHit = sub.getKeyword() != null && !sub.getKeyword().isBlank()
                && result.text().contains(sub.getKeyword());
        int oldLength = latestSnapshotLength(sub.getId());
        boolean changed = oldFingerprint != null && !oldFingerprint.equals(fingerprint);

        PageSnapshot snapshot;
        int confirmThreshold = meta.getIntConfig("scan.diff_confirm_threshold", diffConfirmThreshold);
        if (oldFingerprint == null) {
            // 首次基线：写一条快照但不产生事件
            snapshot = saveSnapshotIfAbsent(sub, task, fingerprint, result.text().length(),
                    "首次基线快照", 0);
            sub.setConsecutiveDiffCount(0);
        } else if (changed) {
            String summary = buildDiffSummary(oldFingerprint, fingerprint,
                    oldLength, result.text().length(), keywordHit);
            snapshot = saveSnapshotIfAbsent(sub, task, fingerprint, result.text().length(),
                    summary, 1);
            int count = (sub.getConsecutiveDiffCount() == null ? 0 : sub.getConsecutiveDiffCount()) + 1;
            sub.setConsecutiveDiffCount(count);
            if (snapshot != null && count >= confirmThreshold) {
                createChangeEvent(sub, task, snapshot, summary, keywordHit ? "KEYWORD_HIT" : "CONTENT");
                sub.setConsecutiveDiffCount(0);
                sub.setLastChangeAt(now);
            }
        } else {
            snapshot = null;
            sub.setConsecutiveDiffCount(0);
        }

        sub.setLastFingerprint(fingerprint);
        sub.setLastScanAt(now);
        sub.setFailCount(0);
        sub.setNextScanAt(now.plusSeconds(sub.getCheckIntervalSec()));
        subscriptionMapper.updateById(sub);

        task.setHttpStatus(result.httpStatus());
        task.setStatus("SUCCESS");
        task.setCostMs((int) costMs);
        task.setEndAt(now);
        task.setErrorMsg(null);
        taskMapper.updateById(task);
    }

    private void markFailure(ScanTask task, Subscription sub, long costMs,
                             String errorCode, String errorMsg, boolean timeout) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        task.setStatus(timeout ? "TIMEOUT" : "FAILED");
        task.setErrorCode(errorCode);
        task.setErrorMsg(truncate(errorMsg, 500));
        task.setCostMs((int) costMs);
        task.setEndAt(now);
        taskMapper.updateById(task);

        if (sub == null) {
            return;
        }
        int maxFail = meta.getIntConfig("scan.max_fail_count", maxFailCount);
        int failCount = (sub.getFailCount() == null ? 0 : sub.getFailCount()) + 1;
        sub.setFailCount(failCount);
        sub.setLastScanAt(now);
        sub.setNextScanAt(now.plusSeconds(sub.getCheckIntervalSec()));
        boolean justAbnormal = false;
        if (failCount >= maxFail && !"ABNORMAL".equals(sub.getStatus())) {
            sub.setStatus("ABNORMAL");
            sub.setEnabled(0);
            justAbnormal = true;
        }
        subscriptionMapper.updateById(sub);
        if (justAbnormal) {
            try {
                // URL_UNAVAILABLE 事件：无真实快照，用 task.id 作为伪 snapshot_id（DB 无外键），
                // 仍受 uniq(subscription_id,snapshot_id) 保护，避免重复事件。
                createChangeEvent(sub, task, task.getId(),
                        "连续 " + failCount + " 次抓取失败(" + errorCode + ")，订阅已置 ABNORMAL",
                        "URL_UNAVAILABLE");
            } catch (Exception e) {
                log.warn("URL_UNAVAILABLE 事件生成失败 subId={} err={}", sub.getId(), e.getMessage());
            }
        }
    }

    private FetchResult fetch(Subscription sub) throws ScanFetchException {
        if (!robotsAllowed(sub.getUrl())) {
            throw new ScanFetchException("ROBOTS_DISALLOWED", "robots.txt 禁止抓取该路径");
        }
        int attempts = Math.max(1, meta.getIntConfig("scan.max_retry", maxRetry) + 1);
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                Connection.Response response = Jsoup.connect(sub.getUrl())
                        .userAgent(userAgent)
                        .timeout(readTimeoutMs)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .maxBodySize(1024 * 1024)
                        .execute();
                int status = response.statusCode();
                if (status >= 400) {
                    throw new ScanFetchException("HTTP_" + status, "源站返回 HTTP " + status);
                }
                Document doc = response.parse();
                String text = extract(doc, sub);
                return new FetchResult(status, text);
            } catch (ScanFetchException se) {
                throw se;
            } catch (SocketTimeoutException e) {
                last = e;
                log.debug("抓取超时重试 {}/{} url={}", i + 1, attempts, sub.getUrl());
            } catch (IOException e) {
                last = e;
                log.debug("抓取失败重试 {}/{} url={} err={}", i + 1, attempts, sub.getUrl(), e.getMessage());
            } catch (IllegalArgumentException e) {
                // CSS 选择器非法等
                throw new ScanFetchException("INVALID_SELECTOR", "选择器非法: " + e.getMessage());
            }
        }
        throw new ScanFetchException("FETCH_TIMEOUT",
                "抓取失败(重试 " + attempts + " 次): " + (last == null ? "unknown" : last.getMessage()));
    }

    /** 按用户自定义规则提取文本；原文只在本方法内参与指纹计算，绝不落库。 */
    private String extract(Document doc, Subscription sub) {
        String type = sub.getSelectorType() == null ? "CSS" : sub.getSelectorType();
        String expr = sub.getSelectorExpr();
        try {
            if ("CSS".equals(type) && expr != null && !expr.isBlank()) {
                Elements elements = doc.select(expr);
                return elements.text();
            }
            if ("REGEX".equals(type) && expr != null && !expr.isBlank()) {
                Matcher matcher = Pattern.compile(expr).matcher(doc.text());
                StringBuilder sb = new StringBuilder();
                while (matcher.find() && sb.length() < 100_000) {
                    sb.append(matcher.group()).append('\n');
                }
                return sb.toString();
            }
            if ("KEYWORD".equals(type)) {
                String keyword = sub.getKeyword();
                String body = doc.text();
                if (keyword != null && !keyword.isBlank()) {
                    int idx = body.indexOf(keyword);
                    if (idx < 0) {
                        return "";   // 关键词未命中 -> 稳定空文本，避免噪声
                    }
                    return body.substring(Math.max(0, idx - 200), Math.min(body.length(), idx + 200));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        }
        return doc.body() == null ? doc.text() : doc.body().text();
    }

    /**
     * robots.txt 基本校验：仅解析 User-agent(* 或本 Bot) 下的 Disallow/Allow。
     * 拉取失败或不存在 robots.txt 时视为允许（不因校验本身阻断调度）。
     */
    private boolean robotsAllowed(String url) {
        try {
            URI uri = new URI(url);
            String robotsUrl = uri.getScheme() + "://" + uri.getAuthority() + "/robots.txt";
            Connection.Response response = Jsoup.connect(robotsUrl)
                    .userAgent(userAgent)
                    .timeout(connectTimeoutMs)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            if (response.statusCode() >= 400) {
                return true;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath();
            boolean applies = false;
            boolean blocked = false;
            for (String line : response.body().split("\\r?\\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                int colon = l.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = l.substring(colon + 1).trim();
                if ("user-agent".equals(key)) {
                    applies = "*".equals(value) || value.toLowerCase(Locale.ROOT).contains("biggerink");
                } else if ("disallow".equals(key) && applies && !value.isEmpty() && path.startsWith(value)) {
                    blocked = true;
                } else if ("allow".equals(key) && applies && !value.isEmpty() && path.startsWith(value)) {
                    blocked = false;
                }
            }
            return !blocked;
        } catch (Exception e) {
            log.debug("robots 校验跳过 url={} err={}", url, e.getMessage());
            return true;
        }
    }

    private PageSnapshot saveSnapshotIfAbsent(Subscription sub, ScanTask task,
                                              String fingerprint, int length,
                                              String summary, int changed) {
        LambdaQueryWrapper<PageSnapshot> q = new LambdaQueryWrapper<PageSnapshot>()
                .eq(PageSnapshot::getSubscriptionId, sub.getId())
                .eq(PageSnapshot::getContentFingerprint, fingerprint);
        Long count = snapshotMapper.selectCount(q);
        if (count != null && count > 0) {
            return snapshotMapper.selectOne(q.last("LIMIT 1"));
        }
        PageSnapshot snapshot = new PageSnapshot();
        snapshot.setSubscriptionId(sub.getId());
        snapshot.setScanTaskId(task.getId());
        snapshot.setContentFingerprint(fingerprint);
        snapshot.setContentLength(length);
        snapshot.setDiffSummary(truncate(summary, diffSummaryMaxLen));
        snapshot.setIsChanged(changed);
        try {
            snapshotMapper.insert(snapshot);
            return snapshot;
        } catch (DuplicateKeyException e) {
            return snapshotMapper.selectOne(q.last("LIMIT 1"));
        }
    }

    private int latestSnapshotLength(Long subscriptionId) {
        PageSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<PageSnapshot>()
                .eq(PageSnapshot::getSubscriptionId, subscriptionId)
                .orderByDesc(PageSnapshot::getCreatedAt)
                .last("LIMIT 1"));
        return snapshot == null || snapshot.getContentLength() == null
                ? 0 : snapshot.getContentLength();
    }

    private void createChangeEvent(Subscription sub, ScanTask task, PageSnapshot snapshot,
                                   String summary, String changeType) {
        if (snapshot == null) {
            return;
        }
        createChangeEvent(sub, task, snapshot.getId(), summary, changeType);
    }

    private void createChangeEvent(Subscription sub, ScanTask task, Long snapshotId,
                                   String summary, String changeType) {
        Long dup = eventMapper.selectCount(new LambdaQueryWrapper<ChangeEvent>()
                .eq(ChangeEvent::getSubscriptionId, sub.getId())
                .eq(ChangeEvent::getSnapshotId, snapshotId));
        if (dup != null && dup > 0) {
            return;   // DB 最后一道防线：重复回调不产生重复事件
        }
        ChangeEvent event = new ChangeEvent();
        event.setEventNo("EV" + LocalDateTime.now(ZoneOffset.UTC).format(EVENT_NO_TS)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000)));
        event.setUserId(sub.getUserId());
        event.setSubscriptionId(sub.getId());
        event.setSnapshotId(snapshotId);
        event.setChangeType(changeType);
        event.setDiffSummary(truncate(summary, diffSummaryMaxLen));
        event.setConfirmed(1);        // 已二次确认才生成
        event.setReadFlag(0);
        event.setNotifyStatus("PENDING");
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            log.info("变更事件幂等去重命中 subId={} snapshotId={}", sub.getId(), snapshotId);
            return;
        }
        metrics.incChange();
        notificationService.notifyChange(event, sub);
        meta.audit(sub.getUserId(), "CHANGE_EVENT_CREATE", "change_event",
                String.valueOf(event.getId()), event.getEventNo() + " " + changeType);
    }

    /** 差异摘要：只含指纹前缀与长度变化等元数据，不含网页原文片段（合规）。 */
    private String buildDiffSummary(String oldFp, String newFp, int oldLen, int newLen,
                                    boolean keywordHit) {
        int delta = newLen - oldLen;
        StringBuilder sb = new StringBuilder();
        sb.append("内容指纹变化 ")
                .append(safe(oldFp)).append(" -> ").append(safe(newFp))
                .append("; 提取文本长度 ").append(oldLen).append(" -> ").append(newLen)
                .append(" (").append(delta >= 0 ? "+" : "").append(delta).append(")");
        if (keywordHit) {
            sb.append("; 关键词命中");
        }
        return truncate(sb.toString(), diffSummaryMaxLen);
    }

    private static String safe(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) {
            return String.valueOf(fingerprint);
        }
        return fingerprint.substring(0, 8);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record FetchResult(int httpStatus, String text) {
    }

    private static class ScanFetchException extends Exception {
        private final String code;

        ScanFetchException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
