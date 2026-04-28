package com.vh.runtime.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 Redis 持久化 LangChain4j 的会话消息 (短期工作记忆 / STM).
 *
 * <h3>Key 设计</h3>
 * {@code vh:mem:<conversationId>} → JSON 序列化后的消息列表
 *
 * <h3>TTL</h3>
 * 7 天. 长期记忆 (W3) 会从消息流中提取语义事实和 episodic chunks 落到 Milvus,
 * 因此 STM 可以放心过期.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "vh:mem:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(key(memoryId));
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.warn("Failed to deserialize chat memory for {}, treating as empty: {}",
                    memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redis.opsForValue().set(key(memoryId), json, TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
