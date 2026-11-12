package com.biggerink.watch.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_account")
public class UserAccount {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String passwordHash;
    private String email;
    private String phone;
    private String role;
    private String status;
    private Integer quotaSubscription;
    private Integer quotaScanDaily;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
