package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("change_event")
public class ChangeEvent {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String eventNo;
    private Long userId;
    private Long subscriptionId;
    private Long snapshotId;
    private String changeType;
    private String diffSummary;
    private Integer confirmed;
    private Integer readFlag;
    private String notifyStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
