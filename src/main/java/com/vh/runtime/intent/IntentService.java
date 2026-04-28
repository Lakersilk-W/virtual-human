package com.vh.runtime.intent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vh.repository.entity.VhIntent;
import com.vh.repository.entity.VhIntentAgent;
import com.vh.repository.entity.VirtualHuman;
import com.vh.repository.mapper.VhIntentAgentMapper;
import com.vh.repository.mapper.VhIntentMapper;
import com.vh.repository.mapper.VirtualHumanMapper;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 意图分类服务. 独立的 LLM 调用, 不带主对话的人设, 用结构化输出.
 *
 * <h3>调用形态</h3>
 * <pre>
 * IntentService.classify(vhId, "上海现在天气怎样")
 *  → {intentCode: "weather_query", confidence: 0.95, slots: {"city": "上海"}}
 * </pre>
 *
 * <h3>设计</h3>
 * - 用 vh_intent_agent 配置的独立模型 (T=0 求稳定); 与主模型解耦
 * - 意图列表来自 vh_intent (含 few-shot examples)
 * - 解析失败 / 模型胡言 → 兜底 fallback_intent_code
 * - W2.D8 仅独立调用; W2.D9 起会被 ChatService 在主流程里调用做路由
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final VirtualHumanMapper virtualHumanMapper;
    private final VhIntentAgentMapper vhIntentAgentMapper;
    private final VhIntentMapper vhIntentMapper;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final TraceCollector traceCollector;

    private static final String DEFAULT_CLASSIFIER_PROMPT = """
            You are an intent classifier for a chatbot. Read the user message and classify it
            into ONE of the intents below.

            Available intents:
            %s

            Rules:
            1. Output JSON only. No markdown fences, no explanation, no other text.
            2. If no intent confidently matches (score < 0.5), use the fallback intent: "%s".
            3. "confidence" is a float in [0, 1] reflecting your certainty.
            4. Extract any obviously useful structured data into "slots"
               (e.g. city name for weather queries, book title for recommendations).
               If no obvious slot, return an empty object.

            Output schema (exact field names, no extras):
            {
              "intentCode": "<one of the intent codes>",
              "confidence": <float 0..1>,
              "slots": { <string, string|number> }
            }

            User message: "%s"
            """;

    public IntentResult classify(Long vhId, String userMessage) {
        VirtualHuman vh = virtualHumanMapper.selectById(vhId);
        if (vh == null) {
            throw new IllegalArgumentException("Virtual human not found: " + vhId);
        }
        Long versionId = vh.getDraftVersionId();
        if (versionId == null) {
            throw new IllegalStateException("VH " + vhId + " has no draft version");
        }

        VhIntentAgent agentCfg = vhIntentAgentMapper.selectOne(
                Wrappers.<VhIntentAgent>lambdaQuery()
                        .eq(VhIntentAgent::getVhVersionId, versionId));
        if (agentCfg == null) {
            throw new IllegalStateException("Missing intent agent config for version " + versionId);
        }

        List<VhIntent> intents = vhIntentMapper.selectList(
                Wrappers.<VhIntent>lambdaQuery()
                        .eq(VhIntent::getVhVersionId, versionId)
                        .orderByAsc(VhIntent::getSortOrder));
        if (intents.isEmpty()) {
            return IntentResult.fallback(agentCfg.getFallbackIntentCode(), "no intents defined");
        }

        String prompt = buildPrompt(agentCfg, intents, userMessage);

        ChatModel model = chatModelFactory.get(agentCfg.getProvider(), agentCfg.getModelName());
        long start = System.currentTimeMillis();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());
        long durationMs = System.currentTimeMillis() - start;

        String aiText = response.aiMessage().text();
        log.info("Intent classifier for vhId={} durationMs={} raw={}", vhId, durationMs,
                aiText == null ? "null" : aiText.replace("\n", " "));

        IntentResult result = parseResult(aiText, agentCfg.getFallbackIntentCode(), intents);

        traceCollector.record(TraceStep.INTENT_CLASSIFY,
                java.util.Map.of("userMessage", userMessage),
                java.util.Map.of(
                        "intentCode", result.intentCode(),
                        "confidence", result.confidence(),
                        "slots", result.slots(),
                        "fallback", result.fallback(),
                        "raw", aiText == null ? "" : aiText),
                durationMs, null);

        return result;
    }

    private String buildPrompt(VhIntentAgent agentCfg, List<VhIntent> intents, String userMsg) {
        StringBuilder intentsList = new StringBuilder();
        for (VhIntent it : intents) {
            intentsList.append("- ").append(it.getIntentCode()).append(": ")
                    .append(it.getDescription() == null ? "" : it.getDescription());
            if (it.getExamples() != null && !it.getExamples().isEmpty()) {
                intentsList.append("\n  Examples: ").append(it.getExamples());
            }
            intentsList.append("\n");
        }

        String template = agentCfg.getClassifierPrompt() != null
                          ? agentCfg.getClassifierPrompt()
                          : DEFAULT_CLASSIFIER_PROMPT;
        return template.formatted(intentsList.toString(),
                agentCfg.getFallbackIntentCode(), userMsg);
    }

    private IntentResult parseResult(String text, String fallbackCode, List<VhIntent> intents) {
        if (text == null || text.isBlank()) {
            return IntentResult.fallback(fallbackCode, "empty response");
        }
        try {
            String json = stripCodeFences(text);
            JsonNode node = objectMapper.readTree(json);

            String code = node.path("intentCode").asText("");
            double conf = node.path("confidence").asDouble(0.5);
            Map<String, Object> slots = parseSlots(node.path("slots"));

            // 校验 code 必须在已知意图列表里, 否则落 fallback
            boolean known = intents.stream().anyMatch(i -> i.getIntentCode().equals(code));
            if (!known) {
                log.warn("Classifier returned unknown intentCode='{}', falling back", code);
                return IntentResult.fallback(fallbackCode, "unknown intent: " + code);
            }
            // 低置信度也兜底, 阈值后续可调
            if (conf < 0.5) {
                log.info("Low confidence {} for '{}', falling back", conf, code);
                return new IntentResult(fallbackCode, conf, slots, true);
            }
            return new IntentResult(code, conf, slots, false);
        } catch (Exception e) {
            log.warn("Intent JSON parse failed: {} (text={})", e.getMessage(), text);
            return IntentResult.fallback(fallbackCode, "parse error: " + e.getMessage());
        }
    }

    private Map<String, Object> parseSlots(JsonNode slotsNode) {
        if (slotsNode == null || slotsNode.isMissingNode() || slotsNode.isNull()) {
            return Map.of();
        }
        Map<String, Object> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = slotsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isTextual())       out.put(e.getKey(), v.asText());
            else if (v.isNumber())   out.put(e.getKey(), v.numberValue());
            else if (v.isBoolean())  out.put(e.getKey(), v.asBoolean());
            else                     out.put(e.getKey(), v.toString());
        }
        return out;
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
