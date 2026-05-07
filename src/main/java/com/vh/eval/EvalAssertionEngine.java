package com.vh.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.ExecutionTrace;
import com.vh.repository.mapper.ExecutionTraceMapper;
import com.vh.runtime.trace.TraceStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单轮 evidence 收集 + 断言比对 (W4.D24).
 *
 * <p>只看 {@code trace_id > minTraceId} 的 trace 行, 这样可以精准定位 case target 这一轮
 * 的所有 step, 不会被同 conversation 上的前置 setup turn 干扰.
 */
@Service
@RequiredArgsConstructor
public class EvalAssertionEngine {

    private final ExecutionTraceMapper executionTraceMapper;

    /**
     * 从 trace 表读 evidence: case 的 target 那一轮一共产生哪些 step / 调了哪些工具 /
     * 召回了什么 facts/episodes / 抽出了哪些新 facts.
     */
    public Evidence collectEvidence(Long convId, long minTraceId) {
        List<ExecutionTrace> traces = executionTraceMapper.selectList(
                Wrappers.<ExecutionTrace>lambdaQuery()
                        .eq(ExecutionTrace::getConversationId, convId)
                        .gt(ExecutionTrace::getId, minTraceId)
                        .orderByAsc(ExecutionTrace::getId));

        String intentCode = null;
        Set<String> toolsCalled = new LinkedHashSet<>();
        int maxParallelGroupSize = 0;
        Set<String> extractedFactKeys = new LinkedHashSet<>();
        Set<String> recalledFactKeys = new LinkedHashSet<>();
        int recallEpisodeCount = 0;

        for (ExecutionTrace t : traces) {
            String step = t.getStep();
            Map<String, Object> in = t.getInput() == null ? Map.of() : t.getInput();
            Map<String, Object> out = t.getOutput() == null ? Map.of() : t.getOutput();

            if (TraceStep.INTENT_CLASSIFY.name().equals(step)) {
                intentCode = strOrNull(out.get("intentCode"));
            } else if (TraceStep.TOOL_CALL.name().equals(step)) {
                String name = strOrNull(in.get("name"));
                if (name != null) toolsCalled.add(name);
                Object groupSize = in.get("parallelGroupSize");
                if (groupSize instanceof Number n) {
                    maxParallelGroupSize = Math.max(maxParallelGroupSize, n.intValue());
                }
            } else if (TraceStep.MEMORY_RECALL.name().equals(step)) {
                Object factKeys = out.get("factKeys");
                if (factKeys instanceof List<?> list) {
                    for (Object k : list) {
                        String s = strOrNull(k);
                        if (s != null) recalledFactKeys.add(s);
                    }
                }
                Object epCount = out.get("episodeCount");
                if (epCount instanceof Number n) {
                    recallEpisodeCount = Math.max(recallEpisodeCount, n.intValue());
                }
            } else if (TraceStep.FACT_EXTRACT.name().equals(step)) {
                Object upserted = out.get("upserted");
                if (upserted instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            String key = strOrNull(m.get("key"));
                            if (key != null) extractedFactKeys.add(key);
                        }
                    }
                }
            }
        }

        return new Evidence(intentCode, List.copyOf(toolsCalled), maxParallelGroupSize,
                List.copyOf(extractedFactKeys), List.copyOf(recalledFactKeys), recallEpisodeCount);
    }

    /**
     * 比对 expect 与 evidence + reply 文本, 返回失败原因列表 (空 = 全部通过).
     */
    public List<String> assertExpect(EvalCase.EvalExpect expect, Evidence ev, String reply) {
        List<String> failures = new ArrayList<>();
        if (expect == null) return failures;

        if (expect.getIntentCode() != null
                && !Objects.equals(expect.getIntentCode(), ev.intentCode())) {
            failures.add(String.format("intentCode expected=%s actual=%s",
                    expect.getIntentCode(), ev.intentCode()));
        }

        if (expect.getToolsCalled() != null) {
            // 空列表 = 断言"没有任何工具调用"
            if (expect.getToolsCalled().isEmpty()) {
                if (!ev.toolsCalled().isEmpty()) {
                    failures.add("toolsCalled expected=[] actual=" + ev.toolsCalled());
                }
            } else {
                for (String t : expect.getToolsCalled()) {
                    if (!ev.toolsCalled().contains(t)) {
                        failures.add("toolsCalled missing '" + t + "', actual=" + ev.toolsCalled());
                    }
                }
            }
        }

        if (expect.getParallelGroupSizeMin() != null
                && ev.maxParallelGroupSize() < expect.getParallelGroupSizeMin()) {
            failures.add(String.format("parallelGroupSize expected>=%d actual=%d",
                    expect.getParallelGroupSizeMin(), ev.maxParallelGroupSize()));
        }

        if (expect.getExtractedFactKeyContains() != null) {
            for (String needle : expect.getExtractedFactKeyContains()) {
                boolean hit = ev.extractedFactKeys().stream()
                        .anyMatch(k -> k.toLowerCase().contains(needle.toLowerCase()));
                if (!hit) {
                    failures.add("extractedFactKey missing substring '" + needle
                            + "', actual=" + ev.extractedFactKeys());
                }
            }
        }

        if (expect.getRecallFactKeyContains() != null) {
            for (String needle : expect.getRecallFactKeyContains()) {
                boolean hit = ev.recalledFactKeys().stream()
                        .anyMatch(k -> k.toLowerCase().contains(needle.toLowerCase()));
                if (!hit) {
                    failures.add("recallFactKey missing substring '" + needle
                            + "', actual=" + ev.recalledFactKeys());
                }
            }
        }

        if (expect.getRecallEpisodeCountMin() != null
                && ev.recallEpisodeCount() < expect.getRecallEpisodeCountMin()) {
            failures.add(String.format("recallEpisodeCount expected>=%d actual=%d",
                    expect.getRecallEpisodeCountMin(), ev.recallEpisodeCount()));
        }

        String safeReply = reply == null ? "" : reply;
        if (expect.getReplyContainsAny() != null && !expect.getReplyContainsAny().isEmpty()) {
            boolean any = expect.getReplyContainsAny().stream().anyMatch(safeReply::contains);
            if (!any) {
                failures.add("replyContainsAny none matched, expected=" + expect.getReplyContainsAny()
                        + ", reply=" + truncate(safeReply, 120));
            }
        }
        if (expect.getReplyNotContains() != null) {
            for (String s : expect.getReplyNotContains()) {
                if (safeReply.contains(s)) {
                    failures.add("replyNotContains hit '" + s + "', reply=" + truncate(safeReply, 120));
                }
            }
        }

        return failures;
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public record Evidence(
            String intentCode,
            List<String> toolsCalled,
            int maxParallelGroupSize,
            List<String> extractedFactKeys,
            List<String> recalledFactKeys,
            int recallEpisodeCount
    ) {}
}
