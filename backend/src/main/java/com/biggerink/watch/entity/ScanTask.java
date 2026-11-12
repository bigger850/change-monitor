package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_task")
public class ScanTask {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long subscriptionId;
    private Long userId;
    private String triggerType;
    private String batchNo;
    private String status;
    private String idempotencyKey;
    private Integer httpStatus;
    private Integer costMs;
    private String errorCode;
    private String errorMsg;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
