package com.vh.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vh.repository.entity.ToolDef;
import com.vh.repository.entity.VhIntentTool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VhIntentToolMapper extends BaseMapper<VhIntentTool> {

    /**
     * 取意图绑定的所有 active 工具, 按 sort_order 升序.
     * 仅返回 tool.status = 1 (启用) 的, disable 的软降级到 ChatterWorker 由调用方判断.
     */
    @Select("""
            SELECT t.*
            FROM vh_intent_tool it
            JOIN tool t ON t.id = it.tool_id
            WHERE it.intent_id = #{intentId}
              AND (t.status IS NULL OR t.status = 1)
            ORDER BY it.sort_order ASC, it.id ASC
            """)
    List<ToolDef> listActiveToolsByIntentId(@Param("intentId") Long intentId);
}
