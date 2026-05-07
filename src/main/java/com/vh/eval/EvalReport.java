package com.vh.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 eval run 的报告 (W4.D24).
 *
 * <p>序列化为 JSON 落 {@code logs/eval-{ts}.json}, 同时控制台打印简化表格.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalReport {

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private long durationMs;
    private int totalCases;
    private int passed;
    private int failed;
    private List<CaseResult> cases = new ArrayList<>();
    /** 按 group 聚合的通过数 */
    private Map<String, GroupSummary> byGroup = new LinkedHashMap<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupSummary {
        private int total;
        private int passed;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CaseResult {
        private String id;
        private String group;
        private String desc;
        private boolean passed;
        private long durationMs;
        /** 失败时各断言条目说明 */
        private List<String> failures = new ArrayList<>();
        /** 主对话回复文本 */
        private String reply;
        /** 主 conversationId (target 那条) */
        private Long conversationId;
        /** 隔离用户 id */
        private Long userId;
    }
}
