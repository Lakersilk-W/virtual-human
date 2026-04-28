# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 风格记录显著变更。
版本号尚未发布到 GA, 用 `Unreleased` 段累积本期改动。

## [Unreleased] — W2 收尾, 2026-04-28

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
