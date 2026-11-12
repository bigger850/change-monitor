package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.biggerink.watch.common.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 5 张非核心表的简化访问（JdbcTemplate，不建实体）：
 * source_blacklist / audit_log / quota_usage / sys_config / notify_channel_config
 */
@Service
public class MetaService {

    private static final Logger log = LoggerFactory.getLogger(MetaService.class);

    private final JdbcTemplate jdbc;

    public MetaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 黑名单 ----------------
    public boolean isBlacklisted(String domain) {
        if (domain == null) {
            return false;
        }
        List<String> patterns = jdbc.queryForList(
                "SELECT domain_pattern FROM source_blacklist WHERE deleted=0 AND status='ACTIVE'",
                String.class);
        String d = domain.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            String low = p.toLowerCase(Locale.ROOT);
            if (d.equals(low) || d.endsWith("." + low)) {
                return true;
            }
            if (low.contains("*") && d.matches(low.replace(".", "\\.").replace("*", ".*"))) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> listBlacklist() {
        return jdbc.queryForList(
                "SELECT id, domain_pattern, reason, status, created_by, created_at "
                        + "FROM source_blacklist WHERE deleted=0 ORDER BY created_at DESC");
    }

    public void addBlacklist(String pattern, String reason, Long createdBy) {
        jdbc.update("INSERT IGNORE INTO source_blacklist "
                        + "(id, domain_pattern, reason, status, created_by) VALUES (?,?,?,'ACTIVE',?)",
                IdWorker.getId(), pattern, reason, createdBy);
    }

    public int deleteBlacklist(Long id) {
        return jdbc.update("UPDATE source_blacklist SET deleted=1 WHERE id=? AND deleted=0", id);
    }

    public int setBlacklistStatus(Long id, String status) {
        return jdbc.update("UPDATE source_blacklist SET status=? WHERE id=? AND deleted=0", status, id);
    }

    // ---------------- 审计日志 ----------------
    public void audit(Long userId, String action, String resourceType,
                      String resourceId, String detail) {
        String ip = null;
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            ip = attrs.getRequest().getRemoteAddr();
        }
        try {
            jdbc.update("INSERT INTO audit_log "
                            + "(id, user_id, action, resource_type, resource_id, detail, ip, trace_id) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    IdWorker.getId(), userId, action, resourceType, resourceId,
                    detail, ip, TraceContext.get());
        } catch (Exception e) {
            log.warn("审计日志写入失败 action={} err={}", action, e.getMessage());
        }
    }

    public long countAuditLogs(String traceId, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_log WHERE deleted=0");
        List<Object> args = new ArrayList<>();
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id=?");
            args.add(traceId);
        }
        if (userId != null) {
            sql.append(" AND user_id=?");
            args.add(userId);
        }
        Long c = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> queryAuditLogs(String traceId, Long userId, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, user_id, action, resource_type, resource_id, detail, ip, trace_id, created_at "
                        + "FROM audit_log WHERE deleted=0");
        List<Object> args = new ArrayList<>();
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id=?");
            args.add(traceId);
        }
        if (userId != null) {
            sql.append(" AND user_id=?");
            args.add(userId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------------- sys_config ----------------
    public Map<String, String> allConfig() {
        Map<String, String> result = new HashMap<>();
        jdbc.queryForList("SELECT config_key, config_value FROM sys_config WHERE deleted=0")
                .forEach(row -> result.put(String.valueOf(row.get("config_key")),
                        String.valueOf(row.get("config_value"))));
        return result;
    }

    public int getIntConfig(String key, int defaultValue) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT config_value FROM sys_config WHERE config_key=? AND deleted=0",
                    String.class, key);
            return v == null ? defaultValue : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void upsertConfig(String key, String value, String remark, Long updatedBy) {
        jdbc.update("INSERT INTO sys_config (config_key, config_value, remark, updated_by) "
                        + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "config_value=VALUES(config_value), remark=VALUES(remark), updated_by=VALUES(updated_by), deleted=0",
                key, value, remark, updatedBy);
    }

    // ---------------- quota_usage ----------------
    public void incrScanCount(Long userId) {
        jdbc.update("INSERT INTO quota_usage (id, user_id, stat_date, scan_count, notify_count) "
                        + "VALUES (?,?,?,1,0) ON DUPLICATE KEY UPDATE scan_count=scan_count+1",
                IdWorker.getId(), userId, LocalDate.now(ZoneOffset.UTC));
    }

    public void incrNotifyCount(Long userId) {
        jdbc.update("INSERT INTO quota_usage (id, user_id, stat_date, scan_count, notify_count) "
                        + "VALUES (?,?,?,0,1) ON DUPLICATE KEY UPDATE notify_count=notify_count+1",
                IdWorker.getId(), userId, LocalDate.now(ZoneOffset.UTC));
    }

    // ---------------- notify_channel_config ----------------
    public List<Map<String, Object>> listChannels(Long userId) {
        return jdbc.queryForList(
                "SELECT id, channel, target, enabled, freq_limit_per_day, last_sent_at "
                        + "FROM notify_channel_config WHERE deleted=0 AND user_id=? ORDER BY channel",
                userId);
    }

    public void upsertChannel(Long userId, String channel, String target,
                              int enabled, int freqLimit) {
        jdbc.update("INSERT INTO notify_channel_config "
                        + "(id, user_id, channel, target, enabled, freq_limit_per_day) "
                        + "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "enabled=VALUES(enabled), freq_limit_per_day=VALUES(freq_limit_per_day), deleted=0",
                IdWorker.getId(), userId, channel, target == null ? "" : target, enabled, freqLimit);
    }

    /** 今日该用户某渠道已成功发送数（频率上限判定） */
    public long countSentToday(Long userId, String channel) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_record WHERE deleted=0 AND user_id=? "
                        + "AND channel=? AND status='SENT' AND sent_at>=?",
                Long.class, userId, channel, LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        return c == null ? 0L : c;
    }
}
