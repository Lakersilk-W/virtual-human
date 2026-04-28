package com.vh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 模型配置, 对应 application.yml 中 vh.llm.*
 * 当前只接 DeepSeek; 后续接 Claude / OpenAI / Qwen 时, 在此追加内嵌类即可.
 */
@Data
@ConfigurationProperties(prefix = "vh.llm")
public class LlmProperties {

    private DeepseekConfig deepseek = new DeepseekConfig();

    @Data
    public static class DeepseekConfig {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com/v1";
        private String defaultModel = "deepseek-chat";
        private Double temperature = 0.7;
        private Integer timeoutSeconds = 60;
    }
}
