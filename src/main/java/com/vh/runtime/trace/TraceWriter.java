package com.vh.runtime.trace;

import com.vh.repository.entity.ExecutionTrace;
import com.vh.repository.mapper.ExecutionTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 批量落 trace. 每次对话结束统一插入, 不在每步同步写, 避免热路径上多余的 IO.
 *
 * <p>异常以 WARN 吞掉而不是抛出: trace 是辅助观测能力, 不能因为它挂掉而影响业务对话.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceWriter {

    private final ExecutionTraceMapper executionTraceMapper;

    @Transactional
    public void persist(List<ExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) return;
        try {
            for (ExecutionTrace t : traces) {
                executionTraceMapper.insert(t);
            }
            log.debug("Persisted {} trace rows for convId={}", traces.size(),
                    traces.get(0).getConversationId());
        } catch (Exception e) {
            log.warn("Failed to persist {} trace rows: {}", traces.size(), e.toString());
        }
    }
}
