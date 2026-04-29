-- =========================================================
-- V7 (W3.D16-17): 用户事实记忆 (Semantic 层)
--
-- 目标: 跨会话保留"关于用户"的结构化事实 (姓名/职业/养猫/喜好/约定),
--       让虚拟人在新会话里也能记得用户.
-- 触发: ChatService 每轮 AI 回复后, FactExtractorService 用 LLM 抽事实并 upsert.
-- 召回: 每轮 model.chat 之前, MemoryRecallService 查 active facts 注入 SystemMessage.
--
-- 注: 用 fact_key/fact_value 而非 key/value, 后者在 MySQL 是保留字会要反引号.
-- =========================================================

CREATE TABLE memory_fact (
  id                BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL,
  fact_key          VARCHAR(64) NOT NULL,
  fact_value        TEXT NOT NULL,
  source_conv_id    BIGINT UNSIGNED,
  confidence        DOUBLE NOT NULL DEFAULT 0.8,
  last_confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fact_user_key (user_id, fact_key),
  KEY idx_fact_user (user_id),
  KEY idx_fact_source (source_conv_id),
  CONSTRAINT fk_fact_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_fact_conv FOREIGN KEY (source_conv_id) REFERENCES conversation(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
