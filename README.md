# ContextFlow

基于证据约束的汽车资料问答项目：从资料建模、检索与工作流编排，到模型生成、引用核验和前端交互，形成从 0 到可运行的完整前后端工程。

项目重点不是直接调用模型聊天，而是让回答经过明确的处理链，并能够回到本次使用的资料原文。内置资料为自编演示内容，非正式车厂 TSB；车型、页码和建议不作为维修依据。涉及车辆安全的问题必须交由具备资质的人员处理。

## 技术栈与工程结构

| 层次 | 技术 / 职责 |
| --- | --- |
| 服务端 | Java 21、Spring Boot 3.5.4、Maven |
| 工作流 | LangGraph4j 1.6.0-rc2、条件分支、节点追踪 |
| 关键词检索 | Lucene 9.12.1、SmartChineseAnalyzer、BM25 |
| 可选向量检索 | PostgreSQL + pgvector、Embedding、RRF |
| 模型接入 | JDK HttpClient、OpenAI-compatible Chat API、严格 JSON |
| 前端 | Vue 3、TypeScript、Ant Design Vue、Vite 7 |
| 事件传输 | POST fetch SSE、UTF-8 增量解析、主动取消 |

```text
ContextFlow/
├── pom.xml
├── frontend/
│   ├── src/                  # 聊天页面、引用展示、API 与 SSE 解析
│   └── tests/                # SSE 与交互规则测试源码
└── src/
    ├── main/java/com/contextflow/
    │   ├── ApiController.java
    │   ├── Contracts.java
    │   ├── CorpusService.java
    │   ├── DiagnosisService.java
    │   ├── ModelGateway.java
    │   └── VectorStore.java
    ├── main/resources/        # 环境配置、语料、案例、评测集、SQL
    └── test/java/com/contextflow/
```

构建生成的静态页面、依赖目录、IDE 文件、本地密钥和环境配置不纳入版本控制。仓库仅提供源码，首次运行需要自行构建前端。

## 1. 从业务问题拆解工作流

```text
输入车型、年份与问题
        ↓
input_check → lucene_search → evidence_check → generate → citation
   ↓                              ↓
ask_user                     no_evidence
```

- 输入缺少车型或年份，返回 `ask_user`，不盲目给出建议。
- 查询仅使用符合车型与年份范围的资料；无证据返回 `no_evidence`。
- 模型只接收候选证据，输出 `answer` 和 `citationIds`。
- 每个非空回答段落必须包含授权证据 ID；正文引用与引用列表必须一致。
- 原文工具校验工具名、候选范围、文档 ID 与页码，最多进行两次参数修正。
- 返回 requestId、节点状态与耗时，便于关联一次完整请求。

`lucene_search` 是沿用的节点名称，实际执行取决于检索模式；不能仅凭节点名判断使用了 Lucene。

## 2. 两种检索模式

### fixed-evidence：默认案例闭环

`RETRIEVAL_MODE=fixed-evidence` 按车型、年份及完整问题匹配案例，按预设候选顺序取最多 5 条有效资料。不执行 BM25、Embedding 或 RRF，也不要求数据库。

适合从页面选择内置案例，观察输入校验、生成与引用链路。任意改写问题不保证命中，固定证据匹配不是语义检索能力或检索效果指标。

### hybrid：关键词与可选第二路召回

设置 `RETRIEVAL_MODE=hybrid` 后建立 Lucene 内存索引，按车型、年份过滤：

- `VECTOR_ENABLED=true`：BM25 + pgvector。
- 未开启向量、`CASE_RECALL_ENABLED=true`：BM25 + 案例库召回。
- 两项均关闭：仅 BM25。

两路候选融合采用 `1/(60+rank1) + 1/(60+rank2)`，去重取 Top 5，同分按 ID 排序。pgvector 当前使用精确余弦距离排序，没有 HNSW / IVFFlat 近似索引；依赖失败不会静默伪装为成功的混合检索。

## 3. 从 0 到本地运行

### 环境准备

- JDK 21，配置 `JAVA_HOME` 和 PATH。
- Maven 3.9.x，或使用仓库 Maven Wrapper。
- Node.js `^20.19.0` 或 `>=22.12.0`，以及 npm。
- 一个可用的 OpenAI-compatible Chat API 账号、模型与额度。
- 仅在启用向量检索时需要 PostgreSQL、pgvector 和 Embedding API。

### 获取源码与配置模型

以下为 PowerShell 示例，安装路径和密钥按本机情况设置。占位符不是真实密钥，不要将真实值写进仓库或前端。

```powershell
git clone git@github.com:GuJunhao123/contextflow.git
Set-Location contextflow

$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:PORT = "8081"
$env:RETRIEVAL_MODE = "fixed-evidence"
$env:VECTOR_ENABLED = "false"
$env:LLM_ENABLED = "true"
$env:OPENAI_API_KEY = "<YOUR_CHAT_API_KEY>"
$env:OPENAI_BASE_URL = "https://api.openai.com/v1"
$env:OPENAI_MODEL = "gpt-4o-mini"
```

Chat base URL 会追加 `/chat/completions`，不要填写完整的 chat 路径。模型必须支持 JSON response format，返回 `finish_reason=stop` 和符合约定的 JSON 内容。实际模型名以账号权限为准。

**关闭 LLM 后没有离线模板回答。** `LLM_ENABLED=false` 时仍可读取资料、查询检索和观察无证据分支；命中证据进入生成节点会返回 `LLM_DISABLED`。启动成功不表示模型配置已经可用。

### 构建前端并启动服务

```powershell
Push-Location frontend
npm ci
npm run build
Pop-Location

mvn spring-boot:run
```

访问 <http://localhost:8081/>，选择页面内置案例，发送问题，查看诊断进程、回答和引用原文。

前端构建写入 `src/main/resources/static`，由后端提供同源页面。Maven 不会自动执行 npm。需要 JAR 部署时，在前端构建完成后执行：

```powershell
mvn package -DskipTests
java -jar .\target\contextflow-1.0.0.jar
```

前后端分离开发：后端启动后，在另一个终端进入 `frontend` 执行 `npm run dev`。Vite 将 `/api` 代理到 `http://127.0.0.1:8081`；后端改端口时同步修改代理配置。

## 4. 可选：启用 BM25 与向量检索

只体验 BM25 时设置 `RETRIEVAL_MODE=hybrid`、`VECTOR_ENABLED=false`、`CASE_RECALL_ENABLED=false`，无需数据库。

要加入向量召回，先安装 PostgreSQL 与 pgvector，并执行：

```powershell
createdb -h localhost -p 5432 -U postgres contextflow
psql -h localhost -p 5432 -U postgres -d contextflow -v ON_ERROR_STOP=1 -f .\src\main\resources\pgvector-init.sql
```

数据库已存在时跳过建库。SQL 创建扩展、`cf_chunks`、`cf_index_manifest` 与过滤索引，应用不会自动执行该脚本。在启动后端的终端设置：

```powershell
$env:RETRIEVAL_MODE = "hybrid"
$env:VECTOR_ENABLED = "true"
$env:VECTOR_JDBC_URL = "jdbc:postgresql://localhost:5432/contextflow"
$env:VECTOR_USERNAME = "postgres"
$env:VECTOR_PASSWORD = "<YOUR_DATABASE_PASSWORD>"
$env:EMBEDDING_API_KEY = "<YOUR_EMBEDDING_API_KEY>"
$env:EMBEDDING_URL = "https://api.openai.com/v1/embeddings"
$env:EMBEDDING_MODEL = "text-embedding-3-small"
$env:EMBEDDING_DIMENSIONS = "1536"
mvn spring-boot:run
```

启动后另开终端，显式重建索引并检查依赖：

```powershell
Invoke-RestMethod -Method Post http://localhost:8081/api/index/rebuild
Invoke-RestMethod http://localhost:8081/api/readiness | ConvertTo-Json -Depth 10
```

重建会逐条调用 Embedding；readiness 会主动探测启用的模型服务，均可能产生费用，不能作为高频免费存活检查。Chat 与 Embedding Key 独立配置，不会自动复用。

向量 manifest 校验语料版本、维度、模型与端点身份、条数。修改语料文件后需重启加载，再重建；更换 Embedding 模型、端点或维度也要重建。语料版本来自原始 JSON 字节 SHA-256。同步重建受总超时限制，不适合作为大规模离线入库任务。

## 5. 配置速查

| 环境变量 | 默认值 / 作用 |
| --- | --- |
| `PORT` | `8081` |
| `RETRIEVAL_MODE` | `fixed-evidence`；可选 `hybrid` |
| `CONTEXTFLOW_TIMEOUT_MS` | `120000`，请求总超时，代码限制 100–120000 ms |
| `MODEL_NETWORK_TIMEOUT_MS` | `90000`，模型网络与数据库超时配置 |
| `CORPUS_PATH` | 空，使用内置语料 |
| `EVALUATION_PATH` | 空，使用内置评测集；外部语料未指定时为空集 |
| `CASE_RECALL_ENABLED` | `true`，hybrid 非向量模式的案例召回开关 |
| `CASE_RECALL_PATH` | 空，使用内置案例；外部语料未指定时不套用内置案例 |
| `INDEX_MAX_EMBEDDING_CALLS` | `100`，单次重建的语料调用预算 |
| `LLM_ENABLED` | `true`，启用真实生成；仍需有效 Key |
| `OPENAI_API_KEY` | 空，通过环境变量注入 |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` |
| `OPENAI_MODEL` | `gpt-4o-mini` |
| `LLM_MAX_TOKENS` | `2000`，输出上限，不是输入上下文预算 |
| `VECTOR_ENABLED` | `false` |
| `VECTOR_JDBC_URL` | 本机 `contextflow` PostgreSQL 数据库 |
| `VECTOR_USERNAME` / `VECTOR_PASSWORD` | `postgres` / 空 |
| `EMBEDDING_URL` | `https://api.openai.com/v1/embeddings` |
| `EMBEDDING_API_KEY` | 空，通过环境变量注入 |
| `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` | `text-embedding-3-small` / `1536` |

Spring Boot 不会自动读取根目录 `.env`；请使用进程环境变量或自行管理本地配置加载。仓库忽略 `.env`、本地/生产配置和私钥，但忽略规则不能代替人工检查，也不能撤回已泄露凭据。

## 6. 资料、案例与评测

内置 `corpus.json` 包含 20 条自编演示资料，来源元信息见 `provenance.json`。这些内容不对应正式车厂通报，页码不是可查证的厂商 PDF 页码。`case-recall.json` 定义案例及候选顺序，`evaluation.json` 提供检索评测输入。

外部资料使用 UTF-8 JSON 数组，可参照 `corpus-import.example.json` 和 `evaluation-import.example.json`。语料字段为：

```json
[
  {
    "docId": "MANUAL-EXAMPLE-P1-C1",
    "title": "已授权资料标题",
    "model": "EXAMPLE-MODEL",
    "yearFrom": 2023,
    "yearTo": 2025,
    "page": 1,
    "text": "人工核对后的资料正文。",
    "advice": ""
  }
]
```

- `docId` 唯一，实际作为 chunkId；同一资料分多段时应使用不同稳定 ID。
- 车型使用字母、数字、下划线或连字符；年份范围 1900–2100，页码至少为 1。
- 正文非空且不超过 8000 字符；`advice` 必须是字符串，但不用于冒充模型回答。
- 单文件最多 32 MB，语料 1–10000 条，评测最多 100 题。
- QA 字段为 `id/model/year/question/gold`，gold 为人工标注的相关资料 ID 列表。

启动前配置路径：

```powershell
$env:CORPUS_PATH = "D:\ContextFlowData\authorized-corpus.json"
$env:EVALUATION_PATH = "D:\ContextFlowData\gold-qa.json"
$env:RETRIEVAL_MODE = "hybrid"
$env:CASE_RECALL_ENABLED = "false"
```

如需外部固定案例，则设置 `CASE_RECALL_PATH`，并保证案例候选指向已导入资料。系统不提供 PDF 下载、OCR、自动切分、跨页聚合或授权检查；资料合法使用、脱敏及向模型供应商传输的权限由使用者负责。

评测以当前检索配置返回的 Top 5 为准：Hit@5 表示至少命中一个 gold；Recall@5 为每题命中的不同 gold 数除以不同 gold 总数，再对题目平均。空 gold 当前计为 hit=false、recall=0，不能据此判断拒答正确率。固定案例命中不能证明语义检索泛化能力，检索命中也不能证明生成答案正确。

## 7. 接口与交互

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/meta` | 配置模式、语料版本与限制；不主动探测模型 |
| GET | `/api/scenarios` | 内置场景 |
| GET | `/api/corpus` | 资料列表 |
| GET | `/api/provenance` | 来源元信息 |
| GET | `/api/source/{docId}` | 资料原文 |
| GET | `/api/retrieval?model=...&year=...&question=...` | 候选、模式与 Top 5 |
| GET | `/api/readiness` | 主动依赖探测，可能计费 |
| POST | `/api/index/rebuild` | 向量重建，可能计费 |
| POST | `/api/diagnosis` | 一次性诊断结果 |
| POST | `/api/diagnosis/stream` | SSE 节点与最终结果 |
| POST | `/api/evaluation` | 当前 QA 集的检索评测 |

诊断请求包含 `model/year/question/scenario`，普通请求的 scenario 为 `normal`。响应直接读取根对象，没有 `data` 外壳。

前端提供聊天、案例选择、停止生成、节点进程、相关资料、引用原文、案例校验及资料库。SSE 事件为 `node/result/error`，不是逐 token 流。部分节点带有前端展示延迟；服务状态展示不等于主动依赖探测成功。聊天只存于页面内，补充问题由前端拼接，后端没有持久化跨轮记忆。

## 8. 工程设计与学习路线

1. **先建立契约**：用固定数据结构串起输入、来源、轨迹、结果与评测。
2. **再处理证据边界**：车型年份过滤、无证据分支、受限原文读取，避免直接无限制问模型。
3. **引入检索与版本**：BM25、向量、RRF、manifest，区分固定案例复现与真实召回。
4. **接入生成并校验**：超时、响应大小、严格 JSON、每段授权引用，不用模板掩盖模型失败。
5. **完成可观察交互**：POST SSE、增量 UTF-8、终止事件、历史回答与证据请求绑定。
6. **控制并发和外部资源**：8 个工作线程、32 个排队位、拒绝策略、总超时与协作取消；满队列返回 503，总超时返回 504。
7. **保留验证边界**：引用格式校验不等于事实正确；mock 测试不等于真实依赖端到端验证。

仓库保留后端与前端测试源码，可按需执行 `mvn test`、前端目录 `npm test`。本次发布不以运行或测试通过作为完成条件，不提供本次后端构建、数据库、模型联调或生产压测通过的结论。

## 9. 常见问题与范围

- 无首页：先构建前端，再启动或打包后端；静态产物未提交。
- 案例未命中：默认固定模式要求匹配完整案例问题；自由输入可改用 hybrid。
- 模型不可用：核对启动进程的 Key、base URL、模型权限、额度及 JSON 模式支持。
- `LLM_DISABLED`：开启模型并提供凭据；没有离线答案兜底。
- `INDEX_NOT_READY`：核对扩展、SQL、账号权限、语料版本与重建结果。
- 重建超时：缩小演示资料规模，当前接口不是后台长任务。
- 引用失败：不接受未知 ID、引用列表与正文不一致、截断或不合法 JSON。

项目未实现用户鉴权、租户隔离、持久记忆、自主规划、多 Agent、输入 Token 预算或生产级安全防护。不要直接公开部署含敏感资料且无访问控制的服务。任何车辆相关输出仅用于资料辅助检索，不代替专业诊断，更不得指导自行高压带电拆修。
