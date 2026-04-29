-- =========================================================
-- V6 (W3.D15): 滚动摘要表 — 长会话压缩长期记忆的第一层
--
-- 触发: STM 滑窗中非 system 消息 ≥ 16 (≈8 轮) 时, SummaryService 把较老的一段压缩
--       成一段摘要写入此表, 同时把内存里的对应消息替换成一个 [过往对话摘要 vN] SystemMessage.
-- 持久化: 每个会话最多 1 行, 每次 rollup 增 version 并覆盖 summary_text;
--         covers_message_count 累计该会话已经被压缩过的原始消息条数 (用于演进观察).
-- =========================================================

CREATE TABLE conversation_summary (
  id                     BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id        BIGINT UNSIGNED NOT NULL,
  summary_text           MEDIUMTEXT NOT NULL,
  covers_message_count   INT NOT NULL DEFAULT 0,
  version                INT NOT NULL DEFAULT 1,
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_summary_conv (conversation_id),
  CONSTRAINT fk_summary_conv FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
