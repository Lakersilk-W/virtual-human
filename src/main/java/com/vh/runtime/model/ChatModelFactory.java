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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (provider, modelName) 动态构造 ChatModel 实例并缓存.
 *
 * <h3>装饰链</h3>
 * 调用 {@link #get(String, String)} 返回的 ChatModel 实际由三层构成:
 * <pre>
 * FallbackChatModel
 *   ├── CostTrackingChatModel(primary)        ← 入参 (provider, model)
 *   ├── CostTrackingChatModel(fallback-1)     ← 来自 vh.llm.fallback.chain
 *   ├── ...
 *   └── EchoChatModel                         ← 末位, 永不抛 (vh.llm.fallback.alwaysEcho)
 * </pre>
 * Cost 由 chain 上每一层各自的装饰器负责: 实际产生 token 的那次调用必然落 cost_record.
 *
 * <h3>缓存</h3>
 * 每个 (provider, modelName) 缓存一个完整 FallbackChatModel; 链内每个 tier 也只构建一次,
 * 复用底层 HTTP client. 流式 model 走另一条独立路径, 不接 fallback (W4 范围).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final LlmProperties llmProperties;
    private final CostTracker costTracker;

    /** key = "provider:modelName"; value = 完整 FallbackChatModel 链 (或 fallback 关闭时的 raw+cost). */
    private final ConcurrentHashMap<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    /** raw chat model (含 CostTracking) 缓存, 给 fallback chain 复用 tier, 避免每个 primary 各起一份. */
    private final ConcurrentHashMap<String, ChatModel> tierCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    /** 单例 echo, 兜底用. */
    private final EchoChatModel echo = new EchoChatModel();

    // ----- 非流式 -----
    public ChatModel get(String provider, String modelName) {
        String key = provider + ":" + modelName;
        return chatCache.computeIfAbsent(key, k -> buildWithFallback(provider, modelName));
    }

    private ChatModel buildWithFallback(String primaryProvider, String primaryModel) {
        ChatModel primary = getOrBuildTier(primaryProvider, primaryModel);

        var fb = llmProperties.getFallback();
        if (fb == null || !fb.isEnabled()) {
            return primary;
        }

        List<FallbackChatModel.Tier> tiers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(primaryProvider + ":" + primaryModel);
        tiers.add(new FallbackChatModel.Tier(primaryProvider + "/" + primaryModel, primary));

        if (fb.getChain() != null) {
            for (var item : fb.getChain()) {
                if (item == null || item.getProvider() == null || item.getModel() == null) continue;
                String dedupKey = item.getProvider() + ":" + item.getModel();
                if (!seen.add(dedupKey)) continue;
                ChatModel m = getOrBuildTier(item.getProvider(), item.getModel());
                tiers.add(new FallbackChatModel.Tier(item.getProvider() + "/" + item.getModel(), m));
            }
        }
        if (fb.isAlwaysEcho()) {
            tiers.add(new FallbackChatModel.Tier("echo", echo));
        }

        if (tiers.size() == 1) return primary;

        log.info("FallbackChatModel built for primary={}/{}: tiers={}",
                primaryProvider, primaryModel,
                tiers.stream().map(FallbackChatModel.Tier::label).toList());
        return new FallbackChatModel(tiers);
    }

    /** 单层 raw + cost wrap (无 fallback). 给链内每个 tier 复用. */
    private ChatModel getOrBuildTier(String provider, String modelName) {
        String key = provider + ":" + modelName;
        return tierCache.computeIfAbsent(key, k -> {
            log.info("Building ChatModel tier: provider={} model={} (with cost tracking)", provider, modelName);
            ChatModel raw = buildChat(provider, modelName);
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

    // ----- 流式 (不接 fallback, W4 范围外) -----
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
