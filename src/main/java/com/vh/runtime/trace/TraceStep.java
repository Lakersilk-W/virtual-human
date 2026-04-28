package com.vh.runtime.trace;

/**
 * trace 步骤类型. 落库时存 enum 名字, 不存 ordinal, 便于增删.
 */
public enum TraceStep {
    /** 意图分类一次 LLM 调用 */
    INTENT_CLASSIFY,
    /** AgentRouter 路由决策 (无 LLM 调用, 通常 <5ms) */
    ROUTE,
    /** 主对话 LLM 调用一轮 */
    LLM_CHAT,
    /** 单次工具执行 */
    TOOL_CALL
}
