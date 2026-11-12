package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.entity.ScanTask;
import com.biggerink.watch.entity.Subscription;
import com.biggerink.watch.entity.UserAccount;
import com.biggerink.watch.mapper.ScanTaskMapper;
import com.biggerink.watch.mapper.SubscriptionMapper;
import com.biggerink.watch.mapper.UserAccountMapper;
import com.biggerink.watch.security.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端（ADMIN）：订阅审核、黑名单、扫描任务与重试、用户配额、sys_config、通知日志、审计日志。
 * 角色二次校验由 SecurityConfig 的 /admin/** hasRole("ADMIN") + 各方法 AuthContext.requireAdmin() 兜底。
 */
@Service
public class AdminService {

    private final SubscriptionMapper subscriptionMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final UserAccountMapper userMapper;
    private final SubscriptionService subscriptionService;
    private final ScanService scanService;
    private final MetaService meta;
    private final JdbcTemplate jdbc;

    public AdminService(SubscriptionMapper subscriptionMapper,
                        ScanTaskMapper scanTaskMapper,
                        UserAccountMapper userMapper,
                        SubscriptionService subscriptionService,
                        ScanService scanService,
                        MetaService meta,
                        JdbcTemplate jdbc) {
        this.subscriptionMapper = subscriptionMapper;
        this.scanTaskMapper = scanTaskMapper;
        this.userMapper = userMapper;
        this.subscriptionService = subscriptionService;
        this.scanService = scanService;
        this.meta = meta;
        this.jdbc = jdbc;
    }

    public PageResult<ApiDtos.SubscriptionVO> listSubscriptions(long page, long size, String status) {
        AuthContext.requireAdmin();
        Page<Subscription> p = new Page<>(page, size);
        IPage<Subscription> r = subscriptionMapper.selectPage(p,
                new LambdaQueryWrapper<Subscription>()
                        .eq(status != null && !status.isBlank(), Subscription::getStatus, status)
                        .orderByDesc(Subscription::getCreatedAt));
        List<ApiDtos.SubscriptionVO> list = r.getRecords().stream()
                .map(subscriptionService::toVO).toList();
        fillOwnerEmail(r.getRecords(), list);
        return PageResult.of(r.getTotal(), r.getCurrent(), r.getSize(), list);
    }

    /** 管理端需要看清订阅归属，批量补一次邮箱，避免 N+1 查询 */
    private void fillOwnerEmail(List<Subscription> records, List<ApiDtos.SubscriptionVO> list) {
        if (records.isEmpty() || list.isEmpty()) {
            return;
        }
        java.util.Set<Long> userIds = new java.util.HashSet<>();
        for (Subscription s : records) {
            if (s.getUserId() != null) {
                userIds.add(s.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> ownerMap = new java.util.HashMap<>();
        for (UserAccount u : userMapper.selectBatchIds(userIds)) {
            String label = (u.getEmail() != null && !u.getEmail().isBlank()) ? u.getEmail() : u.getUsername();
            ownerMap.put(u.getId(), label);
        }
        for (ApiDtos.SubscriptionVO vo : list) {
            if (vo.getUserId() == null) {
                continue;
            }
            try {
                vo.setOwnerEmail(ownerMap.get(Long.valueOf(vo.getUserId())));
            } catch (NumberFormatException ignored) {
                // userId 非数字时留空即可，不影响列表展示
            }
        }
    }

    public void approve(Long id) {
        AuthContext.requireAdmin();
        Subscription sub = requireSubscription(id);
        sub.setStatus("ACTIVE");
        sub.setEnabled(1);
        sub.setFailCount(0);
        sub.setConsecutiveDiffCount(0);
        sub.setNextScanAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(sub.getCheckIntervalSec()));
        subscriptionMapper.updateById(sub);
        meta.audit(AuthContext.userId(), "SUBSCRIPTION_APPROVE", "subscription",
                String.valueOf(id), "审核通过");
    }

    public void reject(Long id, String reason) {
        AuthContext.requireAdmin();
        Subscription sub = requireSubscription(id);
        sub.setStatus("TERMINATED");
        sub.setEnabled(0);
        subscriptionMapper.updateById(sub);
        meta.addBlacklist(sub.getDomain(), reason == null ? "管理端驳回" : reason, AuthContext.userId());
        meta.audit(AuthContext.userId(), "SUBSCRIPTION_REJECT", "subscription",
                String.valueOf(id), "驳回并加入黑名单: " + reason);
    }

    public List<ApiDtos.BlacklistVO> blacklist() {
        AuthContext.requireAdmin();
        return meta.listBlacklist().stream().map(this::toBlacklistVO).toList();
    }

    public void addBlacklist(ApiDtos.BlacklistReq req) {
        AuthContext.requireAdmin();
        meta.addBlacklist(req.getDomainPattern(), req.getReason(), AuthContext.userId());
        meta.audit(AuthContext.userId(), "BLACKLIST_ADD", "source_blacklist",
                req.getDomainPattern(), req.getReason());
    }

    public void deleteBlacklist(Long id) {
        AuthContext.requireAdmin();
        if (meta.deleteBlacklist(id) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "黑名单记录不存在");
        }
        meta.audit(AuthContext.userId(), "BLACKLIST_DELETE", "source_blacklist",
                String.valueOf(id), "删除黑名单");
    }

    public PageResult<ApiDtos.ScanTaskVO> scanTasks(long page, long size,
                                                    String status, String errorCode) {
        AuthContext.requireAdmin();
        Page<ScanTask> p = new Page<>(page, size);
        IPage<ScanTask> r = scanTaskMapper.selectPage(p,
                new LambdaQueryWrapper<ScanTask>()
                        .eq(status != null && !status.isBlank(), ScanTask::getStatus, status)
                        .eq(errorCode != null && !errorCode.isBlank(), ScanTask::getErrorCode, errorCode)
                        .orderByDesc(ScanTask::getCreatedAt));
        List<ApiDtos.ScanTaskVO> list = r.getRecords().stream().map(this::toScanTaskVO).toList();
        return PageResult.of(r.getTotal(), r.getCurrent(), r.getSize(), list);
    }

    public void retryScanTask(Long id) {
        AuthContext.requireAdmin();
        ScanTask old = scanTaskMapper.selectById(id);
        if (old == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "扫描任务不存在");
        }
        if (!List.of("FAILED", "TIMEOUT").contains(old.getStatus())) {
            throw new BizException(ErrorCode.STATUS_NOT_ALLOWED, "仅 FAILED / TIMEOUT 任务可重投");
        }
        ScanTask retry = new ScanTask();
        retry.setSubscriptionId(old.getSubscriptionId());
        retry.setUserId(old.getUserId());
        retry.setTriggerType("RETRY");
        retry.setBatchNo(old.getBatchNo());
        retry.setStatus("PENDING");
        retry.setIdempotencyKey("RETRY-" + id + "-" + System.currentTimeMillis());
        scanTaskMapper.insert(retry);
        meta.audit(AuthContext.userId(), "SCAN_TASK_RETRY", "scan_task",
                String.valueOf(retry.getId()), "重投自任务 " + id);
        scanService.execute(retry.getId());
    }

    public PageResult<ApiDtos.UserVO> users(long page, long size) {
        AuthContext.requireAdmin();
        Page<UserAccount> p = new Page<>(page, size);
        IPage<UserAccount> r = userMapper.selectPage(p,
                new LambdaQueryWrapper<UserAccount>().orderByDesc(UserAccount::getCreatedAt));
        List<ApiDtos.UserVO> list = r.getRecords().stream().map(AuthService::toVO).toList();
        return PageResult.of(r.getTotal(), r.getCurrent(), r.getSize(), list);
    }

    public void updateQuota(Long id, ApiDtos.QuotaReq req) {
        AuthContext.requireAdmin();
        UserAccount user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        user.setQuotaSubscription(req.getQuotaSubscription());
        user.setQuotaScanDaily(req.getQuotaScanDaily());
        userMapper.updateById(user);
        meta.audit(AuthContext.userId(), "USER_QUOTA_UPDATE", "user", String.valueOf(id),
                "subscription=" + req.getQuotaSubscription() + ", scanDaily=" + req.getQuotaScanDaily());
    }

    public Map<String, String> config() {
        AuthContext.requireAdmin();
        return meta.allConfig();
    }

    public void updateConfig(ApiDtos.ConfigReq req) {
        AuthContext.requireAdmin();
        meta.upsertConfig(req.getConfigKey(), req.getConfigValue(),
                req.getRemark(), AuthContext.userId());
        meta.audit(AuthContext.userId(), "SYS_CONFIG_UPDATE", "sys_config",
                req.getConfigKey(), req.getConfigValue());
    }

    public PageResult<Map<String, Object>> notificationRecords(long page, long size, String status) {
        AuthContext.requireAdmin();
        StringBuilder where = new StringBuilder(" WHERE deleted=0");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status=?");
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_record" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, event_id, user_id, channel, target, subject, status, retry_count, "
                        + "sent_at, error_msg, created_at FROM notification_record" + where
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(total == null ? 0 : total, page, size, rows);
    }

    public PageResult<Map<String, Object>> auditLogs(long page, long size,
                                                     String traceId, Long userId) {
        AuthContext.requireAdmin();
        long total = meta.countAuditLogs(traceId, userId);
        List<Map<String, Object>> rows = meta.queryAuditLogs(traceId, userId,
                (int) size, (int) ((page - 1) * size));
        return PageResult.of(total, page, size, rows);
    }

    private Subscription requireSubscription(Long id) {
        Subscription sub = subscriptionMapper.selectById(id);
        if (sub == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订阅不存在");
        }
        return sub;
    }

    private ApiDtos.BlacklistVO toBlacklistVO(Map<String, Object> row) {
        ApiDtos.BlacklistVO vo = new ApiDtos.BlacklistVO();
        vo.setId(String.valueOf(row.get("id")));
        vo.setDomainPattern(String.valueOf(row.get("domain_pattern")));
        vo.setReason(row.get("reason") == null ? null : String.valueOf(row.get("reason")));
        vo.setStatus(String.valueOf(row.get("status")));
        vo.setCreatedBy(row.get("created_by") == null ? null : String.valueOf(row.get("created_by")));
        vo.setCreatedAt(toLocalDateTime(row.get("created_at")));
        return vo;
    }

    private ApiDtos.ScanTaskVO toScanTaskVO(ScanTask t) {
        ApiDtos.ScanTaskVO vo = new ApiDtos.ScanTaskVO();
        vo.setId(String.valueOf(t.getId()));
        vo.setSubscriptionId(String.valueOf(t.getSubscriptionId()));
        vo.setUserId(String.valueOf(t.getUserId()));
        vo.setTriggerType(t.getTriggerType());
        vo.setBatchNo(t.getBatchNo());
        vo.setStatus(t.getStatus());
        vo.setHttpStatus(t.getHttpStatus());
        vo.setCostMs(t.getCostMs());
        vo.setErrorCode(t.getErrorCode());
        vo.setErrorMsg(t.getErrorMsg());
        vo.setStartAt(t.getStartAt());
        vo.setEndAt(t.getEndAt());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
