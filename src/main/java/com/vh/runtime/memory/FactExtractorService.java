package com.vh.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vh.repository.entity.MemoryFact;
import com.vh.repository.mapper.MemoryFactMapper;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.model.ChatModelFactory;
import com.vh.runtime.trace.TraceCollector;
import com.vh.runtime.trace.TraceStep;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户事实抽取 (Semantic 层 / W3.D16-17).
 *
 * <p>每轮 AI 回复后调用一次, 用 LLM 从 (user 输入 + ai 回复) 抽出**关于用户**的稳定事实,
 * upsert 到 {@code memory_fact}. 已有事实会一起喂给 LLM 做去重判断.
 *
 * <h3>过滤规则</h3>
 * 提示里要求 LLM 只输出:
 * <ul>
 *   <li>关于用户本人的事实, 不抽世界知识</li>
 *   <li>稳定的属性/偏好/约定, 不抽一时情绪 ("user is tired today" → skip)</li>
 *   <li>置信度 ≥ 0.7 的</li>
 * </ul>
 *
 * <p>低置信度抽取用 0.7 兜底, 写入时实际 confidence 取 LLM 自评.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactExtractorService {

    private static final String EXTRACT_PROMPT = """
            从下面的一轮对话中抽取关于"用户本人"的事实, 输出 JSON 数组.

            **数据源约束** (非常重要):
            - 只看 "[用户]" 那一行说的内容, 把那当成事实来源
            - **绝对不要**从 "[助手]" 那一行抽事实. 助手的话只是 AI 在配合聊天 (可能是即兴编的,
              比如"我也养猫", "我刚路过猫咖"), 不是真实事实. 助手说什么都不能落库.

            **抽取范围**:
            1. 稳定属性: 姓名 / 职业 / 城市 / 家庭 / 宠物 / 长期喜好 / 关系 / 纪念日 / 生日
            2. 用户的具体近况和状态 (即使非永久): 比如 "猫不爱吃饭" → pet_health_issue,
               "在准备面试" → current_focus, "刚搬家" → recent_event. 这类近况也是有价值的事实.
            3. 跳过纯情绪 ("用户今天累了" / "心情不好") 和反问

            **格式**:
            - fact_key 用 snake_case 英文 (name / city / occupation / has_pet / pet_name /
              pet_type / pet_health_issue / current_focus / favorite_book 等)
            - fact_value 用简洁中文短语
            - confidence 是浮点 0-1, 仅输出 ≥ 0.7
            - 已知 key 只在值变化或被用户重新确认时输出 (覆盖)
            - 没有可抽事实就返回 []

            [已知事实]
            %s

            [新对话]
            [用户]: %s
            [助手]: %s

            输出 JSON 数组, 每项格式 {"fact_key": "...", "fact_value": "...", "confidence": 0.x}.
            不要任何 markdown / 解释, 只输出 JSON.
            """;

    private final MemoryFactMapper memoryFactMapper;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final TraceCollector traceCollector;

    public void extract(Long userId, Long conversationId,
                        String userMessage, String aiResponse,
                        VhActiveConfig config) {
        long start = System.currentTimeMillis();

        // 已知事实拼到 prompt, 让模型避免重复输出
        List<MemoryFact> existing = memoryFactMapper.selectList(
                Wrappers.<MemoryFact>lambdaQuery()
                        .eq(MemoryFact::getUserId, userId)
                        .orderByAsc(MemoryFact::getFactKey));

        StringBuilder existingStr = new StringBuilder();
        if (existing.isEmpty()) {
            existingStr.append("(无)");
        } else {
            for (MemoryFact f : existing) {
                existingStr.append("- ").append(f.getFactKey()).append(": ").append(f.getFactValue()).append('\n');
            }
        }

        String prompt = EXTRACT_PROMPT.formatted(
                existingStr.toString(),
                userMessage == null ? "" : userMessage,
                aiResponse == null ? "" : aiResponse);

        ChatModel model = chatModelFactory.get(
                config.model().provider(), config.model().modelName());

        String raw;
        List<Map<String, Object>> upserted = new ArrayList<>();
        String error = null;

        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build());
            raw = response.aiMessage().text();

            List<MemoryFact> parsed = parseFacts(raw, userId, conversationId);
            for (MemoryFact f : parsed) {
                Map<String, Object> result = upsertFact(f);
                upserted.add(result);
            }
        } catch (Exception e) {
            error = e.toString();
            raw = "";
            log.warn("FactExtractor failed for convId={} userId={}: {}", conversationId, userId, error);
        }

        long durationMs = System.currentTimeMillis() - start;

        traceCollector.record(TraceStep.FACT_EXTRACT,
                Map.of("userId", userId,
                        "userMessage", userMessage == null ? "" : userMessage,
                        "existingCount", existing.size()),
                Map.of("rawOutput", raw == null ? "" : raw,
                        "upserted", upserted,
                        "newCount", upserted.size()),
                durationMs, error);

        if (!upserted.isEmpty()) {
            log.info("FactExtractor convId={} userId={} upserted={} durationMs={}",
                    conversationId, userId, upserted.size(), durationMs);
        }
    }

    private List<MemoryFact> parseFacts(String raw, Long userId, Long conversationId) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = stripCodeFences(raw.trim());
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return List.of();

            List<MemoryFact> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String key = node.path("fact_key").asText("").trim();
                String value = node.path("fact_value").asText("").trim();
                double conf = node.path("confidence").asDouble(0.0);
                if (key.isBlank() || value.isBlank()) continue;
                if (conf < 0.7) continue;

                MemoryFact f = new MemoryFact();
                f.setUserId(userId);
                f.setFactKey(key);
                f.setFactValue(value);
                f.setSourceConvId(conversationId);
                f.setConfidence(conf);
                out.add(f);
            }
            return out;
        } catch (Exception e) {
            log.warn("Fact JSON parse failed: {} (raw={})", e.getMessage(), raw);
            return List.of();
        }
    }

    private Map<String, Object> upsertFact(MemoryFact f) {
        LocalDateTime now = LocalDateTime.now();
        MemoryFact existing = memoryFactMapper.selectOne(
                Wrappers.<MemoryFact>lambdaQuery()
                        .eq(MemoryFact::getUserId, f.getUserId())
                        .eq(MemoryFact::getFactKey, f.getFactKey()));

        Map<String, Object> result = new HashMap<>();
        result.put("key", f.getFactKey());
        result.put("value", f.getFactValue());
        result.put("confidence", f.getConfidence());

        if (existing == null) {
            f.setLastConfirmedAt(now);
            f.setCreatedAt(now);
            f.setUpdatedAt(now);
            memoryFactMapper.insert(f);
            result.put("op", "insert");
        } else {
            String oldValue = existing.getFactValue();
            existing.setFactValue(f.getFactValue());
            existing.setConfidence(Math.max(
                    existing.getConfidence() == null ? 0 : existing.getConfidence(),
                    f.getConfidence()));
            existing.setSourceConvId(f.getSourceConvId());
            existing.setLastConfirmedAt(now);
            existing.setUpdatedAt(now);
            memoryFactMapper.updateById(existing);
            result.put("op", oldValue.equals(f.getFactValue()) ? "confirm" : "update");
            result.put("oldValue", oldValue);
        }
        return result;
    }

    private String stripCodeFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.lastIndexOf("```"));
        }
        return t.trim();
    }
}
