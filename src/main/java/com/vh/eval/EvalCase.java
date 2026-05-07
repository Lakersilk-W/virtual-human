package com.vh.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 单条 golden case (W4.D24). YAML 反序列化目标.
 *
 * <p>{@link #setup} 用来铺陈跨会话场景 (e.g. 在 conv A 留下事实 / 索引 episode),
 * 然后 {@link #target} 跑真正被评估的那一轮. 单轮 case 不写 setup 即可.
 *
 * <h3>conversation 隔离</h3>
 * <ul>
 *   <li>每条 case 起一个独立 userId (EvalConversationManager 分配), 跨会话场景靠 convKey 切实例</li>
 *   <li>convKey 默认 "main", 同一 case 内同 key 复用 conversationId</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    private String id;
    /** intent / tools / facts_extract / facts_recall / episodes_recall / chitchat */
    private String group;
    private String desc;

    private List<EvalSetup> setup;
    private EvalTarget target;
    private EvalExpect expect;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalSetup {
        /** 默认 "main"; 跨会话用 "A" / "B" 区分 */
        private String convKey = "main";
        /** 单条消息 */
        private String message;
        /** 多条批量消息, 用于堆轮数触发 rollup */
        private List<String> messages;
        /**
         * 特殊动作:
         * <ul>
         *   <li>{@code force_rollup} — 通过批量消息后, 强制 SummaryService.maybeRollup</li>
         *   <li>{@code index_episode} — 直接调 EpisodeService.index 写一段 episode (跳过 LLM 摘要)</li>
         * </ul>
         */
        private String action;
        /** action=index_episode 时直接索引这段文字 */
        private String episodeText;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalTarget {
        private String convKey = "main";
        private String message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalExpect {
        /** 期望的 intentCode (来自 INTENT_CLASSIFY trace) */
        private String intentCode;
        /** 期望被调用的工具函数名 (来自 TOOL_CALL trace), 用 "containsAll" 语义 */
        private List<String> toolsCalled;
        /** 期望某次 LLM_CHAT 的并行 fan-out 至少 N 工具 */
        private Integer parallelGroupSizeMin;
        /** 抽出的 fact key 必须包含这些子串 (e.g. "name", "pet") */
        private List<String> extractedFactKeyContains;
        /** MEMORY_RECALL 的 factKeys 必须包含这些子串 (跨会话召回断言) */
        private List<String> recallFactKeyContains;
        /** MEMORY_RECALL 的 episodeCount 至少 N */
        private Integer recallEpisodeCountMin;
        /** 最终回复必须含其中之一 */
        private List<String> replyContainsAny;
        /** 最终回复不应含这些 */
        private List<String> replyNotContains;
    }
}
