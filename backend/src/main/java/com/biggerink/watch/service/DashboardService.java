package com.biggerink.watch.service;

import com.biggerink.watch.common.ApiDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 大盘聚合：前端只消费后端聚合结果，不拉原始流水自行统计。
 * P95 为当日 cost_ms 在应用内排序取分位（P0 数据量下的简化实现，P2 换窗口函数/预聚合）。
 */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final SseService sse;
    private final RedisOps redis;

    public DashboardService(JdbcTemplate jdbc, SseService sse, RedisOps redis) {
        this.jdbc = jdbc;
        this.sse = sse;
        this.redis = redis;
    }

    public ApiDtos.DashboardOverviewVO overview() {
        LocalDateTime today = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        ApiDtos.DashboardOverviewVO vo = new ApiDtos.DashboardOverviewVO();
        vo.setSubscriptionTotal(count("SELECT COUNT(*) FROM subscription WHERE deleted=0"));
        vo.setScanToday(count("SELECT COUNT(*) FROM scan_task WHERE deleted=0 AND created_at>=?", today));
        vo.setChangeToday(count("SELECT COUNT(*) FROM change_event WHERE deleted=0 AND created_at>=?", today));
        long finished = count("SELECT COUNT(*) FROM scan_task WHERE deleted=0 AND created_at>=? "
                + "AND status IN ('SUCCESS','FAILED','TIMEOUT')", today);
        long success = count("SELECT COUNT(*) FROM scan_task WHERE deleted=0 AND created_at>=? "
                + "AND status='SUCCESS'", today);
        vo.setSuccessRate(finished == 0 ? 0.0 : (double) success / finished);
        vo.setP95CostMs(p95CostMs(today));
        vo.setQueueBacklog(count("SELECT COUNT(*) FROM scan_task WHERE deleted=0 "
                + "AND status IN ('PENDING','RUNNING')"));
        return vo;
    }

    public List<ApiDtos.TrendPointVO> trend(int days) {
        int n = Math.max(1, Math.min(days, 30));
        LocalDateTime start = LocalDate.now(ZoneOffset.UTC).minusDays(n - 1L).atStartOfDay();
        Map<String, ApiDtos.TrendPointVO> points = new TreeMap<>();

        List<Map<String, Object>> scans = jdbc.queryForList(
                "SELECT DATE(created_at) AS d, COUNT(*) AS c, "
                        + "SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) AS s "
                        + "FROM scan_task WHERE deleted=0 AND created_at>=? GROUP BY DATE(created_at)",
                start);
        for (Map<String, Object> row : scans) {
            String day = String.valueOf(row.get("d"));
            ApiDtos.TrendPointVO p = points.computeIfAbsent(day, k -> newPoint(k));
            p.setScanCount(longOf(row.get("c")));
            p.setSuccessCount(longOf(row.get("s")));
        }
        List<Map<String, Object>> changes = jdbc.queryForList(
                "SELECT DATE(created_at) AS d, COUNT(*) AS c FROM change_event "
                        + "WHERE deleted=0 AND created_at>=? GROUP BY DATE(created_at)", start);
        for (Map<String, Object> row : changes) {
            String day = String.valueOf(row.get("d"));
            ApiDtos.TrendPointVO p = points.computeIfAbsent(day, k -> newPoint(k));
            p.setChangeCount(longOf(row.get("c")));
        }
        return new ArrayList<>(points.values());
    }

    public Map<String, Object> realtime() {
        ApiDtos.DashboardOverviewVO overview = overview();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("subscriptionTotal", overview.getSubscriptionTotal());
        snapshot.put("scanToday", overview.getScanToday());
        snapshot.put("changeToday", overview.getChangeToday());
        snapshot.put("successRate", overview.getSuccessRate());
        snapshot.put("p95CostMs", overview.getP95CostMs());
        snapshot.put("queueBacklog", overview.getQueueBacklog());
        snapshot.put("sseConnections", sse.connectionCount());
        snapshot.put("redisUp", redis.ping());
        snapshot.put("serverTime", LocalDateTime.now(ZoneOffset.UTC).toString());
        return snapshot;
    }

    private long p95CostMs(LocalDateTime today) {
        List<Long> costs = jdbc.queryForList(
                "SELECT cost_ms FROM scan_task WHERE deleted=0 AND cost_ms IS NOT NULL "
                        + "AND created_at>=? ORDER BY cost_ms", Long.class, today);
        if (costs.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(costs.size() * 0.95) - 1;
        return costs.get(Math.max(0, Math.min(index, costs.size() - 1)));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static ApiDtos.TrendPointVO newPoint(String day) {
        ApiDtos.TrendPointVO p = new ApiDtos.TrendPointVO();
        p.setStatDate(day);
        return p;
    }

    private static long longOf(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
