package com.vh.runtime.model;

import com.vh.config.LlmProperties;
import com.vh.runtime.cost.CostTracker;
import com.vh.runtime.cost.CostTrackingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (provider, modelName) 动态构造 ChatModel 实例并缓存.
 *
 * <h3>设计</h3>
 * - 每个 (provider, modelName) 对应一个长生命周期的 model; 复用底层 HTTP client 连接池
 * - 同时维护 {@link ChatModel} 与 {@link StreamingChatModel} 两套缓存,
 *   非流式用于 ReAct 工具循环 (Day 6), 流式用于交互式 UI (Day 7)
 * - 用 switch 而不是 SPI/Map<String,Builder>, W1 单 provider 不必抽象
 *
 * <h3>DeepSeek</h3>
 * 走 OpenAI 兼容协议, 复用 OpenAiChatModel/OpenAiStreamingChatModel + 自定义 baseUrl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final LlmProperties llmProperties;
    private final CostTracker costTracker;
    private final ConcurrentHashMap<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    // ----- 非流式 -----
    public ChatModel get(String provider, String modelName) {
        String key = provider + ":" + modelName;
        return chatCache.computeIfAbsent(key, k -> {
            log.info("Building ChatModel: provider={} model={} (with cost tracking)", provider, modelName);
            ChatModel raw = buildChat(provider, modelName);
            // W4.D22: 包一层装饰器, 每次 chat() 完自动落 cost_record
            return new CostTrackingChatModel(raw, provider, modelName, costTracker);
        });
    }

    private ChatModel buildChat(String provider, String modelName) {
        return switch (provider.toLowerCase()) {
            case "deepseek" -> buildDeepseekChat(modelName);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    private ChatModel buildDeepseekChat(String modelName) {
        var cfg = ensureDeepseek();
        return OpenAiChatModel.builder()
                .apiKey(cfg.getApiKey())
                .baseUrl(cfg.getBaseUrl())
                .modelName(modelName == null ? cfg.getDefaultModel() : modelName)
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .build();
    }

    // ----- 流式 -----
    public StreamingChatModel getStreaming(String provider, String modelName) {
        String key = provider + ":" + modelName;
        return streamingCache.computeIfAbsent(key, k -> {
            log.info("Building StreamingChatModel: provider={} model={}", provider, modelName);
            return buildStreaming(provider, modelName);
        });
    }

    private StreamingChatModel buildStreaming(String provider, String modelName) {
        return switch (provider.toLowerCase()) {
            case "deepseek" -> buildDeepseekStreaming(modelName);
            default -> throw new IllegalArgumentException("Unsupported streaming provider: " + provider);
        };
    }

    private StreamingChatModel buildDeepseekStreaming(String modelName) {
        var cfg = ensureDeepseek();
        return OpenAiStreamingChatModel.builder()
                .apiKey(cfg.getApiKey())
                .baseUrl(cfg.getBaseUrl())
                .modelName(modelName == null ? cfg.getDefaultModel() : modelName)
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .build();
    }

    private LlmProperties.DeepseekConfig ensureDeepseek() {
        var cfg = llmProperties.getDeepseek();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "DEEPSEEK_API_KEY 未配置. 请检查 .env 是否被 source 到当前 shell.");
        }
        return cfg;
    }
}
