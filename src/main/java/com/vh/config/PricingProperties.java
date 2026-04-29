package com.vh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 模型定价 (W4.D22). 配置位于 application.yml 的 vh.pricing.{model_name}.
 *
 * <p>统一以美元为单位, 单价是"每百万 tokens 多少美元". 计算时:
 * <pre>cost = (pTok * input + cTok * output) / 1_000_000</pre>
 *
 * <p>未知模型的调用 cost = 0 (会被 CostTracker warn 日志提示, 但不挡主流程).
 */
@Data
@ConfigurationProperties(prefix = "vh")
public class PricingProperties {

    /** key 是 model_name (如 deepseek-chat); value 是该模型的输入/输出单价. */
    private Map<String, ModelPricing> pricing = new HashMap<>();

    @Data
    public static class ModelPricing {
        private BigDecimal inputPerMillionUsd = BigDecimal.ZERO;
        private BigDecimal outputPerMillionUsd = BigDecimal.ZERO;
    }
}
