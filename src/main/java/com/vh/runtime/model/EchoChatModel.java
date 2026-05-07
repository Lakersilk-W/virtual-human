package com.vh.runtime.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 永不抛错的兜底 ChatModel (W4.D25). 由 FallbackChatModel 链最末位调用,
 * 真实 provider 全部失败时返回固定文本, 保证用户拿到非空响应.
 *
 * <p>不计 token, 不落 cost (这本来就不是真实 LLM 调用).
 */
@Slf4j
public class EchoChatModel implements ChatModel {

    private static final String FALLBACK_TEXT =
            "（系统提示）AI 引擎临时不可用, 请稍后重试. 如长时间不恢复请联系管理员.";

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        log.warn("EchoChatModel invoked (all upstream chat models exhausted)");
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(FALLBACK_TEXT))
                .build();
    }
}
