package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 情景记忆 (Episodic 层 / W3.D18-20).
 *
 * <p>每次 SummaryService rollup 时把"被压缩的那段消息"同步落成一个 episode:
 * <ul>
 *   <li>{@code raw_text}     原始消息拼接, 给召回后展示</li>
 *   <li>{@code summary_text} 这段的 LLM 摘要, 用来生成 embedding</li>
 *   <li>{@code milvus_id}    embedding 在 Milvus 的主键, 反查用</li>
 * </ul>
 *
 * <p>对应表 {@code memory_episode}, 详见 V8 migration.
 */
@Data
@TableName("memory_episode")
public class MemoryEpisode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long sourceConvId;
    private String summaryText;
    private String rawText;
    private Integer msgCount;
    /** ROLLUP — 滚动摘要触发时索引压缩段; FINALIZE — 空闲超时索引整段含 tail (兜底, W3 修补) */
    private String kind;
    private Long milvusId;

    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
