package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long vhId;
    private Long vhVersionId;
    private Long userId;

    /** PROD / DEBUG_FULL / DEBUG_INTENT */
    private String channel;
    /** ACTIVE / CLOSED */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
    private LocalDateTime closedAt;
}
