package com.vh.runtime.agent;

import com.vh.repository.entity.Conversation;
import com.vh.repository.entity.ToolDef;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.intent.IntentResult;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;

/**
 * Worker 执行上下文. 由 {@link com.vh.runtime.chat.ChatService} 在主流程中装配, 注入给选定的 worker.
 *
 * @param vhId          虚拟人 id
 * @param conversationId 会话 id (已 getOrCreate)
 * @param userMessage   用户原始消息
 * @param config        VH 激活配置 (含人设/模型)
 * @param conversation  会话实体
 * @param memory        ChatMemory 视图 (Redis-backed)
 * @param intent        意图识别结果
 * @param boundTools    该意图绑定的工具实体列表 (按 sort_order 升序), 空走 ChatterWorker
 */
public record WorkerContext(
        Long vhId,
        Long conversationId,
        String userMessage,
        VhActiveConfig config,
        Conversation conversation,
        ChatMemory memory,
        IntentResult intent,
        List<ToolDef> boundTools
) {}
