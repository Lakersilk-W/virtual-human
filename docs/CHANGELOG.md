# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 风格记录显著变更。
版本号尚未发布到 GA, 用 `Unreleased` 段累积本期改动。

## [Unreleased] — W3 收尾, 2026-04-29

### Added — 分层记忆系统 (亮点 #1)

#### Summary 层 (D15)
- `conversation_summary` 表 (V6); 每会话 1 行, version 自增
- `SummaryService` — STM 中非 system 消息 ≥ 16 时触发 rollup, 把较老的 8 条压缩成摘要,
  内存重写为 `[persona, [过往对话摘要] vN, last 8]`; 同步执行
- 触发 hook 接在 `ChatService.chatAs` 出口 (Worker 返回后)

#### Semantic 层 (D16-17)
- `memory_fact` 表 (V7); UNIQUE (user_id, fact_key), 跨会话 user-scoped
- `FactExtractorService` — 每轮 AI 回复后用 LLM 抽用户事实, JSON 输出, upsert 去重
- `MemoryRecallService.recall` — 查 active facts 注入 `[关于用户的事实]` SystemMessage
- prompt 显式区分 `[用户]` / `[助手]` 数据源, 拒绝从助手回应里抽事实

#### Episodic 层 (D18-20)
- `memory_episode` 表 (V8) + Milvus collection `episode_vectors` (512 维, IVF_FLAT, IP)
- `EmbeddingConfig` — 本地 BGE-small-zh-v15 ONNX (in-process, ~100MB, 无外部 key)
- `MilvusEpisodeStore` — 启动 ensure collection + index + load; 连不上不影响主对话
- `EpisodeService.index` — embed summary → Milvus + memory_episode
- `MemoryRecallService.recall` 扩展 — embed user msg + Milvus top-3 召回 (排除当前 conv) →
  注入 `[相关历史话题]` SystemMessage
- piggyback 在 SummaryService rollup 时同步索引 (kind=ROLLUP)

#### W3 修补 (D21)
- `EpisodeFinalizationScheduler` — `@Scheduled` 5min 扫描, 空闲 ≥ 5min 的 ACTIVE 会话
  且无 FINALIZE episode → 索引整段 chat history (含 tail), 修补 rollup 永远漏掉 tail 的 bug
- `memory_episode.kind` 列 (V9): ROLLUP / FINALIZE 两种触发
- prompt 收紧 (Summary + FactExtractor): 显式禁止把 AI 即兴说的内容当成用户事实/经历;
  放宽用户近况状态 (`pet_health_issue` / `current_focus`) 也可抽取

### Added — Trace 扩展
- 4 类新 step: `MEMORY_RECALL` (前置同步) / `FACT_EXTRACT` (后置同步) /
  `SUMMARY_WRITE` (rollup 触发) / `EPISODE_INDEX` (rollup 同步 + finalize 异步)
- traces.html: 5 种新 badge 颜色; MEMORY_RECALL summary 显示 fact + episode 双块;
  EPISODE_INDEX summary 显示 kind 标签; pickUserMessage 检测异步 turn (无 INTENT_CLASSIFY) 标"⏱ 调度"

### Architecture
- `docs/architecture.md` 大改:
  - Section 1 mermaid: Runtime 加 memory 子图含 7 个 W3 服务; Infra 加 Milvus/etcd/MinIO 三件套
  - Section 2 时序图: 主链路加 MEMORY_RECALL/FACT_EXTRACT/SUMMARY_WRITE 步骤;
    新增独立的"异步链路"时序图 (Scheduler → finalize)
  - Section 3 数据流图: LongTerm 子图全部转实框
  - Section 4 状态对照表: W3 列全部 ✅ (含 4 子项: 嵌入 / 向量库 / 异步任务 / 注入)
  - Section 5 设计决策从 8 条扩到 13 条: 4 层记忆划分 / 本地 BGE 选型 / 不进 ChatMemory /
    ROLLUP+FINALIZE 双触发 / prompt isolation

## [W2] — 2026-04-26 ~ 04-28

### Added — Agent 编排
- `IntentService` — 独立模型 (T=0) + JSON 输出的意图分类, 失败/低置信度走 fallback
- `AgentRouter` — 按意图选 Worker; 意图找不到/未绑工具/工具全 disable 都软降级到 ChatterWorker
- `ChatterWorker` / `ToolWorker` — Worker 抽象;
  - ChatterWorker 单次 LLM, 无工具
  - ToolWorker ReAct 循环 + 多 spec 注册, 单轮内 `CompletableFuture.allOf` 真并行执行多工具
- `SystemPromptComposer` — 在首轮 SystemMessage 末尾注入"今天是 yyyy-MM-dd 周X", 修复 LLM 不知日期乱猜季节的 bug
- `MessageDumpUtil` — 把 `List<ChatMessage>` 压扁成 trace 可序列化结构

### Added — Trace 落库 + 可视化
- `execution_trace` 表 + `TraceCollector` (ThreadLocal 同步收集) + `TraceWriter` (出口批量 INSERT)
- 4 类 step: `INTENT_CLASSIFY` / `ROUTE` / `LLM_CHAT` / `TOOL_CALL`, 每条带 input/output JSON
- LLM_CHAT 的 input 含完整 `messages` 快照, 用于排查 prompt
- `TOOL_CALL` 的 input 含 `parallelGroupSize`, 标注是否并行 fan-out
- `TraceController` — 两个端点 (`/api/traces/conversations` 列会话; `/api/traces/conversations/{id}` 取步骤)
- `traces.html` — 双栏可视化页面, 左栏会话列表, 右栏按 INTENT_CLASSIFY 切轮的 timeline; step badge 颜色区分类型, 点开看完整 input/output JSON

### Changed
- DB schema V5: `vh_intent.bound_tool_id` 单值 FK → `vh_intent_tool(intent_id, tool_id, sort_order)` 多对多. 同时给 `weather_query` 追加 `current_time` 工具, 演示并行
- `chat.html` 默认走非流式 `/chat` (从 `/chat/stream` 切换), 完整跑 Intent → Router → Worker 链路并产生 trace; 同时新增「查看 trace」按钮跳到 `/traces.html`
- `chatAsStream` 仍保留作备用入口, 流式回调跨线程不能用 ThreadLocal Collector, 改为直接构 `ExecutionTrace` 走 `TraceWriter` 单步落库

### Architecture
- `docs/architecture.md` 大改: Mermaid 三图全部更新 (分层架构补 Intent/Agent/Trace 三个子图, chatAs 时序图改为三段式 + 并行 fan-out, 数据流图 trace 与 vh_intent_tool 转实框); 关键设计决策从 5 条扩到 8 条, 新增多对多 fan-out / 当前日期注入 / TraceCollector 设计

## [W1] — 2026-04-22 ~ 04-26

### Added
- D1 骨架: Spring Boot 3.3 / Java 21 / Maven / Flyway / docker-compose
- D2 LangChain4j 接 DeepSeek + ChatModelFactory (含 streaming)
- D3 4 个核心实体 + Mapper + VhConfigLoader
- D4 人设接入对话 (system prompt 从 DB)
- D5 ChatMemory + Redis STM 滑窗 + 多轮
- D6 BuiltinToolRegistry + WeatherTool + ReAct 循环
- D7 SSE 流式 + 最小聊天 HTML

### Notes
- W1 期内 git 尚未 init, 详细 diff 见 W2 起的 commits

## [W1 Day 1-6] — 实现累积

> 不在此文档详记, 见 git 历史 (待 git init 后启用)。简表:
> - D1 骨架: Spring Boot 3.3 / Java 21 / Maven / Flyway / docker-compose
> - D2 LangChain4j 接 DeepSeek + ChatModelFactory
> - D3 4 个核心实体 + Mapper + VhConfigLoader
> - D4 人设接入对话 (system prompt 从 DB)
> - D5 ChatMemory + Redis STM 滑窗 + 多轮
> - D6 BuiltinToolRegistry + WeatherTool + ReAct 循环
