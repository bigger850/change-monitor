package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仅存内容指纹与差异摘要；抓取原文只在内存中用完即弃，绝不入库。
 */
@Data
@TableName("page_snapshot")
public class PageSnapshot {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long subscriptionId;
    private Long scanTaskId;
    private String contentFingerprint;
    private Integer contentLength;
    private String diffSummary;
    private Integer isChanged;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
