-- =========================================================
-- V10 (W4.D22): LLM 调用成本记录
--
-- 每次 ChatModel.chat() 调用产生 1 行, 由 CostTrackingChatModel 包装器自动记录.
-- 单价从 application.yml 的 vh.pricing.{model} 取, 美元为单位.
-- 1 USD ≈ 7.2 RMB (展示时由 UI 决定换算).
-- =========================================================

CREATE TABLE cost_record (
  id                 BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id    BIGINT UNSIGNED,
  provider           VARCHAR(32)  NOT NULL,
  model_name         VARCHAR(64)  NOT NULL,
  prompt_tokens      INT NOT NULL DEFAULT 0,
  completion_tokens  INT NOT NULL DEFAULT 0,
  /** 输入 + 输出累计成本, 美元, 6 位精度 */
  cost_usd           DECIMAL(12, 6) NOT NULL DEFAULT 0,
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_cost_conv (conversation_id, created_at),
  KEY idx_cost_model (model_name, created_at),
  KEY idx_cost_day (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
