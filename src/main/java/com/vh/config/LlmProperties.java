package com.vh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 模型配置, 对应 application.yml 中 vh.llm.*
 * 当前只接 DeepSeek; 后续接 Claude / OpenAI / Qwen 时, 在此追加内嵌类即可.
 */
@Data
@ConfigurationProperties(prefix = "vh.llm")
public class LlmProperties {

    private DeepseekConfig deepseek = new DeepseekConfig();
    private FallbackConfig fallback = new FallbackConfig();

    @Data
    public static class DeepseekConfig {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com/v1";
        private String defaultModel = "deepseek-chat";
        private Double temperature = 0.7;
        private Integer timeoutSeconds = 60;
    }

    /**
     * Fallback chain 配置 (W4.D25): 主 model 抛错时按顺序退到下一个.
     *
     * <p>每次 ChatModelFactory.get(provider, model) 返回的 ChatModel 实际是一个
     * FallbackChatModel, primary 是入参 (provider, model) 包过 cost tracking 的 model,
     * fallbacks 来自 {@link #chain} (跳过与 primary 相同的项), 末尾按 {@link #alwaysEcho}
     * 决定是否再追加一个永不抛的 EchoChatModel 兜底.
     *
     * <p>接 Qwen / 智谱 / Claude 时只需在 chain 里加一项 (Qwen/智谱走 OpenAI 兼容协议
     * 复用 deepseek 路径; Claude 需要补 anthropic SDK 分支).
     */
    @Data
    public static class FallbackConfig {
        /** false: 直返 primary, 不包 FallbackChatModel; 调试主链路时可关. */
        private boolean enabled = true;
        /** 主失败时按顺序尝试的备选 (provider/model). 与 primary 相同的项会被跳过. */
        private List<ChainItem> chain = new ArrayList<>();
        /** chain 末尾自动追加 EchoChatModel, 保证永不抛. */
        private boolean alwaysEcho = true;
    }

    @Data
    public static class ChainItem {
        private String provider;
        private String model;
    }
}
