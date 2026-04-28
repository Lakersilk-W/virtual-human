package com.vh.runtime.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 ChatMessage 列表压扁成 trace input 可序列化的 List&lt;Map&gt;.
 *
 * <p>用途: trace UI 上直接看每轮 LLM 调用的完整 prompt, 便于排查
 * "为什么模型这样回" / "记忆里到底带了什么".
 */
public final class MessageDumpUtil {

    private MessageDumpUtil() {}

    public static List<Map<String, Object>> dump(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            out.add(dumpOne(m));
        }
        return out;
    }

    private static Map<String, Object> dumpOne(ChatMessage m) {
        Map<String, Object> r = new HashMap<>();
        r.put("type", m.type().name());
        if (m instanceof SystemMessage sm) {
            r.put("text", sm.text());
        } else if (m instanceof UserMessage um) {
            try {
                r.put("text", um.singleText());
            } catch (Exception e) {
                r.put("text", um.contents().toString());
            }
        } else if (m instanceof AiMessage am) {
            r.put("text", am.text() == null ? "" : am.text());
            if (am.hasToolExecutionRequests()) {
                List<Map<String, Object>> reqs = new ArrayList<>();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    Map<String, Object> reqMap = new HashMap<>();
                    reqMap.put("id", req.id() == null ? "" : req.id());
                    reqMap.put("name", req.name());
                    reqMap.put("arguments", req.arguments());
                    reqs.add(reqMap);
                }
                r.put("toolRequests", reqs);
            }
        } else if (m instanceof ToolExecutionResultMessage trm) {
            r.put("toolName", trm.toolName());
            r.put("toolUseId", trm.id() == null ? "" : trm.id());
            r.put("text", trm.text());
        } else {
            r.put("text", m.toString());
        }
        return r;
    }
}
