package com.biggerink.watch.service;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NotifyChannelService {

    private final MetaService meta;

    @Value("${watch.notify.default-freq-limit:20}")
    private int defaultFreqLimit;

    public NotifyChannelService(MetaService meta) {
        this.meta = meta;
    }

    public List<ApiDtos.ChannelConfigVO> list(Long userId) {
        List<Map<String, Object>> rows = meta.listChannels(userId);
        if (rows.isEmpty()) {
            List<ApiDtos.ChannelConfigVO> defaults = new ArrayList<>();
            defaults.add(defaults("SITE"));
            defaults.add(defaults("EMAIL"));
            return defaults;
        }
        return rows.stream().map(this::toVO).toList();
    }

    public void update(Long userId, ApiDtos.ChannelConfigReq req) {
        String channel = req.getChannel().toUpperCase(Locale.ROOT);
        if (!List.of("SITE", "EMAIL", "WECOM").contains(channel)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "channel 仅支持 SITE/EMAIL/WECOM");
        }
        int enabled = req.getEnabled() == null ? 1 : (req.getEnabled() ? 1 : 0);
        int freq = req.getFreqLimitPerDay() == null ? defaultFreqLimit : req.getFreqLimitPerDay();
        if (freq < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "频率上限不能为负");
        }
        String target = req.getTarget() == null ? "" : req.getTarget();
        meta.upsertChannel(userId, channel, target, enabled, freq);
        meta.audit(userId, "NOTIFY_CHANNEL_UPDATE", "notify_channel_config", channel,
                "enabled=" + enabled + ", freq=" + freq);
    }

    private ApiDtos.ChannelConfigVO defaults(String channel) {
        ApiDtos.ChannelConfigVO vo = new ApiDtos.ChannelConfigVO();
        vo.setChannel(channel);
        vo.setTarget("");
        vo.setEnabled(true);
        vo.setFreqLimitPerDay(defaultFreqLimit);
        return vo;
    }

    private ApiDtos.ChannelConfigVO toVO(Map<String, Object> row) {
        ApiDtos.ChannelConfigVO vo = new ApiDtos.ChannelConfigVO();
        vo.setChannel(String.valueOf(row.get("channel")));
        vo.setTarget(row.get("target") == null ? "" : String.valueOf(row.get("target")));
        vo.setEnabled(intOf(row.get("enabled"), 1) == 1);
        vo.setFreqLimitPerDay(intOf(row.get("freq_limit_per_day"), defaultFreqLimit));
        Object lastSent = row.get("last_sent_at");
        if (lastSent instanceof LocalDateTime ldt) {
            vo.setLastSentAt(ldt);
        }
        return vo;
    }

    private static int intOf(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
