package com.vh.runtime.config;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 把人设 prompt 和运行时环境信息拼成最终 SystemMessage 文本.
 *
 * <p>背景: 人设 prompt 来自 vh_persona_prompt 表, 是半静态的;
 * 但 LLM 不知道当前日期, 在没显式告知时会按训练数据分布乱猜
 * (典型 bug: 春天问"今天天气", 模型回"临近秋天"). 这里在每次会话首轮拼装系统提示时
 * 注入"今天是 yyyy-MM-dd 周X", 解决这个问题.
 *
 * <p>注: 仅在每个会话首轮 SystemMessage 入库时调用一次, 后续轮次复用缓存的 SystemMessage.
 * 因此对于跨天延续的会话, 系统提示里的日期会停留在首轮当天 (STM TTL=7d, 实际偏差最多 7 天).
 * 进一步精确化可改为每轮 user message 之前注入临时 SystemMessage, W3 视需要再做.
 */
public final class SystemPromptComposer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private SystemPromptComposer() {}

    public static String compose(String basePrompt) {
        return compose(basePrompt, LocalDate.now());
    }

    /** 显式传入日期版本, 便于单测. */
    public static String compose(String basePrompt, LocalDate today) {
        String dow = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);
        return (basePrompt == null ? "" : basePrompt)
                + "\n\n【运行时环境】今天是 " + today.format(DATE_FMT) + " " + dow
                + "。涉及今天/最近/季节等时间相关的回答请以此为准, 不要凭训练数据猜测当前时间。";
    }
}
