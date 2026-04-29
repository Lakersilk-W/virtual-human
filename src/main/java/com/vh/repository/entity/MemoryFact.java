package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级语义事实 (Semantic 层 / W3.D16-17).
 *
 * <p>"关于用户的稳定事实", upsert 用 (user_id, fact_key) 唯一约束.
 * 跨会话召回, 让虚拟人记得"用户养了一只叫橘子的猫"等长期信息.
 *
 * <p>对应表 {@code memory_fact}, 详见 V7 migration.
 */
@Data
@TableName("memory_fact")
public class MemoryFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    /** snake_case key, 如 name / city / occupation / has_pet / pet_name / favorite_book */
    private String factKey;
    private String factValue;
    /** 该事实最后一次被对话提到/确认时所在的会话 (可为空) */
    private Long sourceConvId;
    private Double confidence;

    private LocalDateTime lastConfirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
