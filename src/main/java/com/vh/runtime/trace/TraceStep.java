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
    TOOL_CALL,
    /** 滚动摘要触发: 较老的若干条消息被压缩成一段, 写入 conversation_summary 并替换内存 (W3.D15) */
    SUMMARY_WRITE,
    /** Worker 主流程前置: 查长期记忆 (facts/episodes) 注入 SystemMessage (W3.D16+) */
    MEMORY_RECALL,
    /** 用户事实抽取: 每轮 AI 回复后异步 LLM 调用, 把对话里冒出的用户事实抽出来 upsert (W3.D16-17) */
    FACT_EXTRACT,
    /** 情景索引: rollup 时把被压缩的对话片段 embed + 写 Milvus + 落 memory_episode (W3.D18-20) */
    EPISODE_INDEX
}
