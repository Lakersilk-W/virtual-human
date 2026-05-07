package com.vh.runtime.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 ChatModel 顺序 fallback (W4.D25). 第一个抛错时退到下一个, 全部失败重抛最后一次异常.
 *
 * <h3>装饰顺序</h3>
 * 由 ChatModelFactory 包装, 链上每一项都已经被 CostTrackingChatModel 包过, 所以
 * 实际产生 token 的那次调用会自动落 cost_record (fallback 切换不会让 cost 漏记).
 *
 * <pre>
 * FallbackChatModel
 *   ├── CostTrackingChatModel(deepseek-chat)        ← primary
 *   ├── CostTrackingChatModel(deepseek-reasoner)    ← fallback 1
 *   └── EchoChatModel                                ← echo 兜底, 永不抛
 * </pre>
 *
 * <h3>不做的事</h3>
 * 不做健康度统计 / 熔断 / 退避. 1 个月 MVP 范围, 只做"失败即切换".
 * 后续若引入 resilience4j 才考虑.
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

    private final List<Tier> tiers;

    public FallbackChatModel(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("FallbackChatModel needs at least 1 tier");
        }
        this.tiers = List.copyOf(tiers);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        List<String> attempted = new ArrayList<>(tiers.size());
        Throwable last = null;
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            attempted.add(t.label());
            try {
                ChatResponse r = t.model().chat(chatRequest);
                if (i > 0) {
                    log.warn("Fallback hit: succeeded on tier #{}/{} ({}), prior tiers: {}",
                            i + 1, tiers.size(), t.label(), attempted.subList(0, i));
                }
                return r;
            } catch (Throwable e) {
                last = e;
                log.warn("Tier #{}/{} ({}) failed: {} -> trying next",
                        i + 1, tiers.size(), t.label(), e.toString());
            }
        }
        // 末位 EchoChatModel 永不抛, 所以走到这里只可能是 fallback.alwaysEcho=false 关掉了 echo
        throw new RuntimeException(
                "All " + tiers.size() + " fallback tiers failed. Attempted: " + attempted, last);
    }

    /** 一个 tier = 一个候选 ChatModel + 用于日志的可读 label (如 "deepseek/deepseek-chat"). */
    public record Tier(String label, ChatModel model) {}
}
