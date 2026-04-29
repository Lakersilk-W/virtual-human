package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单次 LLM 调用成本记录 (W4.D22). 由 CostTrackingChatModel 自动记录.
 *
 * <p>对应表 {@code cost_record}, 详见 V10 migration.
 */
@Data
@TableName("cost_record")
public class CostRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 该次调用所属会话, 异步调度路径若无 conv 上下文可为 null. */
    private Long conversationId;
    private String provider;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    /** 美元, 6 位精度. */
    private BigDecimal costUsd;

    private LocalDateTime createdAt;
}
