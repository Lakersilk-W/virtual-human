package com.vh.runtime.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 给定 conversationId 取一个 ChatMemory 视图.
 *
 * 实例本身轻量, 每次调用都新建; 状态都在 {@link RedisChatMemoryStore} 里.
 *
 * <h3>窗口策略</h3>
 * - 默认 20 条 (含 system + user/ai 来回)
 * - W3 会引入 SummaryMemory: 当窗口溢出时, 旧消息异步压缩成一段摘要塞回 system
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryFactory {

    private static final int DEFAULT_WINDOW = 20;

    private final RedisChatMemoryStore store;

    public ChatMemory get(Long conversationId) {
        return get(conversationId, DEFAULT_WINDOW);
    }

    public ChatMemory get(Long conversationId, int maxMessages) {
        return MessageWindowChatMemory.builder()
                .id(conversationId)
                .chatMemoryStore(store)
                .maxMessages(maxMessages)
                .build();
    }
}
