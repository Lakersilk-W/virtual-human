package com.vh.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vh.repository.entity.ExecutionTrace;
import com.vh.runtime.trace.ConversationTraceSummary;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExecutionTraceMapper extends BaseMapper<ExecutionTrace> {

    /**
     * 按 conversation 聚合 trace, 给 trace 浏览页左栏使用.
     * 只返回有 trace 的会话, 按最新 trace 时间倒序.
     */
    @Select("""
            SELECT c.id              AS conversation_id,
                   c.vh_id           AS vh_id,
                   c.created_at      AS conv_created_at,
                   COUNT(t.id)       AS step_count,
                   MAX(t.created_at) AS last_trace_at,
                   SUM(CASE WHEN t.step = 'INTENT_CLASSIFY' THEN 1 ELSE 0 END) AS turn_count,
                   COALESCE(SUM(t.duration_ms), 0) AS total_ms
            FROM conversation c
            INNER JOIN execution_trace t ON t.conversation_id = c.id
            GROUP BY c.id, c.vh_id, c.created_at
            ORDER BY MAX(t.created_at) DESC
            LIMIT #{limit}
            """)
    List<ConversationTraceSummary> listConversationsWithTraces(@Param("limit") int limit);
}
