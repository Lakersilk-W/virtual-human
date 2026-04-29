package com.vh.runtime.cost;

import com.vh.config.PricingProperties;
import com.vh.repository.entity.CostRecord;
import com.vh.repository.mapper.CostRecordMapper;
import com.vh.runtime.trace.TraceCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * LLM 调用成本登记 (W4.D22).
 *
 * <p>调用方: {@link CostTrackingChatModel} 在每次 chat() 完成后调用 {@link #record}.
 * conversationId 从 {@link TraceCollector#currentConversationId()} 取 (ThreadLocal,
 * 主流程 ChatService.chatAs / 异步 EpisodeFinalizationScheduler 都已设置过).
 *
 * <h3>价格来源</h3>
 * application.yml 的 vh.pricing.{model_name}. 未配置的模型 cost=0 (一次性 warn).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostTracker {

    private static final BigDecimal MILLION = new BigDecimal(1_000_000);

    private final CostRecordMapper costRecordMapper;
    private final PricingProperties pricingProperties;
    private final TraceCollector traceCollector;

    /** 记录已 warn 过的 model, 避免日志刷屏. */
    private final Set<String> unknownModelsLogged = new HashSet<>();

    public void record(String provider, String modelName,
                       Integer promptTokens, Integer completionTokens) {
        if (promptTokens == null) promptTokens = 0;
        if (completionTokens == null) completionTokens = 0;
        if (promptTokens == 0 && completionTokens == 0) return;

        BigDecimal cost = computeCost(modelName, promptTokens, completionTokens);

        try {
            CostRecord row = new CostRecord();
            row.setConversationId(traceCollector.currentConversationId());
            row.setProvider(provider);
            row.setModelName(modelName);
            row.setPromptTokens(promptTokens);
            row.setCompletionTokens(completionTokens);
            row.setCostUsd(cost);
            row.setCreatedAt(LocalDateTime.now());
            costRecordMapper.insert(row);
        } catch (Exception e) {
            // 落库失败不影响主对话, warn 即可
            log.warn("CostRecord persist failed: provider={} model={} pTok={} cTok={}: {}",
                    provider, modelName, promptTokens, completionTokens, e.toString());
        }
    }

    private BigDecimal computeCost(String modelName, int pTok, int cTok) {
        var pricing = pricingProperties.getPricing().get(modelName);
        if (pricing == null) {
            warnOnceUnknownModel(modelName);
            return BigDecimal.ZERO;
        }
        BigDecimal input = pricing.getInputPerMillionUsd()
                .multiply(BigDecimal.valueOf(pTok))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal output = pricing.getOutputPerMillionUsd()
                .multiply(BigDecimal.valueOf(cTok))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    private void warnOnceUnknownModel(String modelName) {
        if (unknownModelsLogged.add(modelName)) {
            log.warn("Pricing not configured for model='{}', cost will be recorded as 0. " +
                    "Add vh.pricing.{} to application.yml to enable cost tracking.",
                    modelName, modelName);
        }
    }
}
