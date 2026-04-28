package com.vh.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.ToolDef;
import com.vh.repository.entity.VhIntent;
import com.vh.repository.mapper.VhIntentMapper;
import com.vh.repository.mapper.VhIntentToolMapper;
import com.vh.runtime.intent.IntentResult;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按意图分类结果选 Worker.
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>查 vh_intent (vh_version_id + intent_code) 取得意图定义</li>
 *   <li>意图找不到 / 没绑工具 → ChatterWorker</li>
 *   <li>所有绑定工具都 disable → ChatterWorker (软降级)</li>
 *   <li>否则 → ToolWorker (注入按 sort_order 排序的 active 工具列表)</li>
 * </ol>
 *
 * <p>多工具支持: 同一意图可绑多个工具 (V5 起 vh_intent_tool 多对多).
 * ToolWorker 把全部 spec 注册给模型, 单轮内由模型决定是否 fan-out 并行调用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final VhIntentMapper vhIntentMapper;
    private final VhIntentToolMapper vhIntentToolMapper;
    private final ChatterWorker chatterWorker;
    private final ToolWorker toolWorker;
    private final TraceCollector traceCollector;

    public RouteDecision route(IntentResult intent, Long vhVersionId) {
        long start = System.currentTimeMillis();
        RouteDecision decision = doRoute(intent, vhVersionId);

        traceCollector.record(TraceStep.ROUTE,
                Map.of("intentCode", intent.intentCode(),
                        "vhVersionId", vhVersionId),
                Map.of("worker", decision.worker().name(),
                        "boundToolCodes",
                        decision.boundTools().stream().map(ToolDef::getCode).collect(Collectors.toList()),
                        "reason", decision.reason()),
                System.currentTimeMillis() - start, null);

        return decision;
    }

    private RouteDecision doRoute(IntentResult intent, Long vhVersionId) {
        VhIntent intentDef = vhIntentMapper.selectOne(
                Wrappers.<VhIntent>lambdaQuery()
                        .eq(VhIntent::getVhVersionId, vhVersionId)
                        .eq(VhIntent::getIntentCode, intent.intentCode()));

        if (intentDef == null) {
            return new RouteDecision(chatterWorker, List.of(),
                    "intent code '" + intent.intentCode() + "' not in vh_intent");
        }

        List<ToolDef> tools = vhIntentToolMapper.listActiveToolsByIntentId(intentDef.getId());
        if (tools.isEmpty()) {
            return new RouteDecision(chatterWorker, List.of(),
                    "intent has no active bound tool");
        }

        return new RouteDecision(toolWorker, tools,
                "intent bound to " + tools.size() + " tool(s): "
                        + tools.stream().map(ToolDef::getCode).collect(Collectors.joining(",")));
    }
}
