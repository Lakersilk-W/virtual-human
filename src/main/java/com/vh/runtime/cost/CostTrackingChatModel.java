package com.vh.runtime.cost;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ChatModel 装饰器: 每次 chat() 完成后通过 {@link CostTracker} 自动落 cost_record.
 *
 * <p>由 ChatModelFactory 在 {@code get(provider, model)} 返回时包一层. 对调用方透明,
 * 业务代码 (IntentService / Worker / FactExtractor / Summary 等) 完全不感知 cost 逻辑存在.
 *
 * <p>仅覆盖 {@link ChatModel#chat(ChatRequest)} (项目里所有调用都走这个). 其他便捷方法
 * 由接口默认实现转发到这里, 因此一并被覆盖.
 *
 * <p>失败/无 token 信息时静默不记 (LLM 失败时 token usage 通常为 null).
 */
@Slf4j
@RequiredArgsConstructor
public class CostTrackingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String provider;
    private final String modelName;
    private final CostTracker costTracker;

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        ChatResponse response = delegate.chat(chatRequest);
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            costTracker.record(provider, modelName,
                    usage.inputTokenCount(), usage.outputTokenCount());
        }
        return response;
    }
}
