package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级滚动摘要. 每个 conversation 最多 1 行, version 随每次 rollup 自增.
 *
 * <p>对应表 {@code conversation_summary}, 详见 V6 migration.
 */
@Data
@TableName("conversation_summary")
public class ConversationSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String summaryText;
    /** 该会话累计被压缩过的原始消息条数 (含历次 rollup). */
    private Integer coversMessageCount;
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
