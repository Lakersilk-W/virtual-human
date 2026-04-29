package com.vh.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.MemoryEpisode;
import com.vh.repository.entity.MemoryFact;
import com.vh.repository.mapper.MemoryEpisodeMapper;
import com.vh.repository.mapper.MemoryFactMapper;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长期记忆召回 (W3.D16+).
 *
 * <p>在 Worker 调用 LLM 前查长期记忆, 编排成 SystemMessage 注入到 prompt 前部.
 * 当前覆盖:
 * <ul>
 *   <li><b>Semantic facts</b> (D16-17) — 用户级稳定事实, 跨会话</li>
 *   <li><b>Episodic episodes</b> (D18-20) — 向量召回的相关历史话题, 跨会话</li>
 * </ul>
 *
 * <p>每轮重新查不进 ChatMemory: 这样 facts/episodes 一旦更新, 下一轮就反映,
 * 不用维护缓存一致性.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallService {

    private static final int MAX_FACTS = 30;
    private static final int EPISODE_TOP_K = 3;
    private static final double EPISODE_MIN_CHARS = 4; // 太短的 user message 召回质量差, 直接跳过

    private final MemoryFactMapper memoryFactMapper;
    private final MemoryEpisodeMapper memoryEpisodeMapper;
    private final EmbeddingModel embeddingModel;
    private final MilvusEpisodeStore milvusStore;
    private final TraceCollector traceCollector;

    /**
     * 一次性召回 facts + episodes, 返回 0-2 条 SystemMessage 让 Worker 注入到 persona 之后.
     *
     * @param userId         用户 id
     * @param currentConvId  当前会话, 召回 episodes 时排除自身, 防止"自己召回自己"
     * @param queryText      用作 embedding 的查询文本, 一般是当前 user message
     */
    public List<SystemMessage> recall(Long userId, Long currentConvId, String queryText) {
        long start = System.currentTimeMillis();

        List<SystemMessage> out = new ArrayList<>(2);
        Map<String, Object> traceOut = new HashMap<>();

        // ---- Facts ----
        List<MemoryFact> facts = memoryFactMapper.selectList(
                Wrappers.<MemoryFact>lambdaQuery()
                        .eq(MemoryFact::getUserId, userId)
                        .ge(MemoryFact::getConfidence, 0.5)
                        .orderByDesc(MemoryFact::getLastConfirmedAt)
                        .last("LIMIT " + MAX_FACTS));
        traceOut.put("factCount", facts.size());
        if (!facts.isEmpty()) {
            traceOut.put("factKeys",
                    facts.stream().map(MemoryFact::getFactKey).collect(Collectors.toList()));
            out.add(SystemMessage.from(formatFacts(facts)));
        }

        // ---- Episodes ----
        List<MemoryEpisode> episodes = recallEpisodes(userId, currentConvId, queryText);
        traceOut.put("episodeCount", episodes.size());
        traceOut.put("milvusReady", milvusStore.isReady());
        if (!episodes.isEmpty()) {
            traceOut.put("episodeIds",
                    episodes.stream().map(MemoryEpisode::getId).collect(Collectors.toList()));
            out.add(SystemMessage.from(formatEpisodes(episodes)));
        }

        long durationMs = System.currentTimeMillis() - start;

        Map<String, Object> traceIn = Map.of(
                "userId", userId,
                "currentConvId", currentConvId,
                "queryChars", queryText == null ? 0 : queryText.length(),
                "kinds", List.of("facts", "episodes"));
        traceCollector.record(TraceStep.MEMORY_RECALL, traceIn, traceOut, durationMs, null);

        return out;
    }

    private List<MemoryEpisode> recallEpisodes(Long userId, Long currentConvId, String queryText) {
        if (queryText == null || queryText.length() < EPISODE_MIN_CHARS) return List.of();
        if (!milvusStore.isReady()) return List.of();

        try {
            Embedding embedding = embeddingModel.embed(TextSegment.from(queryText)).content();
            List<Long> milvusIds = milvusStore.search(
                    userId, currentConvId == null ? -1 : currentConvId,
                    embedding.vector(), EPISODE_TOP_K);
            if (milvusIds.isEmpty()) return List.of();

            List<MemoryEpisode> rows = memoryEpisodeMapper.selectList(
                    Wrappers.<MemoryEpisode>lambdaQuery()
                            .in(MemoryEpisode::getMilvusId, milvusIds));
            // 保持 Milvus 返回的相关性顺序
            Map<Long, MemoryEpisode> byId = new HashMap<>();
            for (MemoryEpisode e : rows) byId.put(e.getMilvusId(), e);
            List<MemoryEpisode> ordered = new ArrayList<>();
            for (Long id : milvusIds) {
                MemoryEpisode e = byId.get(id);
                if (e != null) ordered.add(e);
            }
            return ordered;
        } catch (Exception e) {
            log.warn("Episode recall failed userId={} convId={}: {}", userId, currentConvId, e.toString());
            return List.of();
        }
    }

    private String formatFacts(List<MemoryFact> facts) {
        StringBuilder sb = new StringBuilder("[关于用户的事实, 跨会话长期记忆]\n");
        for (MemoryFact f : facts) {
            sb.append("- ").append(f.getFactKey()).append(": ").append(f.getFactValue());
            if (f.getConfidence() != null && f.getConfidence() < 0.8) {
                sb.append(" (置信度 ").append(String.format("%.2f", f.getConfidence())).append(")");
            }
            sb.append('\n');
        }
        sb.append("\n回复时可自然引用这些信息, 不要主动列举或解释来源.");
        return sb.toString();
    }

    private String formatEpisodes(List<MemoryEpisode> episodes) {
        StringBuilder sb = new StringBuilder("[相关历史话题, 来自其他会话]\n");
        for (int i = 0; i < episodes.size(); i++) {
            MemoryEpisode e = episodes.get(i);
            sb.append(i + 1).append(". (").append(e.getOccurredAt()).append(") ");
            sb.append(e.getSummaryText().replace('\n', ' '));
            sb.append('\n');
        }
        sb.append("\n如果与当前话题相关, 可以主动衔接; 不相关时不要硬扯.");
        return sb.toString();
    }
}
