# 项目洞察代理（Project Analyzer Agent）

基于 **Spring Boot + LangChain4j + DeepSeek** 的智能代码审计与架构分析代理，配套 **Vite + React + Tailwind CSS** 前端界面。  
它可以扫描任意代码仓库，生成高质量的项目蓝图（Markdown），并构建 RAG 向量索引，支持对项目进行实时问答。

---

## 功能总览

- **项目级审计分析**
  - 扫描整个项目源码（支持 Java / Python / C++ / Go / JS）
  - 统计语言与文件分布，识别入口文件
  - 生成结构化的架构/逻辑审计报告（Markdown Blueprint）
- **双模式项目审计**
  - **标准模式**：一次性扫描 + 逻辑审计 + 向量化索引
  - **交互式模式**：Agent 基于 `Project-Map.json` 主动点名查看关键文件，逐步构建“探查笔记”，再由 Reasoner 提炼成最终蓝图
- **RAG 知识库与对话**
  - 将蓝图与代码结构化切片后写入向量库
  - 对话记忆持久化到 `data/projects/{projectId}/chat-memory.json`
  - 用户提问与 AI 回答会增量向量化写入同项目向量库，可被后续问题召回
  - 支持普通对话接口与 SSE 流式对话接口
  - 对话阶段可结合 `Project-Map.json` + 工具化文件读取进行补充审计
  - 前端内置 RAG 对话面板，可对指定项目持续提问
- **Web UI 操作台**
  - 目录浏览器：在服务器文件系统中选择要审计的项目根目录
  - 审计进度展示：伪进度条 + 多阶段状态提示
  - 审计结果浏览：左侧展示 Blueprint（Markdown 渲染），右侧展示 RAG 对话
  - 已索引项目列表：支持从历史分析结果中快速恢复

---

## 技术栈

**后端**
- Java 17+（推荐 21）
- Spring Boot 3.3.5
- LangChain4j 0.36.2
- DeepSeek API（`deepseek-reasoner` / `deepseek-chat`）
- 本地嵌入模型：`bge-small-zh-v1.5-q`（langchain4j-embeddings-bge-small-zh-v15-q）

**前端**
- React 19（函数式组件 + Hooks）
- Vite 6
- TypeScript 5
- Tailwind CSS 3
- `lucide-react` 图标、`react-markdown` + `remark-gfm` 用于 Markdown & Mermaid 渲染

---

## 系统架构概览

后端主要模块（见 `src/main/java/com/bupt/cqy/analyzer`）：

- `AnalysisController`
  - `/api/analyze`：标准审计（扫描 + 逻辑审计 + Blueprint 落盘 + RAG 索引）
  - `/api/analyze/interactive`：交互式审计（Agent 工具调用 + 两阶段推理）
  - `/api/analyze-snippet`：单文件审计
  - `/api/chat` & `/api/chat/stream`：RAG 问答（普通 & SSE 流式）
  - `/api/projects` & `/api/projects/{projectId}`：已索引项目管理
  - `/api/browse`：服务器端目录浏览接口（供前端路径选择器使用）
  - `/api/health`：健康检查
- `LogicAuditService`
  - 对多语言源码进行结构化切片并调用 `AuditAssistant`，生成整体逻辑审计报告
- `InteractiveAuditService`
  - 第一阶段：Agent（基于 `deepSeekV3ChatModel` + `readFileSnippet` Tool）按照 `Project-Map` 自主探索
  - 第二阶段：Reasoner（`deepSeekChatModel`）基于“探查笔记 + 项目骨架”合成高保真 Blueprint
- `ProjectMapService` & `ProjectMap`
  - 扫描项目，生成轻量级 `Project-Map.json`（语言统计、入口候选、角色标签等）
  - 持久化路径：`data/projects/{projectId}/Project-Map.json`
- `ProjectIndexManager` / `IndexService` / `RagChatService`
  - 将 Blueprint + 代码片段分块、向量化、存储
  - 提供混合检索（向量 + 关键词）与 RAG 对话能力
- `DeepSeekConfig`
  - 配置三类 DeepSeek 模型：Reasoner、V3（Tool Calling）、Streaming Reasoner（SSE）

前端主要模块（见 `frontend/src`）：

- `App.tsx`
  - 维护整体状态：审计阶段、进度、蓝图内容、项目 ID 等
  - 根据模式（标准 / 交互式）调用 `analyzeProject` 或 `analyzeProjectInteractive`
  - 分别渲染 Dashboard（输入 & 进度）、BlueprintViewer、RagChat
- `components/Dashboard.tsx`
  - 项目路径输入框 + 模式切换
  - 目录浏览弹窗（通过 `/api/browse` 浏览服务器目录）
  - “已索引项目”列表（调用 `/api/projects`）
- `api.ts`
  - 封装前端调用的 REST API：`/api/analyze`、`/api/analyze/interactive`、`/api/chat`、`/api/projects` 等

---

## 环境准备

- **Java 17+**（推荐安装 21）
- **Maven 3.8+**
- **Node.js 18+ / pnpm / npm / yarn**（任意一个即可，用于前端）
- **DeepSeek API Key**

确保在后端进程环境中配置 DeepSeek 密钥，Spring Boot 会自动映射到 `deepseek.api.key`：

```bash
export DEEPSEEK_API_KEY=你的-deepseek-api-key
# 或使用 Windows PowerShell
$env:DEEPSEEK_API_KEY="你的-deepseek-api-key"
```

也可以在 `application.yml` / `application.properties` 中显式配置：

```properties
deepseek.api.key=${DEEPSEEK_API_KEY}
```

### RAG 对话降本与超时保护参数（推荐保留默认）

```properties
rag.chat.vector.top-k=4
rag.chat.keyword.top-k=2
rag.chat.min-score=0.35
rag.chat.max-context-chars=8000
rag.chat.max-memory-turns=4
rag.chat.max-memory-context-chars=2500
rag.chat.enable.agentic.audit=false
rag.chat.agentic.audit.timeout.ms=8000
rag.chat.max-agentic-notes.chars=3000
rag.chat.sse.timeout.ms=600000
rag.chat.sse.heartbeat.ms=15000
```

- `rag.chat.enable.agentic.audit=false`：默认关闭每轮问答的额外工具审计，显著降低 token 与首包延迟。
- `rag.chat.sse.heartbeat.ms=15000`：流式期间每 15 秒发送心跳，减少“前端长时间无响应”与连接超时。

---

## 后端启动（Spring Boot）

在仓库根目录（包含 `pom.xml`）执行：

```bash
# 编译并打包
mvn clean install

# 启动开发环境（推荐）
mvn spring-boot:run
```

默认后端监听：

- HTTP：`http://localhost:8080`
- 健康检查：`GET /api/health`

---

## 前端启动（Vite + React）

前端代码位于 `frontend` 目录：

```bash
cd frontend

# 安装依赖（任选其一）
npm install
# 或
pnpm install

# 启动开发服务器
npm run dev
```

Vite 默认端口为 `5173`，启动后在浏览器打开：

```text
http://localhost:5173
```

前端会自动将 API 请求代理到后端（`/api/...`），请确保后端已在 8080 端口启动。

---

## Web 界面使用指南

1. **选择项目路径**
   - 在首页“项目路径”输入框中直接填写服务器上的代码目录（例如 `/home/xxx/my-project`）
   - 或点击右侧“文件夹”按钮，打开目录浏览器，通过 `/api/browse` 选择目录
2. **选择审计模式**
   - **标准审计**：一次性完成扫描 + 审计 + RAG 索引（适合初次接入项目）
   - **交互式审计**：Agent 会逐步点名关键文件，生成更“故事化”的探查笔记（适合深度理解复杂项目）
3. **等待分析完成**
   - 页面将展示三阶段进度条：扫描文件 → AI 审计 → RAG 索引
   - 错误信息会显示在错误提示卡片中
4. **浏览结果**
   - 审计完成后，左侧展示 Blueprint（支持 Markdown + Mermaid 渲染）
   - 如果是标准模式且存在 `projectId`，右侧会显示 RAG 对话面板
5. **RAG 对话**
   - 在右侧输入自然语言问题（中文优先），系统会基于已索引的蓝图和代码片段返回答案
   - 后端通过 `/api/chat` 或 `/api/chat/stream`（SSE）提供回答
6. **复用已索引项目**
   - Dashboard 底部展示“已索引项目”列表（来自 `/api/projects`）
   - 点击“进入”可直接恢复某个项目的 Blueprint 和 RAG 能力，无需重新扫描与审计

---

## 主要 API 说明

### 1. 标准项目审计

```bash
GET /api/analyze?path=/path/to/project&force=false
```

- `path`：要分析的项目根目录
- `force`：是否强制重新索引（默认 `false`，若已有索引则直接复用）

返回字段示例：

```json
{
  "status": "success",
  "projectId": "2025-xxxx",
  "alreadyIndexed": false,
  "filesScanned": 120,
  "auditReport": "# 审计报告 Markdown ...",
  "blueprint": "# 同上，一般等同于 auditReport",
  "message": "Analysis completed successfully. Blueprint saved and indexed for RAG."
}
```

### 2. 交互式审计

```bash
GET /api/analyze/interactive?path=/path/to/project&force=false
```

首次会触发完整的交互式审计流程，生成高保真 Blueprint 并建立索引；后续若 `force=false` 且已索引，则直接复用。

### 3. 单文件审计

```bash
POST /api/analyze-snippet
Content-Type: application/json

{
  "path": "/abs/path/to/File.java",
  "language": "Java",
  "code": "public class Example { ... }"
}
```

返回该文件的聚焦分析结果与改进建议。

### 4. RAG 对话

- 非流式：

```bash
POST /api/chat
Content-Type: application/json

{
  "projectId": "2025-xxxx",
  "sessionId": "session-abc",
  "question": "这个项目的整体架构分层是怎样的？"
}
```

- 流式（SSE）：

```bash
GET /api/chat/stream?projectId=2025-xxxx&sessionId=session-abc&question=...
Accept: text/event-stream
```

前端通过 `EventSource` 逐 token 接收结果。

### 5. 项目与目录管理

- 列出已索引项目：

```bash
GET /api/projects
```

- 获取单个项目详情：

```bash
GET /api/projects/{projectId}
```

- 浏览服务器文件系统（仅元信息）：

```bash
GET /api/browse?path=/some/dir
```

---

## 仓库结构（简要）

```text
project-analyzer-agent
├── pom.xml                      # 后端 Maven 配置
├── Project-Blueprint.md         # 当前仓库自身的 Blueprint（示例文档）
├── Project-Map.json             # 当前仓库的项目地图（由 ProjectMapService 生成）
├── src/main/java/com/bupt/cqy/analyzer
│   ├── controller/AnalysisController.java
│   ├── config/DeepSeekConfig.java
│   ├── model/ProjectMap.java
│   ├── service/
│   │   ├── InteractiveAuditService.java
│   │   ├── LogicAuditService.java
│   │   ├── ProjectMapService.java
│   │   └── ... 其他 Index/RAG 相关服务
│   └── ...
└── frontend
    ├── package.json
    ├── src/
    │   ├── App.tsx
    │   ├── api.ts
    │   └── components/
    │       ├── Dashboard.tsx
    │       └── ...
    └── ...
```

---

## 后续规划建议（来自 Blueprint 抽象）

- **向量存储持久化**：将当前向量库替换为持久化方案（如 Milvus / PgVector / Chroma），以支持大型项目与服务重启恢复。
- **多模型/本地化兜底**：在 DeepSeek 不可用或限流时，支持切换至 OpenAI / 本地模型等备选。
- **异步长任务与监控**：对超大项目分析采用异步任务 + 进度查询接口，并增强日志与用量监控。
- **安全与权限控制**：对目录浏览与文件读取增加白名单/权限校验，适配企业环境。

欢迎基于本仓库继续演进，打造适配自己团队的智能代码审计平台。

