package com.vh.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.ConversationSummary;
import com.vh.repository.mapper.ConversationSummaryMapper;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.model.ChatModelFactory;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话滚动摘要 (Summary 层) — W3.D15.
 *
 * <h3>触发</h3>
 * Worker 在 AI 回复入 memory 后调用 {@link #maybeRollup}. 当"非系统前缀消息"
 * 数量 ≥ {@link #TRIGGER_NON_PREFIX} (16 条 ≈ 8 轮) 时, 把较老的一段压缩成摘要,
 * 内存中替换为单条 {@code [过往对话摘要 vN]} SystemMessage, 同时 upsert
 * conversation_summary 表.
 *
 * <h3>边界</h3>
 * - 第一次 rollup: memory = [persona, user, ai, user, ai, ...] →
 *   [persona, summary, user, ai, ...keep last 8...]
 * - 后续 rollup: memory = [persona, oldSummary, user, ai, ...] →
 *   [persona, newSummary (融合 oldSummary), user, ai, ...keep last 8...]
 *
 * <h3>同步 vs 异步</h3>
 * 当前在主线程同步执行 (rollup 概率 ~7 轮一次). 后续若发现延迟显著, 可改为
 * {@code @Async} + 自定义线程池, 并加 conversation 级锁防并发触发.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    /** 非 system 前缀消息数 (= chat history) 达到此阈值时触发 rollup. */
    private static final int TRIGGER_NON_PREFIX = 16;
    /** rollup 时保留最末几条原文不压缩, 让模型仍有近期上下文细节. */
    private static final int KEEP_LAST = 8;

    private static final String SUMMARY_PREFIX = "[过往对话摘要] ";

    private final ChatModelFactory chatModelFactory;
    private final ConversationSummaryMapper summaryMapper;
    private final EpisodeService episodeService;
    private final com.vh.repository.mapper.ConversationMapper conversationMapper;
    private final TraceCollector traceCollector;

    public void maybeRollup(Long conversationId, ChatMemory mem, VhActiveConfig config) {
        List<ChatMessage> all = mem.messages();

        int prefixEnd = 0;
        while (prefixEnd < all.size() && all.get(prefixEnd) instanceof SystemMessage) {
            prefixEnd++;
        }
        List<ChatMessage> prefix = new ArrayList<>(all.subList(0, prefixEnd));
        List<ChatMessage> rest = all.subList(prefixEnd, all.size());

        if (rest.size() < TRIGGER_NON_PREFIX) {
            return;
        }

        int splitAt = rest.size() - KEEP_LAST;
        List<ChatMessage> toCompress = new ArrayList<>(rest.subList(0, splitAt));
        List<ChatMessage> toKeep = new ArrayList<>(rest.subList(splitAt, rest.size()));

        // 抽取 prev summary: prefix 中第二个及之后的 SystemMessage 拼接 (一般只有一个)
        String prevSummary = "";
        for (int i = 1; i < prefix.size(); i++) {
            String t = ((SystemMessage) prefix.get(i)).text();
            if (t.startsWith(SUMMARY_PREFIX)) {
                prevSummary = t.substring(SUMMARY_PREFIX.length());
                break;
            }
        }

        long start = System.currentTimeMillis();
        String newSummary;
        try {
            newSummary = generateSummary(prevSummary, toCompress, config);
        } catch (Exception e) {
            log.warn("Summary rollup failed for convId={}: {}. Skip.", conversationId, e.toString());
            traceCollector.record(TraceStep.SUMMARY_WRITE,
                    Map.of("compressed", toCompress.size(), "kept", toKeep.size()),
                    Map.of(),
                    System.currentTimeMillis() - start, e.toString());
            return;
        }
        long durationMs = System.currentTimeMillis() - start;

        // 重建 memory: persona(原 prefix[0]) + new summary system + last K
        SystemMessage persona = (SystemMessage) prefix.get(0);
        SystemMessage summaryMsg = SystemMessage.from(SUMMARY_PREFIX + newSummary);

        mem.clear();
        mem.add(persona);
        mem.add(summaryMsg);
        for (ChatMessage m : toKeep) {
            mem.add(m);
        }

        int newVersion = upsertSummary(conversationId, newSummary, toCompress.size());

        traceCollector.record(TraceStep.SUMMARY_WRITE,
                Map.of("compressed", toCompress.size(),
                        "kept", toKeep.size(),
                        "prevSummaryChars", prevSummary.length()),
                Map.of("version", newVersion,
                        "summary", newSummary,
                        "summaryChars", newSummary.length()),
                durationMs, null);

        log.info("Summary rollup convId={} v={} compressed={} kept={} durationMs={} chars={}",
                conversationId, newVersion, toCompress.size(), toKeep.size(),
                durationMs, newSummary.length());

        // W3.D18-20: 同步索引为 episode (Milvus + memory_episode)
        var conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            episodeService.index(conv.getUserId(), conversationId,
                    buildRawText(toCompress), newSummary, toCompress.size(),
                    EpisodeService.KIND_ROLLUP);
        }
    }

    private String buildRawText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            String role = m instanceof UserMessage ? "用户"
                    : m instanceof AiMessage ? "助手"
                    : m instanceof ToolExecutionResultMessage ? "工具"
                    : "系统";
            String text = textOf(m);
            if (text == null || text.isBlank()) continue;
            sb.append(role).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    /**
     * 公开版本: 给 finalize/scheduler 复用 LLM 生成摘要逻辑, 不做 trace/落库/重写 memory.
     */
    public String summarizeText(String prevSummary, List<ChatMessage> messages, VhActiveConfig config) {
        return generateSummary(prevSummary, messages, config);
    }

    /** Finalize 路径用: 把消息列表拼成 raw text, 给 episode raw_text 字段. */
    public String renderRawText(List<ChatMessage> messages) {
        return buildRawText(messages);
    }

    private String generateSummary(String prevSummary, List<ChatMessage> messages, VhActiveConfig config) {
        ChatModel model = chatModelFactory.get(
                config.model().provider(), config.model().modelName());

        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : messages) {
            String role = m instanceof UserMessage ? "用户"
                    : m instanceof AiMessage ? "助手"
                    : m instanceof ToolExecutionResultMessage ? "工具"
                    : "系统";
            String text = textOf(m);
            if (text == null || text.isBlank()) continue;
            transcript.append(role).append(": ").append(text).append('\n');
        }

        String prompt = """
                你的任务是把对话历史压缩成一段紧凑摘要, 让后续对话仍能延续上下文.

                要求:
                1. 中文, 整段不超过 300 字
                2. 用要点列表 (每点一行, 以 - 起头)
                3. 第三人称视角 ("用户提到...", "助手告诉用户...")
                4. **重点是用户的事实/经历**: 用户透露的姓名/职业/喜好/家庭/宠物/状态/事件,
                   以及已确认的约定. 助手的回应仅用于理解上下文, 一般不要单独成点.
                5. **严禁把助手即兴说的内容当成事实写入摘要**. 例如助手回答"我也养猫, 叫芝麻"
                   或"我刚路过猫咖看到橘猫", 这些是助手的会话回应, 不是真实事实, 摘要里**不要提**.
                6. 工具调用结果 (如天气查询) 是客观数据, 可以保留但要标明 "查询了/工具返回".
                7. 如下方有 [已有摘要], 把新对话的事实融入进去, 不要丢失早期事实.
                8. 不要包含寒暄/客套话/纯情绪表达 (如"今天好累"); 但用户的具体近况 (如"猫不爱吃饭",
                   "在准备面试") 要保留.

                [已有摘要]
                %s

                [新对话片段]
                %s

                直接输出新摘要, 不要任何前缀/解释/markdown 代码块.
                """.formatted(
                        prevSummary.isBlank() ? "(无)" : prevSummary,
                        transcript.toString());

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());
        String text = response.aiMessage().text();
        return text == null ? "" : text.trim();
    }

    private String textOf(ChatMessage m) {
        if (m instanceof SystemMessage sm) return sm.text();
        if (m instanceof UserMessage um) {
            try { return um.singleText(); }
            catch (Exception e) { return um.contents().toString(); }
        }
        if (m instanceof AiMessage am) return am.text();
        if (m instanceof ToolExecutionResultMessage trm) return trm.toolName() + " → " + trm.text();
        return m.toString();
    }

    /** upsert 单行 conversation_summary, 返回新 version. */
    private int upsertSummary(Long convId, String summary, int compressedCount) {
        ConversationSummary existing = summaryMapper.selectOne(
                Wrappers.<ConversationSummary>lambdaQuery()
                        .eq(ConversationSummary::getConversationId, convId));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ConversationSummary row = new ConversationSummary();
            row.setConversationId(convId);
            row.setSummaryText(summary);
            row.setCoversMessageCount(compressedCount);
            row.setVersion(1);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            summaryMapper.insert(row);
            return 1;
        } else {
            existing.setSummaryText(summary);
            existing.setCoversMessageCount(
                    (existing.getCoversMessageCount() == null ? 0 : existing.getCoversMessageCount())
                            + compressedCount);
            existing.setVersion(existing.getVersion() + 1);
            existing.setUpdatedAt(now);
            summaryMapper.updateById(existing);
            return existing.getVersion();
        }
    }
}
