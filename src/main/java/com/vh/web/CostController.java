package com.vh.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.CostRecord;
import com.vh.repository.mapper.CostRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 成本统计接口 (W4.D22). 给 traces.html 提供成本展示数据.
 *
 * <ul>
 *   <li>GET /api/costs/conversations         — 会话成本汇总, 按最新一次调用倒序</li>
 *   <li>GET /api/costs/conversations/{id}    — 该会话所有 LLM 调用明细</li>
 *   <li>GET /api/costs/summary               — 全平台聚合: 总成本/今日/按模型分组</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/costs")
@RequiredArgsConstructor
public class CostController {

    private static final int MAX_LIMIT = 200;

    private final CostRecordMapper costRecordMapper;

    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations(
            @RequestParam(defaultValue = "50") int limit) {
        int safe = Math.max(1, Math.min(limit, MAX_LIMIT));
        return costRecordMapper.listConversationCosts(safe);
    }

    @GetMapping("/conversations/{conversationId}")
    public List<CostRecord> getRecords(@PathVariable Long conversationId) {
        return costRecordMapper.selectList(
                Wrappers.<CostRecord>lambdaQuery()
                        .eq(CostRecord::getConversationId, conversationId)
                        .orderByAsc(CostRecord::getCreatedAt));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        // 全部成本
        BigDecimal totalUsd = costRecordMapper.selectList(null).stream()
                .map(CostRecord::getCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalCalls = costRecordMapper.selectCount(null);

        // 今日 (00:00 起)
        LocalDateTime startOfToday = LocalDate.now().atTime(LocalTime.MIN);
        var todayRows = costRecordMapper.selectList(
                Wrappers.<CostRecord>lambdaQuery().ge(CostRecord::getCreatedAt, startOfToday));
        BigDecimal todayUsd = todayRows.stream()
                .map(CostRecord::getCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 按模型分组
        var byModel = costRecordMapper.aggregateByModel();

        return Map.of(
                "totalUsd", totalUsd,
                "totalCalls", totalCalls,
                "todayUsd", todayUsd,
                "todayCalls", todayRows.size(),
                "byModel", byModel);
    }
}
