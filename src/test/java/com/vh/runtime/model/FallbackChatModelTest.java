package com.vh.runtime.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W4.D25: FallbackChatModel 装饰器单元测试.
 *
 * <p>不打真实 LLM, 用 lambda mock 三种行为 (fail / succeed / echo) 验证链路.
 */
class FallbackChatModelTest {

    private static final ChatRequest REQ = ChatRequest.builder()
            .messages(List.of(dev.langchain4j.data.message.UserMessage.from("ping")))
            .build();

    private static ChatModel failing(String tag) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) { throw new RuntimeException(tag + " boom"); }
        };
    }

    private static ChatModel succeeding(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }

    @Test
    void primary_success_returns_directly() {
        FallbackChatModel m = new FallbackChatModel(List.of(
                new FallbackChatModel.Tier("primary", succeeding("hello")),
                new FallbackChatModel.Tier("backup",  failing("backup"))
        ));
        assertThat(m.chat(REQ).aiMessage().text()).isEqualTo("hello");
    }

    @Test
    void primary_fails_falls_through_to_backup() {
        FallbackChatModel m = new FallbackChatModel(List.of(
                new FallbackChatModel.Tier("primary", failing("primary")),
                new FallbackChatModel.Tier("backup",  succeeding("from-backup"))
        ));
        assertThat(m.chat(REQ).aiMessage().text()).isEqualTo("from-backup");
    }

    @Test
    void all_tiers_failing_with_echo_at_end_succeeds() {
        FallbackChatModel m = new FallbackChatModel(List.of(
                new FallbackChatModel.Tier("primary", failing("p")),
                new FallbackChatModel.Tier("backup",  failing("b")),
                new FallbackChatModel.Tier("echo",    new EchoChatModel())
        ));
        String text = m.chat(REQ).aiMessage().text();
        assertThat(text).isNotBlank().contains("AI 引擎临时不可用");
    }

    @Test
    void all_tiers_failing_without_echo_rethrows_last() {
        FallbackChatModel m = new FallbackChatModel(List.of(
                new FallbackChatModel.Tier("primary", failing("p")),
                new FallbackChatModel.Tier("backup",  failing("b"))
        ));
        assertThatThrownBy(() -> m.chat(REQ))
                .hasMessageContaining("All 2 fallback tiers failed");
    }

    @Test
    void zero_tiers_rejected() {
        assertThatThrownBy(() -> new FallbackChatModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
