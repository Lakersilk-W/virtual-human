package com.vh.runtime.memory;

import com.vh.repository.entity.MemoryEpisode;
import com.vh.repository.mapper.MemoryEpisodeMapper;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 情景记忆索引服务 (Episodic 层 / W3.D18-20).
 *
 * <p>由 SummaryService 在 rollup 时调用: 把"被压缩的那段消息"作为一个 episode
 * 落 MySQL 元数据 + Milvus 向量.
 *
 * <h3>embedding 内容选择</h3>
 * 取 summary_text 而非 raw_text 做 embedding. 理由:
 * 1) summary 更紧凑, 噪音少, 语义更密集
 * 2) BGE-small-zh-v15 输入有长度上限 (~512 token), 短文本表现更稳
 * 3) 召回展示给 LLM 时优先 summary, 用户消息也是短的, 长度匹配
 *
 * <p>raw_text 仍保留入库, 用于面试展示/调试和未来扩展 (例如重新 reembed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final MemoryEpisodeMapper episodeMapper;
    private final EmbeddingModel embeddingModel;
    private final MilvusEpisodeStore milvusStore;
    private final TraceCollector traceCollector;

    public static final String KIND_ROLLUP = "ROLLUP";
    public static final String KIND_FINALIZE = "FINALIZE";

    public void index(Long userId, Long sourceConvId,
                      String rawText, String summary, int msgCount, String kind) {
        if (userId == null || sourceConvId == null) {
            log.warn("Episode index skipped: userId={} convId={}", userId, sourceConvId);
            return;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("Episode index skipped: empty summary for convId={}", sourceConvId);
            return;
        }

        long start = System.currentTimeMillis();
        Long milvusId = null;
        Long episodeId = null;
        String error = null;
        int dim = 0;

        try {
            Embedding embedding = embeddingModel.embed(TextSegment.from(summary)).content();
            dim = embedding.vector().length;

            milvusId = milvusStore.insert(userId, sourceConvId, embedding.vector());

            LocalDateTime now = LocalDateTime.now();
            MemoryEpisode row = new MemoryEpisode();
            row.setUserId(userId);
            row.setSourceConvId(sourceConvId);
            row.setSummaryText(summary);
            row.setRawText(rawText == null ? "" : rawText);
            row.setMsgCount(msgCount);
            row.setKind(kind);
            row.setMilvusId(milvusId);
            row.setOccurredAt(now);
            row.setCreatedAt(now);
            episodeMapper.insert(row);
            episodeId = row.getId();
        } catch (Exception e) {
            error = e.toString();
            log.warn("Episode index failed convId={}: {}", sourceConvId, error);
        }

        long durationMs = System.currentTimeMillis() - start;

        traceCollector.record(TraceStep.EPISODE_INDEX,
                Map.of("userId", userId,
                        "sourceConvId", sourceConvId,
                        "msgCount", msgCount,
                        "kind", kind,
                        "summaryChars", summary.length(),
                        "rawChars", rawText == null ? 0 : rawText.length()),
                Map.of("episodeId", episodeId == null ? -1 : episodeId,
                        "milvusId", milvusId == null ? -1 : milvusId,
                        "embeddingDim", dim,
                        "milvusReady", milvusStore.isReady()),
                durationMs, error);

        if (error == null) {
            log.info("Indexed episode id={} kind={} milvusId={} userId={} convId={} dim={} durationMs={}",
                    episodeId, kind, milvusId, userId, sourceConvId, dim, durationMs);
        }
    }
}
