# Virtual Human · AI 虚拟人平台

Java + LangChain4j 搭建的虚拟人平台，聚焦两大亮点：**分层记忆系统**、**Agent 编排（Router + ReAct + Parallel Tools）**。

状态：🚧 MVP 开发中（1 个月路线图，当前 Week 4 收尾）

## 技术栈

- Java 21 · Spring Boot 3.3 · Maven
- LangChain4j 1.0 · DeepSeek (OpenAI 兼容)
- MySQL 8 · Redis 7 · Milvus 2.3 (W3)
- MyBatis-Plus · Flyway

## 本地启动

### 1. 准备环境变量

```bash
cp .env.example .env
# 编辑 .env, 填入 DEEPSEEK_API_KEY
```

### 2. 启动依赖容器

```bash
# W1/W2 只需 MySQL + Redis
docker compose up -d

# W3 开始才需要向量库 (多跑 3 个容器: etcd + minio + milvus)
docker compose --profile memory up -d
```

验证：

```bash
docker compose ps                       # 看 mysql/redis 都是 healthy
docker exec vh-mysql mysql -uvh -pvhpass -e "SELECT 1" vh
docker exec vh-redis redis-cli ping     # PONG
```

### 3. 启动应用

```bash
# 从 .env 导出环境变量
export $(cat .env | xargs)

./mvnw spring-boot:run
# 或 mvn spring-boot:run
```

验证：

```bash
curl http://localhost:8080/api/ping
# {"status":"ok","service":"virtual-human","ts":"..."}

curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP",...},"redis":{"status":"UP",...}}}
```

### 4. 试用

- 聊天页面：<http://localhost:8080/chat.html>（默认走非流式 `/api/virtual-humans/{id}/chat`，完整 Intent → Router → Worker 链路）
- Trace 浏览：<http://localhost:8080/traces.html>（按会话查看每轮 LLM 调用 + 工具调用 + 记忆召回/抽取，可展开看完整 prompt 与 input/output JSON）

几个 demo 句：
- 工具并行：「上海现在多少度，顺便告诉我现在几点」→ 单轮 LLM_CHAT 内 fan-out weather + current_time
- 跨会话 facts：会话 A 说"我叫小张，养了只猫" → 关掉浏览器开会话 B 问"你记得我叫啥吗" → 命中 fact 召回
- 跨会话 episodes：会话 A 聊一长串关于猫的具体细节 → 等 5min 让 finalize scheduler 跑 → 会话 B 问相关话题 → 召回到 episode

## 路线图

| 周 | 里程碑 |
|---|---|
| W1 | 基础对话 + 工具调用 + SSE 流式 ✅ |
| W2 | Agent 编排（Router + ReAct + Parallel）+ trace 落库 + 可视化 ✅ |
| W3 | 分层记忆（STM / Summary / Episodic / Semantic）+ Milvus + 异步索引兜底 ✅ |
| W4 | 成本统计 ✅ + 多 Provider fallback ✅ + 评估 pipeline ✅ ← **当前** |

## W4 亮点

### 成本追踪（cost tracking）
每次 `ChatModel.chat()` 都被装饰器 `CostTrackingChatModel` 自动记一行 `cost_record`，按
`vh.pricing.{model}` 配置的每百万 token 单价折算 USD。trace 浏览页和成本端点都能读到：

```bash
curl http://localhost:8080/api/costs/summary       # 全平台聚合 + 今日 + 按模型分组
curl http://localhost:8080/api/costs/conversations # 会话维度成本汇总
```

价格来自 `application.yml` 的 `vh.pricing.*`，未配置的模型 cost 计 0（一次 warn 不刷屏）。
切换 model 不丢账，因为装饰器在每个 (provider, model) 维度独立包装。

### 多 Provider fallback chain
`ChatModelFactory.get()` 返回的 ChatModel 实际是 `FallbackChatModel`，按配置顺序退到下一个：

```
FallbackChatModel
  ├── CostTrackingChatModel(deepseek-chat)        ← primary
  ├── CostTrackingChatModel(deepseek-reasoner)    ← fallback 1 (vh.llm.fallback.chain)
  └── EchoChatModel                                ← 永不抛, 兜底返回固定提示
```

接 Qwen / 智谱 / Claude 时只需在 `vh.llm.fallback.chain` 加一项（前两者走 OpenAI 兼容
协议复用 deepseek 路径，Claude 需补 anthropic 分支）。失败即切换，不做熔断/退避。

### Evaluation pipeline（20 条 golden case）
`src/main/resources/eval/golden-cases.yaml` 用 YAML 描述 20 条 case，覆盖 6 个组：
意图分类、工具调用（含并行）、facts 抽取、facts 召回（跨会话）、episodes 召回、闲聊兜底。

跑法：

```bash
# 启动依赖 (mysql/redis/milvus)
docker compose --profile memory up -d
# 跑 eval (真实打 LLM, 单跑 ≈ 5-10 分钟)
mvn spring-boot:run -Dspring-boot.run.profiles=eval
```

每条 case 隔离 `userId` 与 `conversationId`，跑完读 `execution_trace` 表做结构化断言：
比对 `INTENT_CLASSIFY.intentCode` / `TOOL_CALL.name` & `parallelGroupSize` /
`MEMORY_RECALL.factKeys` & `episodeCount` / `FACT_EXTRACT.upserted`，
末尾输出按 group 的通过率 + JSON 报告 `logs/eval-{ts}.json`。

详细设计文档:
- [架构总览](docs/architecture.md) — 分层、时序、数据流三张图 + 关键设计决策
- [Demo 演示脚本](docs/demo-script.md) — 3-5 分钟分镜
- [CHANGELOG](docs/CHANGELOG.md)
