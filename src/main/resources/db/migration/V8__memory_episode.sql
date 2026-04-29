-- =========================================================
-- V8 (W3.D18-20): Episodic 情景记忆 — 向量化的对话片段, 跨会话相似召回
--
-- 触发: SummaryService rollup 时, 把被压缩的那段消息同时落成一个 episode:
--       - raw_text     = 那段原始消息拼接
--       - summary_text = 该段的 LLM 摘要 (复用 summary 内容)
--       - milvus_id    = embedding 在 Milvus 的主键
-- 召回: 每轮主流程之前, 对 user message 取 embedding -> Milvus top-k 搜
--       (filter 同 user_id, 排除当前 conv), 用 milvus_id 反查本表组装 SystemMessage.
--
-- 注: vector 自身存 Milvus 不存 MySQL, 这里只存元数据.
-- =========================================================

CREATE TABLE memory_episode (
  id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  source_conv_id  BIGINT UNSIGNED NOT NULL,
  summary_text    MEDIUMTEXT NOT NULL,
  raw_text        MEDIUMTEXT NOT NULL,
  msg_count       INT NOT NULL DEFAULT 0,
  /** Milvus collection 中的主键, embed 写入后回填 */
  milvus_id       BIGINT,
  occurred_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_episode_user (user_id, occurred_at),
  KEY idx_episode_conv (source_conv_id),
  KEY idx_episode_milvus (milvus_id),
  CONSTRAINT fk_episode_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_episode_conv FOREIGN KEY (source_conv_id) REFERENCES conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
