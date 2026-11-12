package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_record")
public class NotificationRecord {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventId;
    private Long userId;
    private String channel;
    private String target;
    private String subject;
    private String contentDigest;
    private String status;
    private Integer retryCount;
    private String idempotencyKey;
    private LocalDateTime sentAt;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
