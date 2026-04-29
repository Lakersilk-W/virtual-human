-- =========================================================
-- V9 (W3 修补): memory_episode 加 kind 标识 episode 来源
--
-- 起因: rollup 只压缩窗口前半段, kept tail 永远没机会成为 episode.
--       发现: conv 关闭后, "用户后期才聊到的关键内容"(在 tail 里) 永远不入 Milvus,
--             跨会话召回拿不到. 改: 加一个空闲超时 finalize 兜底.
--
-- kind:
--   ROLLUP   — SummaryService 滚动摘要触发, 索引被压缩的那段 (W3.D18 原始路径)
--   FINALIZE — EpisodeFinalizationScheduler 在会话空闲 N 分钟后触发,
--              索引整段 chat history (含 tail), 兜底
-- =========================================================

ALTER TABLE memory_episode
  ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'ROLLUP' AFTER msg_count;

ALTER TABLE memory_episode
  ADD KEY idx_episode_kind_conv (source_conv_id, kind);
