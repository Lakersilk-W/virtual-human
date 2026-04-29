package com.vh.runtime.agent;

import com.vh.repository.entity.ToolDef;
import com.vh.runtime.chat.ChatService;
import com.vh.runtime.chat.ConversationService;
import com.vh.runtime.config.SystemPromptComposer;
import com.vh.runtime.memory.MemoryRecallService;
import com.vh.runtime.model.ChatModelFactory;
import com.vh.runtime.tool.BuiltinToolRegistry;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 工具 Worker — ReAct 循环, 把意图绑定的全部 active 工具的 ToolSpecification 都注册给模型.
 *
 * <h3>多工具与并行</h3>
 * 模型在单轮 LLM_CHAT 中可以请求多个工具 (provider/model 支持并行 tool calls 时),
 * 我们用 {@link CompletableFuture#supplyAsync} 真并行执行, 总耗时 ≈ max(各 tool wall-clock)
 * 而非 sum. 不支持并行的模型回退成顺序请求, 不影响正确性.
 *
 * <h3>vs Day 6 「全部工具丢给模型」</h3>
 * 区别在: 路由层先把"该意图能用哪些工具"裁剪好 (vh_intent_tool 多对多),
 * 模型只在白名单内决定用哪些 + 参数, 不再自由组合所有工具. 更可控、更可观测.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolWorker implements Worker {

    /** 单个意图内的工具往返上限, 防失控. */
    private static final int MAX_ITERATIONS = 4;

    private final ChatModelFactory chatModelFactory;
    private final BuiltinToolRegistry toolRegistry;
    private final ConversationService conversationService;
    private final MemoryRecallService memoryRecallService;
    private final TraceCollector traceCollector;

    @Override
    public String name() {
        return "tool-worker";
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

        // 解析意图绑定的全部工具 -> spec 列表 (空表已由 AgentRouter 拦截走 Chatter, 这里只过滤未注册的)
        List<ToolSpecification> specs = new ArrayList<>();
        List<String> dbCodes = new ArrayList<>();
        for (ToolDef td : ctx.boundTools()) {
            ToolSpecification s = toolRegistry.getSpecByDbCode(td.getCode());
            if (s == null) {
                log.warn("Bound tool '{}' not registered in BuiltinToolRegistry; skipped", td.getCode());
                continue;
            }
            specs.add(s);
            dbCodes.add(td.getCode());
        }
        String toolsLabel = specs.isEmpty()
                ? "(none)"
                : specs.stream().map(ToolSpecification::name).collect(Collectors.joining(","));

        // 召回长期记忆 (facts + episodes), 整个 ReAct 循环复用 (轮内不变)
        List<SystemMessage> recalled = memoryRecallService.recall(
                ctx.conversation().getUserId(), ctx.conversationId(), ctx.userMessage());

        long totalMs = 0;
        int totalPTok = 0, totalCTok = 0, toolCalls = 0, iter = 0;
        ChatResponse response = null;

        while (iter < MAX_ITERATIONS) {
            iter++;
            List<ChatMessage> effective = ChatterWorker.composeEffective(mem.messages(), recalled);
            var messagesSnapshot = MessageDumpUtil.dump(effective);

            long start = System.currentTimeMillis();
            response = model.chat(ChatRequest.builder()
                    .messages(effective)
                    .toolSpecifications(specs)
                    .build());
            long thisRoundMs = System.currentTimeMillis() - start;
            totalMs += thisRoundMs;

            TokenUsage usage = response.tokenUsage();
            Integer pTok = usage == null ? null : usage.inputTokenCount();
            Integer cTok = usage == null ? null : usage.outputTokenCount();
            if (pTok != null) totalPTok += pTok;
            if (cTok != null) totalCTok += cTok;

            AiMessage ai = response.aiMessage();
            mem.add(ai);

            traceCollector.record(TraceStep.LLM_CHAT,
                    Map.of("iter", iter,
                            "messageCount", messagesSnapshot.size(),
                            "tools", toolsLabel,
                            "messages", messagesSnapshot),
                    Map.of("text", ai.text() == null ? "" : ai.text(),
                            "toolRequests", ai.hasToolExecutionRequests() ? ai.toolExecutionRequests().size() : 0,
                            "promptTokens", pTok == null ? -1 : pTok,
                            "completionTokens", cTok == null ? -1 : cTok),
                    thisRoundMs, null);

            if (!ai.hasToolExecutionRequests()) {
                break;
            }

            List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
            toolCalls += requests.size();

            record TimedResult(String result, long durationMs) {}
            List<CompletableFuture<TimedResult>> futures = requests.stream()
                    .map(req -> CompletableFuture.supplyAsync(() -> {
                        long t0 = System.currentTimeMillis();
                        // 按模型回吐的 LC4j 工具名 (= Java 方法名) 找执行器, 而非 DB code
                        ToolExecutor executor = toolRegistry.getExecutor(req.name());
                        String r = (executor != null)
                                ? safeExecute(executor, req, ctx.conversationId())
                                : "Tool not registered: " + req.name();
                        return new TimedResult(r, System.currentTimeMillis() - t0);
                    }))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 按请求原序: 写回 memory + record trace (主线程做, ThreadLocal 安全)
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest req = requests.get(i);
                TimedResult tr = futures.get(i).join();
                mem.add(ToolExecutionResultMessage.from(req, tr.result()));
                traceCollector.record(TraceStep.TOOL_CALL,
                        Map.of("name", req.name(),
                                "args", req.arguments(),
                                "iter", iter,
                                "parallelGroupSize", requests.size()),
                        Map.of("result", tr.result()),
                        tr.durationMs(), null);
            }
        }

        conversationService.touch(ctx.conversationId());

        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            text = "[tool loop exhausted at " + iter + " iterations]";
        }

        log.info("ToolWorker done: convId={} intent={} dbTools={} totalMs={} iter={} toolCalls={} pTok={} cTok={}",
                ctx.conversationId(), ctx.intent().intentCode(), dbCodes,
                totalMs, iter, toolCalls, totalPTok, totalCTok);

        return new ChatService.ChatReply(
                ctx.conversationId(), text, totalMs, totalPTok, totalCTok);
    }

    private String safeExecute(ToolExecutor executor, ToolExecutionRequest req, Long convId) {
        try {
            String result = executor.execute(req, convId);
            String preview = result.length() > 100 ? result.substring(0, 100) + "..." : result;
            log.info("Tool exec convId={} name={} args={} result='{}'",
                    convId, req.name(), req.arguments(), preview);
            return result;
        } catch (Exception e) {
            log.warn("Tool exec failed name={}: {}", req.name(), e.toString());
            return "Tool error: " + e.getMessage();
        }
    }
}
