package com.vh.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vh.repository.entity.CostRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface CostRecordMapper extends BaseMapper<CostRecord> {

    /**
     * 列最近 N 个有成本记录的会话, 按总花销倒序. 给 cost UI 顶层用.
     */
    @Select("""
            SELECT conversation_id            AS conversation_id,
                   COUNT(*)                   AS call_count,
                   SUM(prompt_tokens)         AS prompt_tokens,
                   SUM(completion_tokens)     AS completion_tokens,
                   SUM(cost_usd)              AS cost_usd,
                   MAX(created_at)            AS last_call_at
            FROM cost_record
            WHERE conversation_id IS NOT NULL
            GROUP BY conversation_id
            ORDER BY MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> listConversationCosts(@Param("limit") int limit);

    /**
     * 全平台聚合: 总成本/今日成本/按模型分组. 给 cost summary 顶层 banner.
     */
    @Select("""
            SELECT model_name              AS model_name,
                   COUNT(*)                AS call_count,
                   SUM(prompt_tokens)      AS prompt_tokens,
                   SUM(completion_tokens)  AS completion_tokens,
                   SUM(cost_usd)           AS cost_usd
            FROM cost_record
            GROUP BY model_name
            ORDER BY SUM(cost_usd) DESC
            """)
    List<Map<String, Object>> aggregateByModel();
}
