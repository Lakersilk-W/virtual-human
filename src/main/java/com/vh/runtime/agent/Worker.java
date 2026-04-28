package com.vh.runtime.agent;

import com.vh.runtime.chat.ChatService;

/**
 * Agent Worker 抽象: 接到一个 {@link WorkerContext}, 完成一次对话回合, 返回 reply.
 *
 * <p>当前实现:
 * <ul>
 *   <li>{@link ChatterWorker} — 纯人设对话, 不带工具 (闲聊/书/音乐)</li>
 *   <li>{@link ToolWorker}    — ReAct 循环, 只看到意图绑定的那一个工具 (天气)</li>
 * </ul>
 *
 * <p>W3 起可加 {@code SummaryWorker} (摘要触发) / {@code RagWorker} (向量召回回答) 等.
 */
public interface Worker {
    /** Worker 名字, 用于 trace 日志. */
    String name();

    ChatService.ChatReply handle(WorkerContext ctx);
}
