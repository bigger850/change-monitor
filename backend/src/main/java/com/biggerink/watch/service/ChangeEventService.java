package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.common.PageResult;
import com.biggerink.watch.entity.ChangeEvent;
import com.biggerink.watch.mapper.ChangeEventMapper;
import com.biggerink.watch.security.AuthContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

@Service
public class ChangeEventService {

    private final ChangeEventMapper eventMapper;
    private final MetaService meta;

    public ChangeEventService(ChangeEventMapper eventMapper, MetaService meta) {
        this.eventMapper = eventMapper;
        this.meta = meta;
    }

    public PageResult<ApiDtos.ChangeEventVO> timeline(Long userId, long page, long size,
                                                      Long subscriptionId, String keyword) {
        Page<ChangeEvent> p = new Page<>(page, size);
        IPage<ChangeEvent> result = eventMapper.selectPage(p, baseQuery(userId)
                .eq(subscriptionId != null, ChangeEvent::getSubscriptionId, subscriptionId)
                .like(keyword != null && !keyword.isBlank(), ChangeEvent::getDiffSummary, keyword)
                .orderByDesc(ChangeEvent::getCreatedAt));
        List<ApiDtos.ChangeEventVO> list = result.getRecords().stream().map(ChangeEventService::toVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), list);
    }

    public ApiDtos.ChangeEventVO detail(Long userId, Long id) {
        ChangeEvent event = loadOwned(userId, id);
        return toVO(event);
    }

    public void markRead(Long userId, Long id) {
        ChangeEvent event = loadOwned(userId, id);
        event.setReadFlag(1);
        eventMapper.updateById(event);
        meta.audit(userId, "CHANGE_EVENT_READ", "change_event", String.valueOf(id), "标记已读");
    }

    /** 后端生成 CSV 文件流（含 BOM 便于 Excel 打开中文），前端只触发下载。 */
    public void exportCsv(Long userId, Long subscriptionId, String keyword,
                          HttpServletResponse response) throws IOException {
        List<ChangeEvent> events = eventMapper.selectList(baseQuery(userId)
                .eq(subscriptionId != null, ChangeEvent::getSubscriptionId, subscriptionId)
                .like(keyword != null && !keyword.isBlank(), ChangeEvent::getDiffSummary, keyword)
                .orderByDesc(ChangeEvent::getCreatedAt)
                .last("LIMIT 10000"));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=change-events.csv");
        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");
        writer.write("eventNo,subscriptionId,changeType,diffSummary,notifyStatus,readFlag,createdAt\n");
        for (ChangeEvent e : events) {
            writer.write(String.join(",",
                    csv(e.getEventNo()),
                    csv(String.valueOf(e.getSubscriptionId())),
                    csv(e.getChangeType()),
                    csv(e.getDiffSummary()),
                    csv(e.getNotifyStatus()),
                    csv(String.valueOf(e.getReadFlag())),
                    csv(String.valueOf(e.getCreatedAt()))));
            writer.write('\n');
        }
        writer.flush();
        meta.audit(userId, "CHANGE_EVENT_EXPORT", "change_event", null,
                "导出 CSV " + events.size() + " 行");
    }

    public static ApiDtos.ChangeEventVO toVO(ChangeEvent e) {
        ApiDtos.ChangeEventVO vo = new ApiDtos.ChangeEventVO();
        vo.setId(String.valueOf(e.getId()));
        vo.setEventNo(e.getEventNo());
        vo.setSubscriptionId(String.valueOf(e.getSubscriptionId()));
        vo.setChangeType(e.getChangeType());
        vo.setDiffSummary(e.getDiffSummary());
        vo.setConfirmed(Integer.valueOf(1).equals(e.getConfirmed()));
        vo.setReadFlag(Integer.valueOf(1).equals(e.getReadFlag()));
        vo.setNotifyStatus(e.getNotifyStatus());
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }

    private ChangeEvent loadOwned(Long userId, Long id) {
        ChangeEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "变更事件不存在");
        }
        AuthContext.requireOwner(event.getUserId());
        return event;
    }

    private static LambdaQueryWrapper<ChangeEvent> baseQuery(Long userId) {
        return new LambdaQueryWrapper<ChangeEvent>()
                .eq(ChangeEvent::getUserId, userId)
                .eq(ChangeEvent::getConfirmed, 1);   // 未确认事件不进用户时间线
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
