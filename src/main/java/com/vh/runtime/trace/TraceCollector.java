package com.vh.runtime.trace;

import com.vh.repository.entity.ExecutionTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请求级 trace 收集器. ThreadLocal 实现, 由 ChatService 在 chatAs 入口 start, 出口 end.
 *
 * <h3>限制</h3>
 * <ul>
 *   <li>仅适用于同步流程. 跨线程的工作 (如 CompletableFuture.supplyAsync) 不要在子线程里 record;
 *       由主线程 join 后统一 record</li>
 *   <li>嵌套调用不支持 (本场景不需要)</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 每个请求一个 ThreadLocal 实例, 各自隔离; ArrayList 在单线程里追加是安全的.
 */
@Slf4j
@Component
public class TraceCollector {

    private final ThreadLocal<Context> current = new ThreadLocal<>();

    public void start(Long conversationId) {
        if (current.get() != null) {
            log.warn("TraceCollector.start called while another trace is active for convId={}",
                    current.get().conversationId);
        }
        current.set(new Context(conversationId));
    }

    public void record(TraceStep step,
                       Map<String, Object> input,
                       Map<String, Object> output,
                       long durationMs,
                       String errorMsg) {
        Context ctx = current.get();
        if (ctx == null) {
            // 没在 trace 期内调用, 静默忽略
            return;
        }
        ctx.stepOrder++;
        ExecutionTrace t = new ExecutionTrace();
        t.setConversationId(ctx.conversationId);
        t.setStep(step.name());
        t.setStepOrder(ctx.stepOrder);
        t.setInput(input);
        t.setOutput(output);
        t.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        t.setErrorMsg(errorMsg);
        t.setCreatedAt(LocalDateTime.now());
        ctx.traces.add(t);
    }

    public List<ExecutionTrace> drain() {
        Context ctx = current.get();
        return ctx == null ? List.of() : ctx.traces;
    }

    /** 当前请求关联的 conversationId, 没有 trace 上下文时返回 null. CostTracker 等周边组件会用. */
    public Long currentConversationId() {
        Context ctx = current.get();
        return ctx == null ? null : ctx.conversationId;
    }

    public void end() {
        current.remove();
    }

    private static class Context {
        final Long conversationId;
        final List<ExecutionTrace> traces = new ArrayList<>();
        int stepOrder = 0;

        Context(Long convId) { this.conversationId = convId; }
    }
}
