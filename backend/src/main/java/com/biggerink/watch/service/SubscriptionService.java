package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.common.HashUtil;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.entity.ScanTask;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.mapper.ScanTaskMapper;
import com.biggerink.watch.mapper.SubscriptionMapper;
import com.biggerink.watch.security.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SubscriptionService {

    private final SubscriptionMapper subscriptionMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final ScanService scanService;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final MetaService meta;
    private final RedisOps redis;

    @Value("${watch.scan.min-interval-sec:300}")
    private int minIntervalSec;

    @Value("${watch.scan.domain-min-gap-sec:30}")
    private long domainMinGapSec;

    @Value("${watch.notify.default-freq-limit:20}")
    private int defaultFreqLimit;

    public SubscriptionService(SubscriptionMapper subscriptionMapper,
                               ScanTaskMapper scanTaskMapper,
                               ScanService scanService,
                               QuotaService quotaService,
                               IdempotencyService idempotencyService,
                               MetaService meta,
                               RedisOps redis) {
        this.subscriptionMapper = subscriptionMapper;
        this.scanTaskMapper = scanTaskMapper;
        this.scanService = scanService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.meta = meta;
        this.redis = redis;
    }

    public PageResult<ApiDtos.SubscriptionVO> list(Long userId, long page, long size, String status) {
        Page<Subscription> p = new Page<>(page, size);
        IPage<Subscription> result = subscriptionMapper.selectPage(p,
                new LambdaQueryWrapper<Subscription>()
                        .eq(Subscription::getUserId, userId)
                        .eq(status != null && !status.isBlank(), Subscription::getStatus, status)
                        .orderByDesc(Subscription::getCreatedAt));
        List<ApiDtos.SubscriptionVO> list = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), list);
    }

    public ApiDtos.SubscriptionVO create(Long userId, ApiDtos.SubscriptionCreateReq req) {
        String normalized = normalizeUrl(req.getUrl());
        String domain = extractDomain(normalized);
        if (meta.isBlacklisted(domain)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "URL 命中源站黑名单");
        }
        int minInterval = meta.getIntConfig("scan.min_interval_sec", minIntervalSec);
        if (req.getCheckIntervalSec() < minInterval) {
            throw new BizException(ErrorCode.PARAM_INVALID, "检查间隔不得小于 " + minInterval + " 秒");
        }
        String urlHash = HashUtil.sha256Hex(normalized);
        Long exists = subscriptionMapper.selectCount(new LambdaQueryWrapper<Subscription>()
                .eq(Subscription::getUserId, userId)
                .eq(Subscription::getUrlHash, urlHash));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该 URL 已存在订阅");
        }
        quotaService.checkSubscriptionQuota(userId);

        Subscription sub = new Subscription();
        sub.setUserId(userId);
        sub.setName(req.getName());
        sub.setUrl(normalized);
        sub.setUrlHash(urlHash);
        sub.setDomain(domain);
        sub.setSelectorType(validSelectorType(req.getSelectorType()));
        sub.setSelectorExpr(req.getSelectorExpr());
        sub.setKeyword(req.getKeyword());
        validateSelector(sub.getSelectorType(), sub.getSelectorExpr());
        sub.setCheckIntervalSec(req.getCheckIntervalSec());
        sub.setStatus("ACTIVE");
        sub.setConsecutiveDiffCount(0);
        sub.setFailCount(0);
        sub.setEnabled(1);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        sub.setNextScanAt(now.plusSeconds(req.getCheckIntervalSec()));
        subscriptionMapper.insert(sub);

        List<String> channels = (req.getNotifyChannels() == null || req.getNotifyChannels().isEmpty())
                ? List.of("SITE", "EMAIL") : req.getNotifyChannels();
        for (String channel : channels) {
            meta.upsertChannel(userId, channel, "", 1, defaultFreqLimit);
        }
        meta.audit(userId, "SUBSCRIPTION_CREATE", "subscription",
                String.valueOf(sub.getId()), normalized);
        return toVO(sub);
    }

    public ApiDtos.SubscriptionVO detail(Long userId, Long id) {
        Subscription sub = loadOwned(userId, id);
        ApiDtos.SubscriptionVO vo = toVO(sub);
        List<ScanTask> recent = scanTaskMapper.selectList(new LambdaQueryWrapper<ScanTask>()
                .eq(ScanTask::getSubscriptionId, id)
                .orderByDesc(ScanTask::getCreatedAt)
                .last("LIMIT 1"));
        if (!recent.isEmpty()) {
            ScanTask t = recent.get(0);
            Map<String, Object> lastScan = new HashMap<>();
            lastScan.put("status", t.getStatus());
            lastScan.put("triggerType", t.getTriggerType());
            lastScan.put("httpStatus", t.getHttpStatus());
            lastScan.put("costMs", t.getCostMs());
            lastScan.put("errorMsg", t.getErrorMsg());
            lastScan.put("startAt", t.getStartAt());
            vo.setLastScan(lastScan);
        }
        return vo;
    }

    public ApiDtos.SubscriptionVO update(Long userId, Long id, ApiDtos.SubscriptionUpdateReq req) {
        Subscription sub = loadOwned(userId, id);
        if ("TERMINATED".equals(sub.getStatus())) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED);
        }
        if (req.getName() != null) {
            sub.setName(req.getName());
        }
        if (req.getSelectorType() != null) {
            sub.setSelectorType(validSelectorType(req.getSelectorType()));
        }
        if (req.getSelectorExpr() != null) {
            sub.setSelectorExpr(req.getSelectorExpr());
        }
        if (req.getKeyword() != null) {
            sub.setKeyword(req.getKeyword());
        }
        if (req.getCheckIntervalSec() != null) {
            int minInterval = meta.getIntConfig("scan.min_interval_sec", minIntervalSec);
            if (req.getCheckIntervalSec() < minInterval) {
                throw new BizException(ErrorCode.PARAM_INVALID, "检查间隔不得小于 " + minInterval + " 秒");
            }
            sub.setCheckIntervalSec(req.getCheckIntervalSec());
            sub.setNextScanAt(LocalDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(req.getCheckIntervalSec()));
        }
        validateSelector(sub.getSelectorType(), sub.getSelectorExpr());
        subscriptionMapper.updateById(sub);
        if (req.getNotifyChannels() != null && !req.getNotifyChannels().isEmpty()) {
            for (String channel : req.getNotifyChannels()) {
                meta.upsertChannel(userId, channel, "", 1, defaultFreqLimit);
            }
        }
        meta.audit(userId, "SUBSCRIPTION_UPDATE", "subscription", String.valueOf(id), "修改订阅规则");
        return toVO(sub);
    }

    public ApiDtos.SubscriptionVO changeStatus(Long userId, Long id, String target) {
        Subscription sub = loadOwned(userId, id);
        String current = sub.getStatus();
        if ("TERMINATED".equals(current)) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED, "TERMINATED 不可变更状态");
        }
        if ("ACTIVE".equals(target)) {
            if ("PAUSED".equals(current) || "ABNORMAL".equals(current)) {
                sub.setStatus("ACTIVE");
                sub.setFailCount(0);
                sub.setConsecutiveDiffCount(0);
                sub.setNextScanAt(LocalDateTime.now(ZoneOffset.UTC)
                        .plusSeconds(sub.getCheckIntervalSec()));
            } else if (!"ACTIVE".equals(current)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "非法的目标状态");
            }
        } else if ("PAUSED".equals(target)) {
            sub.setStatus("PAUSED");
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持 ACTIVE / PAUSED");
        }
        sub.setEnabled("ACTIVE".equals(sub.getStatus()) ? 1 : 0);
        subscriptionMapper.updateById(sub);
        meta.audit(userId, "SUBSCRIPTION_STATUS", "subscription", String.valueOf(id), target);
        return toVO(sub);
    }

    public void delete(Long userId, Long id) {
        Subscription sub = loadOwned(userId, id);
        sub.setStatus("TERMINATED");
        sub.setEnabled(0);
        subscriptionMapper.updateById(sub);
        subscriptionMapper.deleteById(id);   // @TableLogic -> deleted=1
        meta.audit(userId, "SUBSCRIPTION_DELETE", "subscription", String.valueOf(id), "逻辑删除");
    }

    /** 手动触发：幂等(5分钟窗口) + 配额 + 域名限速 + 写 scan_task + 同步执行抓取 */
    public ApiDtos.ScanTriggerVO triggerScan(Long userId, Long id, String idemKey) {
        Subscription sub = loadOwned(userId, id);
        if ("TERMINATED".equals(sub.getStatus()) || "ABNORMAL".equals(sub.getStatus())) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED,
                    "TERMINATED / ABNORMAL 状态不允许触发扫描");
        }
        idempotencyService.manualScanGuard(id);
        quotaService.checkScanQuota(userId);
        if (!redis.acquireDomainSlot(sub.getDomain(), domainMinGapSec)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "同域名扫描过于频繁（并发 1 / 间隔 " + domainMinGapSec + "s）");
        }

        ScanTask task = new ScanTask();
        task.setSubscriptionId(id);
        task.setUserId(userId);
        task.setTriggerType("MANUAL");
        task.setBatchNo("M" + System.currentTimeMillis());
        task.setStatus("PENDING");
        task.setIdempotencyKey(idemKey + "-" + id);
        scanTaskMapper.insert(task);

        scanService.execute(task.getId());

        ApiDtos.ScanTriggerVO vo = new ApiDtos.ScanTriggerVO();
        vo.setTaskId(String.valueOf(task.getId()));
        vo.setBatchNo(task.getBatchNo());
        vo.setMessage("手动扫描已执行");
        meta.audit(userId, "SUBSCRIPTION_SCAN", "subscription", String.valueOf(id),
                "手动触发 scan_task=" + task.getId());
        return vo;
    }

    /** 归属过滤 + 不存在校验 */
    public Subscription loadOwned(Long userId, Long id) {
        Subscription sub = subscriptionMapper.selectById(id);
        if (sub == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订阅不存在");
        }
        AuthContext.requireOwner(sub.getUserId());
        return sub;
    }

    public ApiDtos.SubscriptionVO toVO(Subscription s) {
        ApiDtos.SubscriptionVO vo = new ApiDtos.SubscriptionVO();
        vo.setId(String.valueOf(s.getId()));
        vo.setUserId(String.valueOf(s.getUserId()));
        vo.setName(s.getName());
        vo.setUrl(s.getUrl());
        vo.setDomain(s.getDomain());
        vo.setSelectorType(s.getSelectorType());
        vo.setSelectorExpr(s.getSelectorExpr());
        vo.setKeyword(s.getKeyword());
        vo.setCheckIntervalSec(s.getCheckIntervalSec());
        vo.setStatus(s.getStatus());
        vo.setFailCount(s.getFailCount());
        vo.setConsecutiveDiffCount(s.getConsecutiveDiffCount());
        vo.setLastScanAt(s.getLastScanAt());
        vo.setLastChangeAt(s.getLastChangeAt());
        vo.setNextScanAt(s.getNextScanAt());
        vo.setCreatedAt(s.getCreatedAt());
        return vo;
    }

    private String validSelectorType(String type) {
        String t = type == null ? "CSS" : type.toUpperCase(Locale.ROOT);
        if (!List.of("CSS", "KEYWORD", "REGEX").contains(t)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "selectorType 仅支持 CSS/KEYWORD/REGEX");
        }
        return t;
    }

    /** 创建/修改时校验选择器语法，避免运行时 Jsoup 抛异常。 */
    private void validateSelector(String type, String expr) {
        if ("CSS".equals(type) && expr != null && !expr.isBlank()) {
            try {
                org.jsoup.Jsoup.parse("<html><body></body></html>").select(expr);
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "CSS 选择器非法: " + e.getMessage());
            }
        }
        if ("REGEX".equals(type) && expr != null && !expr.isBlank()) {
            try {
                java.util.regex.Pattern.compile(expr);
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "正则表达式非法: " + e.getMessage());
            }
        }
    }

    private String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "URL 不能为空");
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "仅支持 http/https 公开网页");
            }
            if (uri.getHost() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "URL 缺少主机名");
            }
            int port = uri.getPort();
            boolean defaultPort = (port == 80 && "http".equals(scheme))
                    || (port == 443 && "https".equals(scheme));
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
            if (port > 0 && !defaultPort) {
                sb.append(':').append(port);
            }
            String path = uri.getRawPath();
            sb.append(path == null || path.isEmpty() ? "/" : path);
            if (uri.getRawQuery() != null) {
                sb.append('?').append(uri.getRawQuery());
            }
            return sb.toString();   // 丢弃 fragment
        } catch (URISyntaxException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "URL 格式非法");
        }
    }

    private String extractDomain(String normalizedUrl) {
        try {
            return new URI(normalizedUrl).getHost().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "URL 格式非法");
        }
    }
}
