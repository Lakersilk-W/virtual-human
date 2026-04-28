package com.vh.runtime.intent;

import java.util.Map;

/**
 * 意图分类结果.
 *
 * @param intentCode  命中的意图 code (匹配 vh_intent.intent_code, 或 fallback)
 * @param confidence  分类器自评信心度 [0, 1]
 * @param slots       从用户话里抽取的结构化槽位 (城市名/时间/数量等)
 * @param fallback    是否走了兜底分支 (JSON 解析失败或低置信度)
 */
public record IntentResult(
        String intentCode,
        double confidence,
        Map<String, Object> slots,
        boolean fallback
) {
    public static IntentResult fallback(String fallbackCode, String reason) {
        return new IntentResult(fallbackCode, 0.0, Map.of("reason", reason), true);
    }
}
