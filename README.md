# Virtual Human · AI 虚拟人平台

Java + LangChain4j 搭建的虚拟人平台，聚焦两大亮点：**分层记忆系统**、**Agent 编排（Router + ReAct + Parallel Tools）**。

状态：🚧 MVP 开发中（1 个月路线图，当前 Week 2 收尾）

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
- Trace 浏览：<http://localhost:8080/traces.html>（按会话查看每轮 LLM 调用 + 工具调用，可展开看完整 prompt 与 input/output JSON）

试一句 "上海现在多少度，顺便告诉我现在几点"：会命中 `weather_query` 意图，单轮 LLM_CHAT 内并行请求 weather + current_time 两个工具。

## 路线图

| 周 | 里程碑 |
|---|---|
| W1 | 基础对话 + 工具调用 + SSE 流式 ✅ |
| W2 | Agent 编排（Router + ReAct + Parallel）+ trace 落库 + 可视化 ← **当前** |
| W3 | 记忆四层（STM / Summary / Episodic / Semantic） |
| W4 | 成本统计 + README + 架构图 + demo 视频 |

详细设计文档:
- [架构总览](docs/architecture.md) — 分层、时序、数据流三张图 + 关键设计决策
- [CHANGELOG](docs/CHANGELOG.md)
