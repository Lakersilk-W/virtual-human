package com.vh.runtime.trace;

import lombok.Data;

import java.time.LocalDateTime;

/** trace 浏览页左栏的会话汇总条目. 由 ExecutionTraceMapper 聚合查询填充. */
@Data
public class ConversationTraceSummary {
    private Long conversationId;
    private Long vhId;
    private LocalDateTime convCreatedAt;
    private Integer stepCount;
    private LocalDateTime lastTraceAt;
    /** INTENT_CLASSIFY 出现次数 = 用户消息轮数 */
    private Integer turnCount;
    /** 该会话所有 trace 步骤 duration_ms 之和 */
    private Long totalMs;
}
