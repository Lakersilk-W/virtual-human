-- =========================================================
-- V5: 意图与工具改为多对多, 支持单意图绑多工具 -> 模型可一轮 fan-out 并行调用
-- 演示场景: weather_query 同时绑 weather + current_time, 用户问
-- "上海现在多少度? 几点了?" 时模型在一轮 LLM_CHAT 内同时请求两个工具,
-- 由 ToolWorker 的 CompletableFuture.allOf 真正并发执行 (此前只暴露 1 个 spec, 名义并行).
-- =========================================================

CREATE TABLE vh_intent_tool (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  intent_id    BIGINT UNSIGNED NOT NULL,
  tool_id      BIGINT UNSIGNED NOT NULL,
  sort_order   INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_intent_tool (intent_id, tool_id),
  KEY idx_it_intent (intent_id),
  KEY idx_it_tool   (tool_id),
  CONSTRAINT fk_it_intent FOREIGN KEY (intent_id) REFERENCES vh_intent(id) ON DELETE CASCADE,
  CONSTRAINT fk_it_tool   FOREIGN KEY (tool_id)   REFERENCES tool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 迁移现有 vh_intent.bound_tool_id 数据
INSERT INTO vh_intent_tool (intent_id, tool_id, sort_order)
SELECT id, bound_tool_id, 0
FROM vh_intent
WHERE bound_tool_id IS NOT NULL;

-- weather_query 追加一个 current_time, 让单意图同时具备两工具用于演示并行
INSERT INTO vh_intent_tool (intent_id, tool_id, sort_order)
SELECT vi.id, t.id, 1
FROM vh_intent vi
JOIN tool t ON t.code = 'current_time'
WHERE vi.intent_code = 'weather_query';

-- 删 bound_tool_id 列 (含 FK)
ALTER TABLE vh_intent DROP FOREIGN KEY fk_intent_tool;
ALTER TABLE vh_intent DROP COLUMN bound_tool_id;
