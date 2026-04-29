package com.vh.runtime.agent;

import com.vh.runtime.chat.ChatService;
import com.vh.runtime.chat.ConversationService;
import com.vh.runtime.config.SystemPromptComposer;
import com.vh.runtime.memory.MemoryRecallService;
import com.vh.runtime.model.ChatModelFactory;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 闲聊 Worker — 纯人设对话, 不带工具列表.
 *
 * <p>适用意图: {@code chitchat}, {@code book_recommendation}, {@code music_share}
 * 等不需要外部数据的意图.
 *
 * <p>单次 LLM 调用, 无 ReAct 循环.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatterWorker implements Worker {

    private final ChatModelFactory chatModelFactory;
    private final ConversationService conversationService;
    private final MemoryRecallService memoryRecallService;
    private final TraceCollector traceCollector;

    @Override
    public String name() {
        return "chatter";
    }

    @Override
    public ChatService.ChatReply handle(WorkerContext ctx) {
        ChatMemory mem = ctx.memory();
        if (mem.messages().isEmpty()) {
            mem.add(SystemMessage.from(SystemPromptComposer.compose(ctx.config().systemPrompt())));
        }
        mem.add(UserMessage.from(ctx.userMessage()));

        ChatModel model = chatModelFactory.get(
                ctx.config().model().provider(),
                ctx.config().model().modelName());

        // 召回长期记忆 (facts + episodes), 注入到 persona 之后, 不入 ChatMemory
        List<SystemMessage> recalled = memoryRecallService.recall(
                ctx.conversation().getUserId(), ctx.conversationId(), ctx.userMessage());
        List<ChatMessage> effective = composeEffective(mem.messages(), recalled);
        var messagesSnapshot = MessageDumpUtil.dump(effective);

        long start = System.currentTimeMillis();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(effective)
                .build());
        long durationMs = System.currentTimeMillis() - start;

        mem.add(response.aiMessage());
        conversationService.touch(ctx.conversationId());

        TokenUsage usage = response.tokenUsage();
        Integer pTok = usage == null ? null : usage.inputTokenCount();
        Integer cTok = usage == null ? null : usage.outputTokenCount();

        String text = response.aiMessage().text();
        traceCollector.record(TraceStep.LLM_CHAT,
                Map.of("messageCount", messagesSnapshot.size(),
                        "tools", "(none)",
                        "messages", messagesSnapshot),
                Map.of("text", text == null ? "" : text,
                        "promptTokens", pTok == null ? -1 : pTok,
                        "completionTokens", cTok == null ? -1 : cTok),
                durationMs, null);

        log.info("ChatterWorker done: convId={} intent={} durationMs={} pTok={} cTok={}",
                ctx.conversationId(), ctx.intent().intentCode(),
                durationMs, pTok, cTok);

        return new ChatService.ChatReply(
                ctx.conversationId(),
                response.aiMessage().text(),
                durationMs, pTok, cTok);
    }

    /**
     * 把召回的 SystemMessages 插到 persona 之后, 不进 ChatMemory (每轮重新召回保证新鲜).
     * 顺序: persona, recalled[facts, episodes](W3.D16+), [过往对话摘要](W3.D15)?, history.
     */
    static List<ChatMessage> composeEffective(List<ChatMessage> base, List<SystemMessage> recalled) {
        if (recalled == null || recalled.isEmpty() || base.isEmpty()) return base;
        int insertAt = 1;  // persona 之后
        List<ChatMessage> out = new ArrayList<>(base.size() + recalled.size());
        out.addAll(base.subList(0, Math.min(insertAt, base.size())));
        out.addAll(recalled);
        if (base.size() > insertAt) {
            out.addAll(base.subList(insertAt, base.size()));
        }
        return out;
    }
}
