package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subscription")
public class Subscription {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String name;
    private String url;
    private String urlHash;
    private String domain;
    private String selectorType;
    private String selectorExpr;
    private String keyword;
    private Integer checkIntervalSec;
    private String status;
    private Integer consecutiveDiffCount;
    private String lastFingerprint;
    private LocalDateTime lastScanAt;
    private LocalDateTime lastChangeAt;
    private LocalDateTime nextScanAt;
    private Integer failCount;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
