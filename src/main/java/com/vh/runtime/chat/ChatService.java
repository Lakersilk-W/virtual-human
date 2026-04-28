package com.vh.runtime.chat;

import com.vh.repository.entity.Conversation;
import com.vh.repository.entity.ExecutionTrace;
import com.vh.runtime.agent.RouteDecision;
import com.vh.runtime.agent.AgentRouter;
import com.vh.runtime.agent.WorkerContext;
import com.vh.runtime.config.SystemPromptComposer;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.config.VhConfigLoader;
import com.vh.runtime.intent.IntentResult;
import com.vh.runtime.intent.IntentService;
import com.vh.runtime.memory.ChatMemoryFactory;
import com.vh.runtime.model.ChatModelFactory;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import com.vh.runtime.trace.TraceWriter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对话服务. 主入口:
 *
 * <ul>
 *   <li>{@link #rawChat}      诊断: 裸调模型, 无人设/记忆/工具</li>
 *   <li>{@link #chatAs}       业务主入口 (chat.html 默认走这里): 意图 → 路由 → Worker, 全链路 trace</li>
 *   <li>{@link #chatAsStream} 流式备用入口: 单次 LLM, 不接路由, trace 单步落库 (W2 末尾考虑合流)</li>
 * </ul>
 *
 * <h3>chatAs 主流程 (W2.D9 重构后)</h3>
 * <pre>
 * loadConfig + getOrCreateConversation + getMemory
 *   ↓
 * IntentService.classify  (独立模型, T=0)
 *   ↓
 * AgentRouter.route       (按 vh_intent.bound_tool_id 选 Worker)
 *   ↓
 * Worker.handle(ctx)      (各自封装记忆 / 工具调用 / 模型调用)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String DIAG_PROVIDER = "deepseek";
    private static final String DIAG_MODEL = "deepseek-v4-pro";

    private final ChatModelFactory chatModelFactory;
    private final VhConfigLoader vhConfigLoader;
    private final ConversationService conversationService;
    private final ChatMemoryFactory chatMemoryFactory;
    private final IntentService intentService;
    private final AgentRouter agentRouter;
    private final TraceCollector traceCollector;
    private final TraceWriter traceWriter;

    /** 裸调诊断, 不走业务流程. */
    public ChatReply rawChat(String userMessage) {
        ChatModel model = chatModelFactory.get(DIAG_PROVIDER, DIAG_MODEL);
        long start = System.currentTimeMillis();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(userMessage)))
                .build());
        long durationMs = System.currentTimeMillis() - start;
        TokenUsage usage = response.tokenUsage();
        return new ChatReply(null, response.aiMessage().text(), durationMs,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
    }

    /** 业务对话: 意图 → 路由 → Worker. 全链路 trace 落 execution_trace 表. */
    public ChatReply chatAs(Long vhId, Long conversationId, String userMessage) {
        VhActiveConfig config = vhConfigLoader.load(vhId, VhConfigLoader.Channel.DRAFT);
        Conversation conv = conversationService.getOrCreate(
                conversationId, vhId, config.versionId());
        ChatMemory memory = chatMemoryFactory.get(conv.getId());

        traceCollector.start(conv.getId());
        try {
            IntentResult intent = intentService.classify(vhId, userMessage);
            log.info("Intent: convId={} code={} confidence={} fallback={} slots={}",
                    conv.getId(), intent.intentCode(), intent.confidence(),
                    intent.fallback(), intent.slots());

            RouteDecision route = agentRouter.route(intent, config.versionId());
            log.info("Route: convId={} worker={} reason='{}' boundTools={}",
                    conv.getId(), route.worker().name(), route.reason(),
                    route.boundTools().stream().map(t -> t.getCode()).toList());

            WorkerContext ctx = new WorkerContext(
                    vhId, conv.getId(), userMessage,
                    config, conv, memory, intent, route.boundTools());

            return route.worker().handle(ctx);
        } finally {
            traceWriter.persist(traceCollector.drain());
            traceCollector.end();
        }
    }

    /**
     * 流式备用入口. 当前 chat.html 默认走非流式 chatAs, 此方法保留供:
     * (1) 想看流式 token 输出的演示场景
     * (2) W2 末尾合流"流式 + 意图路由"时复用通道
     *
     * <p>不走 Intent → Router, 单次 LLM 调用. 流式回调在异步线程触发, ThreadLocal 的
     * TraceCollector 不可用, 直接构 ExecutionTrace 走 TraceWriter, 单步 LLM_CHAT 落库.
     */
    public void chatAsStream(Long vhId, Long conversationId,
                             String userMessage, StreamingCallback callback) {
        VhActiveConfig config = vhConfigLoader.load(vhId, VhConfigLoader.Channel.DRAFT);
        Conversation conv = conversationService.getOrCreate(
                conversationId, vhId, config.versionId());

        StreamingChatModel model = chatModelFactory.getStreaming(
                config.model().provider(),
                config.model().modelName());
        ChatMemory memory = chatMemoryFactory.get(conv.getId());

        if (memory.messages().isEmpty()) {
            memory.add(SystemMessage.from(SystemPromptComposer.compose(config.systemPrompt())));
        }
        memory.add(UserMessage.from(userMessage));

        long start = System.currentTimeMillis();
        int messageCountAtStart = memory.messages().size();
        model.chat(
                ChatRequest.builder().messages(memory.messages()).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partial) {
                        callback.onChunk(partial);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse complete) {
                        memory.add(complete.aiMessage());
                        conversationService.touch(conv.getId());
                        long durationMs = System.currentTimeMillis() - start;
                        TokenUsage usage = complete.tokenUsage();
                        Integer pTok = usage == null ? null : usage.inputTokenCount();
                        Integer cTok = usage == null ? null : usage.outputTokenCount();
                        log.info("Stream chat done: convId={} vhVersion={} durationMs={} pTok={} cTok={}",
                                conv.getId(), config.versionId(), durationMs, pTok, cTok);

                        persistStreamTrace(conv.getId(), userMessage, complete.aiMessage().text(),
                                pTok, cTok, messageCountAtStart, durationMs, null);

                        callback.onComplete(new ChatReply(
                                conv.getId(), complete.aiMessage().text(),
                                durationMs, pTok, cTok));
                    }

                    @Override
                    public void onError(Throwable error) {
                        long durationMs = System.currentTimeMillis() - start;
                        log.warn("Stream chat failed: convId={}: {}", conv.getId(), error.toString());
                        persistStreamTrace(conv.getId(), userMessage, "",
                                null, null, messageCountAtStart, durationMs, error.toString());
                        callback.onError(error);
                    }
                });
    }

    private void persistStreamTrace(Long convId, String userMessage, String aiText,
                                    Integer pTok, Integer cTok,
                                    int messageCountAtStart, long durationMs, String errorMsg) {
        ExecutionTrace t = new ExecutionTrace();
        t.setConversationId(convId);
        t.setStep(TraceStep.LLM_CHAT.name());
        t.setStepOrder(1);
        t.setInput(Map.of(
                "userMessage", userMessage,
                "messageCount", messageCountAtStart,
                "tools", "(none)",
                "mode", "stream"));
        t.setOutput(Map.of(
                "text", aiText == null ? "" : aiText,
                "promptTokens", pTok == null ? -1 : pTok,
                "completionTokens", cTok == null ? -1 : cTok));
        t.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        t.setErrorMsg(errorMsg);
        t.setCreatedAt(LocalDateTime.now());
        traceWriter.persist(List.of(t));
    }

    public interface StreamingCallback {
        void onChunk(String chunk);
        void onComplete(ChatReply reply);
        void onError(Throwable error);
    }

    public record ChatReply(
            Long conversationId,
            String text,
            long durationMs,
            Integer promptTokens,
            Integer completionTokens
    ) {}
}
