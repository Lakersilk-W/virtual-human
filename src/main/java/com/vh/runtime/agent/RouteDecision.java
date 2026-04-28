package com.vh.runtime.agent;

import com.vh.repository.entity.ToolDef;

import java.util.List;

/**
 * AgentRouter 的决策结果.
 *
 * @param worker      选定的 worker (chatter / tool-worker)
 * @param boundTools  ToolWorker 需要的工具实体列表 (按 sort_order 升序), 空表示走 ChatterWorker
 * @param reason      选这个 worker 的原因 (用于日志/trace, 比如 "intent has no bound tool")
 */
public record RouteDecision(
        Worker worker,
        List<ToolDef> boundTools,
        String reason
) {}
