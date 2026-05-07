# Demo 演示脚本（3-5 分钟）

> 录屏 / 现场讲解都按这个分镜走。每段都先讲"为什么有"再"看到什么"再"为什么这么做"——
> 三个动作连起来就是一段完整的故事。
>
> 录屏前清单：
> - `docker compose --profile memory up -d` 起 mysql/redis/milvus
> - `mvn spring-boot:run` 起服务
> - 浏览器开两个 tab：`/chat.html` 和 `/traces.html`
> - 终端预备一个 `curl http://localhost:8080/api/costs/summary | jq` 命令

---

## 0. 开场（15 秒）

> "这是我作品集里的虚拟人平台。技术栈是 Java 21 + Spring Boot 3 + LangChain4j 1.0，
> 重点不是写一个聊天 demo——是把生产级 AI 应用的几个关键能力都做齐：
> Agent 编排、分层记忆、Trace 可观测、成本追踪、多 Provider fallback、Eval pipeline。
> 我用四周时间做完，今天 3 分钟挑两个亮点演示。"

---

## 1. Agent 编排 + 多工具并行（45 秒）

打开 `chat.html`，输入：

> **上海现在多少度，顺便告诉我现在几点**

> "这句话本来要分两次问。看 LangChain4j `AiServices` 默认实现，模型每轮只能拿到一个工具
> spec，要串行调两次。我把 schema 改成 `vh_intent_tool` 多对多，意图 `weather_query`
> 同时绑 `weather` 和 `current_time` 两个工具，单轮 LLM 内模型可以一次吐多个
> `ToolExecutionRequest`，我用 `CompletableFuture.allOf` 把它们真正并发执行。"

切到 `traces.html`，打开刚才那条会话：

> "看 trace 时间线——单条 LLM_CHAT 之后的两个 TOOL_CALL，input 里有 `parallelGroupSize=2`，
> 它们的 wall-clock 接近，不是串行。trace 全部走 ThreadLocal 收集 + 出口批量 INSERT，
> 不在热路径上加同步 IO。"

---

## 2. 分层记忆 — Semantic facts 跨会话召回（60 秒）

回到 `chat.html`，**新建会话**，输入：

> **我叫小张，在上海做后端，养了只叫芝麻的橘猫**

等 AI 回完，**关掉浏览器 tab**（模拟新会话/新一天再来），重新打开 `chat.html` 开**新会话**：

> **你还记得我叫啥不？我家那只小毛球今天又拆家了**

AI 应该能叫出名字 + 自然衔接养猫的话题。

切到 `traces.html` 这条会话的 trace：

> "看第一步 `MEMORY_RECALL`——它在 LLM_CHAT 之前就跑了，从 `memory_fact` 表查到
> `name=小张` `pet_name=芝麻`，组成一段 SystemMessage 注入 prompt。
> 这套抽取在每轮 AI 回复后做——你看 trace 末尾的 `FACT_EXTRACT` 步骤，prompt 里
> 我显式声明只从 `[用户]` 行抽，**不从 `[助手]` 抽**——之前发生过 bug：助手即兴说
> '我也养猫叫芝麻'被抽成了用户事实，导致下一轮 AI 误以为用户养了一只叫芝麻的猫。"

> "完整记忆分四层：STM 在 Redis 滑窗（每轮上下文）；Summary 是当前会话的滚动摘要；
> Episodic 是跨会话的对话片段，本地 BGE-small-zh-v15 in-process 算 embedding，
> Milvus 召回 top-3；Semantic 就是刚演示的用户事实。对应 MemGPT、ChatGPT Memory 的
> 经典划分。"

---

## 3. 成本与 Fallback（45 秒）

终端：

```bash
curl http://localhost:8080/api/costs/summary | jq
```

> "每次 `ChatModel.chat()` 都被 `CostTrackingChatModel` 装饰器自动落一行 `cost_record`，
> 按 `application.yml` 里 `vh.pricing.{model}` 的每百万 token 单价折算 USD。
> 这是 `/api/costs/summary` 的输出——总成本、今日、按模型分组。trace 浏览页每条会话
> 也直接显示成本字段，演示了 W4 的两个亮点之一。"

> "另一个是 fallback chain。`ChatModelFactory.get()` 返回的不是裸 ChatModel，
> 是 `FallbackChatModel`，按配置链顺序退到下一个：deepseek-chat → deepseek-reasoner →
> 永不抛的 EchoChatModel 兜底。每个 tier 自带 cost tracking，所以 fallback 切换时
> cost 仍正确归到实际产生 token 的那个 model。接入 Qwen / 智谱只需改一行配置。"

---

## 4. Eval pipeline（30 秒）

终端另开一个 shell：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=eval
```

跑完后展示 stdout 总结表 + `logs/eval-{ts}.json`：

> "20 条 golden case 真打 LLM，覆盖六组：意图分类 / 工具调用 + 并行 / facts 抽取 /
> facts 召回（跨会话） / episodes 召回 / 闲聊兜底。每条 case 隔离 userId 和 conversationId，
> 跑完读 `execution_trace` 表做结构化断言——比对 `INTENT_CLASSIFY.intentCode`、
> `TOOL_CALL.name` 和 `parallelGroupSize`、`MEMORY_RECALL.factKeys` / `episodeCount`、
> `FACT_EXTRACT.upserted`。不走 LLM-as-judge，避免引入二次不确定性。"

---

## 5. 收尾（15 秒）

> "项目代码都在 `github.com/.../virtual-human`。详细设计决策（17 条）在
> `docs/architecture.md`，每条都按'问题 → 方案 → 选了 A 而不是 B 的原因'写。
> 谢谢。"

---

## 备用提问 Q&A 速查

- **「为什么不用 Dify？」** → 工作流形态固定（意图→工具/人设），变的是参数；Java 栈下跨语言成本高、动态 DSL 别扭；LangChain4j 抽象足够。
- **「为什么手写 ReAct 不用 `AiServices`？」** → 可观测——每步 trace 要落 `execution_trace` 含完整 messages 快照；`AiServices` 内部黑盒，加 max iteration / 重试都不顺手。
- **「为什么 facts/episodes 不进 ChatMemory，每轮重查？」** → ChatMemory 是 Redis 缓存，写进去就 sticky；facts/episodes 会随每轮抽取/索引更新，要做 invalidate 一致性。每轮重查 ~350ms 但语义新鲜，比维护一致性简单。
- **「ROLLUP / FINALIZE 两种 episode 触发？」** → ROLLUP 永远只压缩前半段，用户后期才说出的关键内容（kept tail）永远不入 Milvus；加 FINALIZE：会话空闲 5min 由 scheduler 索引含 tail 的整段，修补盲区。
- **「为什么本地 BGE 不用远程 embedding？」** → 不依赖外部 key、中文效果好、512 维成本/质量平衡，且体现"in-process 推理"作为加分项（多数 Java 后端 RAG 都是远程 embedding）。
- **「fallback 为什么不做熔断？」** → 1 个月 MVP，"失败即切换"覆盖 90% 价值（限流 / 网络抖动）；引入 resilience4j 增加状态机与配置面但讲不出更多故事。
