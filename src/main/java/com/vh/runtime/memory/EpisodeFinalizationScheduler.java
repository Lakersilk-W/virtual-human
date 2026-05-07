package com.vh.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.Conversation;
import com.vh.repository.entity.MemoryEpisode;
import com.vh.repository.mapper.ConversationMapper;
import com.vh.repository.mapper.MemoryEpisodeMapper;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.config.VhConfigLoader;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceWriter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话空闲兜底索引 (W3 修补).
 *
 * <h3>问题</h3>
 * SummaryService 只在 chat history ≥ 16 条触发 rollup, 且每次 rollup 只压缩前半段;
 * 用户后期才说出的关键内容 (kept tail) 永远没机会成为 episode → 跨会话召回拿不到.
 *
 * <h3>修补</h3>
 * 每 5 分钟扫一遍 ACTIVE 会话, 对于"已空闲 ≥ 5 分钟且没有 FINALIZE 类型 episode"的会话,
 * 把当前 ChatMemory 里的全部非系统消息打包索引为一个 FINALIZE episode.
 *
 * <h3>边界</h3>
 * - 同一会话只 finalize 一次. 用户重新激活后再次空闲不会再次 finalize (用 kind=FINALIZE 的存在判定).
 *   未来如果想"再次激活后又被 finalize", 改成"按时间窗+次数"的策略.
 * - ChatMemory 仍在 Redis 内 (TTL 7d), 所以即使 conv 已经空闲一两天也能取到内容.
 * - 不影响主对话路径; 异步线程, 失败 warn 不抛.
 */
@Slf4j
@Component
@Profile("!eval") // W4.D24: eval 跑批时不要让 scheduler 干扰隔离 conversation
@RequiredArgsConstructor
public class EpisodeFinalizationScheduler {

    private static final long IDLE_MILLIS = 5 * 60_000L;
    private static final int MIN_NON_PREFIX_TO_INDEX = 4;

    private final ConversationMapper conversationMapper;
    private final MemoryEpisodeMapper episodeMapper;
    private final ChatMemoryFactory chatMemoryFactory;
    private final VhConfigLoader vhConfigLoader;
    private final SummaryService summaryService;
    private final EpisodeService episodeService;
    private final TraceCollector traceCollector;
    private final TraceWriter traceWriter;

    @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 60_000L)
    public void finalizeIdleConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(IDLE_MILLIS));

        List<Conversation> idle = conversationMapper.selectList(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, "ACTIVE")
                        .lt(Conversation::getLastActiveAt, cutoff)
                        .orderByAsc(Conversation::getId));

        if (idle.isEmpty()) {
            return;
        }

        int finalized = 0, skipped = 0, failed = 0;
        for (Conversation conv : idle) {
            try {
                if (alreadyFinalized(conv.getId())) {
                    skipped++;
                    continue;
                }
                if (finalizeOne(conv)) finalized++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("Finalize failed convId={}: {}", conv.getId(), e.toString());
            }
        }
        log.info("Finalize sweep: scanned={} finalized={} skipped={} failed={}",
                idle.size(), finalized, skipped, failed);
    }

    private boolean alreadyFinalized(Long convId) {
        Long count = episodeMapper.selectCount(
                Wrappers.<MemoryEpisode>lambdaQuery()
                        .eq(MemoryEpisode::getSourceConvId, convId)
                        .eq(MemoryEpisode::getKind, EpisodeService.KIND_FINALIZE));
        return count != null && count > 0;
    }

    private boolean finalizeOne(Conversation conv) {
        ChatMemory mem = chatMemoryFactory.get(conv.getId());
        List<ChatMessage> all = mem.messages();

        // 跳过 system 前缀, 取真实 chat history
        int prefixEnd = 0;
        while (prefixEnd < all.size() && all.get(prefixEnd) instanceof SystemMessage) prefixEnd++;
        List<ChatMessage> rest = new ArrayList<>(all.subList(prefixEnd, all.size()));

        if (rest.size() < MIN_NON_PREFIX_TO_INDEX) {
            // 太短的会话不值得 index
            return false;
        }

        traceCollector.start(conv.getId());
        try {
            VhActiveConfig config = vhConfigLoader.load(conv.getVhId(), VhConfigLoader.Channel.DRAFT);

            // 用既有 prev summary 作上下文 (如果有过 rollup)
            String prevSummary = "";
            for (int i = 0; i < prefixEnd; i++) {
                if (all.get(i) instanceof SystemMessage sm
                        && sm.text().startsWith("[过往对话摘要] ")) {
                    prevSummary = sm.text().substring("[过往对话摘要] ".length());
                    break;
                }
            }

            String summary = summaryService.summarizeText(prevSummary, rest, config);
            String rawText = summaryService.renderRawText(rest);

            episodeService.index(conv.getUserId(), conv.getId(),
                    rawText, summary, rest.size(), EpisodeService.KIND_FINALIZE);
            return true;
        } finally {
            traceWriter.persist(traceCollector.drain());
            traceCollector.end();
        }
    }
}
