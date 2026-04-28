package com.vh.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.ExecutionTrace;
import com.vh.repository.mapper.ExecutionTraceMapper;
import com.vh.runtime.trace.ConversationTraceSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trace 查询接口. 给 traces.html 浏览页提供数据.
 *
 * <ul>
 *   <li>GET /api/traces/conversations         — 列出有 trace 的会话, 按最新一条 trace 倒序</li>
 *   <li>GET /api/traces/conversations/{id}    — 取该会话所有 trace 步骤, 按 step_order 升序</li>
 * </ul>
 *
 * <p>UI 侧按 INTENT_CLASSIFY 出现位置切分"轮", 后端只返回 flat list.
 */
@RestController
@RequestMapping("/api/traces")
@RequiredArgsConstructor
public class TraceController {

    private static final int MAX_LIMIT = 200;

    private final ExecutionTraceMapper executionTraceMapper;

    @GetMapping("/conversations")
    public List<ConversationTraceSummary> listConversations(
            @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return executionTraceMapper.listConversationsWithTraces(safeLimit);
    }

    @GetMapping("/conversations/{conversationId}")
    public List<ExecutionTrace> getSteps(@PathVariable Long conversationId) {
        return executionTraceMapper.selectList(
                Wrappers.<ExecutionTrace>lambdaQuery()
                        .eq(ExecutionTrace::getConversationId, conversationId)
                        .orderByAsc(ExecutionTrace::getStepOrder));
    }
}
