# WKC AI Platform 架構總覽

> 基於 `open-platform` 模組，涵蓋 Agent、Memory、RAG、Skill、Prompt、AI Gateway、Engine、Observability、Evaluation 九大子系統。

---

## 一、整體模組架構

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AI Gateway Layer                              │
│  UnifiedChatClient ← GatewayFilterChain (Retry|Fallback|Trace|...)   │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────────┐
│                      Execution Engine Layer                          │
│  ExecutionEngine (async|parallel|retry|timeout|stream)               │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
┌──────────┬─────────┬─────┴────┬──────────┬───────────┬─────────────┐
│  Agent   │  Skill  │  Prompt  │   RAG    │  Memory   │  Function   │
│  System  │  System │  Center  │  System  │  System   │  Registry   │
├──────────┴─────────┴──────────┴──────────┴───────────┴─────────────┤
│                      Observability Layer                             │
│  AiTracingService (OTel) | MemoryTracer | Metrics | Audit            │
└─────────────────────────────────────────────────────────────────────┘
```

**依賴關係**（由下至上）：
- Observability Layer → 所有上層
- Memory/Function/Engine → Agent
- Prompt → Skill Composer
- Agent → RAG + Memory（通過 Bridge）
- Gateway → Engine → Runtime

---

## 二、Agent 系統

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                        AgentRuntime (SPI)                         │
│  DefaultAgentRuntime                                              │
│  ├── SubAgentManager (嵌套代理管理)                                │
│  └── ReActLoop (核心狀態機)                                       │
└──────────────────────────────┬───────────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────────┐
│                         ReActLoop                                 │
│                                                                   │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────┐  │
│  │ PLAN     │──▶│ ACT      │──▶│ OBSERVE  │──▶│ REFLECT      │  │
│  │ (Planner)│   │(Executor)│   │(Observer)│   │ (Reflector)  │  │
│  └──────────┘   └──────────┘   └──────────┘   └──────┬───────┘  │
│       │              │               │               │          │
│       │         ┌────┴────┐          │         ┌─────┴──────┐   │
│       │         │Tool Step│          │         │ RETRY      │   │
│       │         │LLM Step │          │         │ CONTINUE   │   │
│       │         └─────────┘          │         │ COMPLETE   │   │
│       │                              │         └────────────┘   │
│       └────────── RAG Context ───────┴────────Memory Recall ────┘
└──────────────────────────────────────────────────────────────────┘
```

### Agent 生命週期狀態機

```
IDLE ──▶ PLANNING ──▶ ACTING ──▶ OBSERVING ──▶ REFLECTING ──▶ COMPLETED
                         │                          │
                         └───────── RETRY ───────────┘
                         │                    │
                         ▼                    ▼
                      FAILED              CANCELLED/TIMEOUT
```

### 核心類

| 類 | 職責 |
|-----|---------|
| `@AiAgent` | 自定義註解：name + systemPrompt + tools + temperature |
| `AiAgentFactory` | 工廠：根據註解創建 ChatClient / ReactAgent |
| `AgentContext` | 運行時上下文：messageHistory + variables + trace + state machine |
| `AgentConfig` | 配置：maxSteps / timeout / retries / HITL / jsonResponse |
| `AgentTask` | 任務：goal + context + toolFilter + config |
| `AgentResult` | 結果：success + result + stepTraces + tokenUsage + cost |
| `AgentEvent` | 流式事件類型：PLAN_START ~ AGENT_ERROR |
| `ReActLoop` | 核心編排器：Plan → Act → Observe → Reflect 循環 |
| `DefaultAgentRuntime` | Runtime 實現：委託 ReActLoop + ExecutionEngine |
| `AiAgentFactory` | 創建 ReactAgent（@Tool + FunctionRegistry 合併） |

### Planner SPI

| 實現 | 場景 |
|----------|----------|
| `TemplatePlanner` | 預定義步驟模板（確定性任務） |
| `LLMPlanner` | LLM 自主生成計劃（開放性任務） |
| `HybridPlanner` | 模板 + LLM 增強 |
| `NoOpPlanner` | 無規劃（直接執行） |

### Agent 與外部整合

```
AgentRuntime
  ├── AgentMemoryBridge → MemoryRuntime (Working/Episodic)
  ├── AgentRagBridge → EnhancedRagService
  ├── AgentSkillFunctionRegistrar → Skill 包裝為 Function (skill:xxx)
  └── FunctionRegistry → @Tool Bean + Function Bean
```

**Agent → Skill 橋接**：`AgentSkillFunctionRegistrar` 在 `@PostConstruct` 階段自動將所有 Skill 註冊為 Function（命名 `skill:{skillName}`），使 Agent 可透過 Function Calling 透明調用 Skill。

---

## 三、記憶系統（Memory System）

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                      MemoryRuntime (SPI 門面)                     │
│                   DefaultMemoryRuntime                            │
│  ├── WorkingMemoryManager    ├── EpisodicMemoryStore              │
│  └── MemoryEventBus ──┬── MemoryTracer (InMemory + OTel)         │
└─────────────────────────┬────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                   MemoryStore 層（Store SPI）                      │
│                                                                   │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐   │
│  │ WorkingMemory  │  │ EpisodicMemory │  │ SemanticMemory   │   │
│  │ Store          │  │ Store          │  │ Store            │   │
│  │ (Redis/Hash)   │  │ (Redis/Stream) │  │ (MySQL/Vector)   │   │
│  └────────────────┘  └────────────────┘  └──────────────────┘   │
│  ┌────────────────┐                                              │
│  │ Procedural     │                                              │
│  │ Memory Store   │                                              │
│  │ (MySQL)        │                                              │
│  └────────────────┘                                              │
└──────────────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                  Store Adapter Layer                              │
│  InMemoryMemoryStoreAdapter (dev/test)                           │
│  RedisMemoryStoreAdapter (prod - working + episodic)             │
│  MySQLMemoryStoreAdapter (prod - semantic + procedural)          │
│  ElasticsearchMemoryStoreAdapter (vector search)                 │
└──────────────────────────────────────────────────────────────────┘
```

### 記憶類型（MemoryType）

| 類型 | 生命週期 | 存儲 | 用途 |
|------|-----------|-------|---------|
| `WORKING` | Session (1h) | L1 堆內存 | 計劃樹、變量作用域、中間結果、恢復點 |
| `EPISODIC` | 7 天 | Redis Stream / Timeseries | 時間線事件/經歷，長追加 |
| `SEMANTIC` | 365 天 | Vector Store + MySQL | 事實/知識/關係，帶向量索引 |
| `PROCEDURAL` | 365 天 | MySQL | 可復用任務流程，帶版本管理 |
| `SESSION` | 30min | Redis RList | 聊天歷史，Episodic 的子類型 |

### MemoryCell — 原子記憶單元

```
MemoryCell {
    cellId: MemoryCellId      // 全局唯一
    memoryType: MemoryType     // 類型
    content: String            // 內容
    attributes: Map            // 結構化元數據
    timestamp: Instant         // 創建時間
    sequenceNumber: long       // 單調遞增序號
    vector: Optional<Vector>   // 向量嵌入
    tags: Map<String,String>  // 自定義標籤
    parentCellId: MemoryCellId // 父 Cell（因果/派生）
    version: int              // 樂觀鎖版本
}
```

### MemoryRelation — 知識圖譜

```
關係類型: CAUSAL | TEMPORAL | SEMANTIC | REFERENCES | DERIVED_FROM
MemoryRelation(from → to, type, weight[0,1], attributes)
```

### 記憶檢索管道

```
MemoryQuery (text | vector | hybrid)
    │
    ▼
RetrievalPipeline
    │
    ├── KeywordRetriever (BM25/contains)
    ├── VectorRetriever (cosine similarity)
    └── HybridRetriever (RRF 融合)
           │
           ▼
    RelevanceRanker (排序)
           │
           ▼
    QueryResult { ScoredMemoryCell[], totalFound, queryTime }
```

### 記憶 Consolidation（STM → LTM）

```
Episodic Cells (短期)                    Semantic/Procedural (長期)
┌──────────────┐    MemoryConsolidator    ┌──────────────────┐
│ 大量 Timeline │ ──────────────────────▶ │ 結構化事實/流程   │
│ 事件記錄      │   EpisodeExtraction      │ 向量索引+版本管理 │
└──────────────┘   FactExtraction          └──────────────────┘
     │                     │                      ▲
     └── ConsolidationPolicy 觸發條件 ──────────────┘
         - cell count > threshold
         - time since last consolidation
         - manual trigger
```

### 記憶策略系統

```
MemoryPolicy
  ├── RetentionPolicy: TTL per MemoryType (session=30min, episodic=7d, working=1h)
  ├── CompressionPolicy: 壓縮策略 (disabled / size-based / time-based)
  ├── ConsolidationPolicy: 歸納策略 (觸發條件 + 目標類型)
  └── EvictionPolicy: 淘汰策略 (LRU / FIFO / custom)
```

### WorkingMemory 內部結構

```
WorkingMemory {
    id: MemoryId
    rootPlan: PlanNode (樹狀計劃)
    globalScope: VariableScope (全局變量)
    scopeStack: Deque<VariableScope> (作用域棧, 可嵌套)
    intermediateResults: List<IntermediateResult>
    resumePoints: List<ResumePoint>
    state: ACTIVE | SUSPENDED | RESUMING | COMPLETED | FAILED
}
```

---

## 四、RAG 系統

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                         EnhancedRagService                        │
│                                                                   │
│  1. Query Rewriting ── 2. Multi-Route ── 3. RRF Fusion ── 4. LLM│
│                     │         │         │         Reranking       │
│                     │         │         │                        │
│              ┌──────┴──┐ ┌──┴──────┐   │   ┌────────────────┐  │
│              │Vector   │ │BM25     │   │   │LLM Reranker    │  │
│              │Retriever│ │Retriever│   └──▶│(LLM 語義評分)   │  │
│              │(ES)     │ │(ES)     │       └────────────────┘  │
│              └─────────┘ └─────────┘                           │
└──────────────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                      Document Pipeline                           │
│                                                                   │
│  DocumentSource → DocumentIngestor → EmbeddingModel → ES Index   │
│  (byte[]/InputStream/    (BGE Small ZH /                          │
│   MultipartFile)         text-embedding-3-small)                  │
│                                                                   │
│  ChunkSplitter: ParagraphSplitter / HeadingSplitter / ASTSplitter │
│  DocumentProcessor: AST 文檔處理器 (cleaner/enricher/etc)          │
└──────────────────────────────────────────────────────────────────┘
```

### 增強型 RAG 管道（6 階段）

```
用戶查詢
  │
  ▼
Stage 1: Query Rewriting (問題重寫)
  │ LLM 優化查詢為關鍵詞形式，提升召回率
  │ 降級：跳過重寫，使用原始查詢
  ▼
Stage 2: Multi-Route Retrieval (多路召回)
  ├── 向量路 (Vector): 語義相似度搜索 (ES)
  └── 關鍵詞路 (BM25): 精確匹配搜索 (ES)
  │ 降級：任一失敗時使用另一路結果
  ▼
Stage 3: RRF Fusion (融合)
  │ RRF(d) = Σ 1/(k + rank_i(d)), k=60
  │ 無需分數歸一化，按排名直接融合
  ▼
Stage 4: LLM Reranking (精排)
  │ LLM 分析查詢-文檔相關性，輸出語義評分
  │ 降級：跳過精排，直接取 RRF 結果 Top-K
  ▼
Stage 5: Context Assembly (上下文組裝)
  │ 格式化 Markdown 結構，按 maxContextTokens 截斷
  ▼
Stage 6: Inject into Agent/LLM (注入)
  │ ctx.putVariable("agent.ragContext", context)
```

### RAG 配置 (RAGProperties)

| 屬性 | 默認值 | 說明 |
|----------|---------------|------|
| enabled | false | 全局開關 |
| esHosts | localhost:9200 | ES 地址 |
| embeddingModel | text-embedding-3-small | Embedding 模型 |
| dimension | 384 | 向量維度 |
| candidateMultiplier | 3 | 粗排候選乘數 |
| maxContextTokens | 3000 | 最大上下文 tokens |
| retrieveTimeoutMs | 5000 | 檢索超時 |

---

## 五、Skill 系統

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                       SkillRuntime (SPI)                         │
│  DefaultSkillRuntime                                             │
│  ├── SkillSelector (Intent | Rule | LLM) → [skill1, skill2, ...] │
│  ├── DependencyResolver (依賴解析 + 拓撲排序)                    │
│  ├── PromptComposer (System Prompt 動態組裝)                     │
│  └── UnifiedChatClient (通過 Gateway 調用 LLM)                   │
└──────────────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                      Skill 定義存儲層                              │
│                                                                   │
│  SkillLoader → SkillRepository → classpath:/skills/*.md           │
│                                                                   │
│  .md 格式:                                                        │
│  ┌────────────────────────────────────────────────┐              │
│  │---                                              │              │
│  │name: summary                                    │              │
│  │version: 1.2.0                                   │              │
│  │description: 摘要生成                            │              │
│  │model: deepseek-chat                             │              │
│  │temperature: 0.3                                 │              │
│  │rag: enabled                                     │              │
│  │intent: ["summarize", "abstract"]                │              │
│  │dependencies: [common-rules]                     │              │
│  │---                                              │              │
│  │System Prompt 主體 (Handlebars 模板)              │              │
│  │{{> rag_context}} {{> memory_context}}           │              │
│  └────────────────────────────────────────────────┘              │
└──────────────────────────────────────────────────────────────────┘
```

### Skill 選擇過程

```
RouteContext (用戶請求)
    │
    ▼
IntentRouter (意圖識別)
    │ intent: "summarize"
    ▼
SkillSelector
    ├── IntentSkillSelector (配置映射) → ["summary", "common-rules"]
    ├── RuleSkillSelector (規則匹配) → ["fallback-skill"]
    └── LLM-based Selector (未來)
    │
    ▼
DependencyResolver (依賴拓撲排序)
    │ ["common-rules", "summary"] 確保依賴按序先行
    ▼
SkillRuntime.execute(["common-rules", "summary"], ctx)
    │
    ▼
PromptComposer.compose(["common-rules", "summary"], ctx)
    │ 1. 依序加載各 Skill .md Frontmatter + Body
    │ 2. 合併 RAG Context (若 rag=enabled)
    │ 3. 合併 Memory Context (若需)
    │ 4. 注入 Function Registry 工具描述
    │ 5. 渲染 Handlebars 模板 → 最終 System Prompt
    ▼
UnifiedChatClient.chat(request) (通過 Gateway)
```

### Skill 與 Agent 橋接

```
AgentSkillFunctionRegistrar (@PostConstruct)
    │
    ├── 掃描所有 Skill（skillLoader.listSkillNames()）
    ├── 每個 Skill → AgentSkillFunction (Function 包裝)
    │    ├── Function 名: "skill:{skillName}"
    │    ├── 輸入參數: userInput + sessionId
    │    └── 調用: skillRuntime.execute(skillName, ctx)
    └── 註冊到 FunctionRegistry
          │
          ▼
Agent 可透過 Function Calling 調用: "skill:summary"
```

---

## 六、提示詞系統（Prompt System）

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                      PromptCenter (SPI 門面)                      │
│                    DefaultPromptCenter                            │
│                                                                   │
│  ├── Fragment 管理 (可復用片段)                                   │
│  │   ├── getFragment(name, version)                               │
│  │   ├── getLatestFragment(name)                                  │
│  │   └── listFragments()                                          │
│  │                                                                │
│  ├── Template 管理 (獨立 Prompt)                                   │
│  │   ├── getTemplate(name, version)                               │
│  │   ├── getLatestTemplate(name)                                  │
│  │   └── listTemplates()                                          │
│  │                                                                │
│  └── Variable 管理                                                │
│      ├── getVariable(name)                                        │
│      └── listVariables()                                          │
└──────────────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                       存儲層 (YAML)                               │
│                                                                   │
│  prompts/templates/standalone/*.yaml  (獨立 Prompt 模板)         │
│  prompts/fragments/*.yaml            (可復用片段)                 │
│  prompts/variables/*.yaml            (變量定義)                   │
│                                                                   │
│  引用機制: {{> fragmentName}} 在 Skill .md 中引用 Fragment       │
└──────────────────────────────────────────────────────────────────┘
```

### Prompt 組裝過程

```
Skill 系統觸發
    │
    ▼
PromptComposer.compose(skillNames, ctx)
    │
    ├── 1. 加載所有 SkillManifest (YAML Frontmatter + Markdown Body)
    │
    ├── 2. 檢查 rag.enabled → 調用 EnhancedRagService.retrieve()
    │     └── 結果 → {{rag_context}} 變量
    │
    ├── 3. 檢查記憶需求 → MemoryRuntime.retrieve()
    │     └── 結果 → {{memory_context}} 變量
    │
    ├── 4. 注入 Function 工具描述 (FunctionRegistry.getDefinitions())
    │
    ├── 5. 渲染 Handlebars 模板
    │     取代 {{> fragment}} → 加載 Fragment 內容
    │     取代 {{variable}} → VariableResolver 解析
    │
    ├── 6. 合併各 Skill System Prompt (依賴順序)
    │
    └── 7. 返回最終 System Prompt → AI Gateway
```

### PromptTemplate / Fragment 結構

```yaml
# prompts/templates/standalone/summary.yaml
name: summary
version: 1.2.0
description: 新聞摘要生成
model: deepseek-chat
temperature: 0.3
maxTokens: 1024
template: |
  請對以下內容進行摘要：
  
  {{content}}
  
  要求：{{requirements}}
variables:
  - name: content
    type: string
    required: true
  - name: requirements
    type: string
    required: false
changelog:
  - version: 1.2.0
    date: 2026-07-01
    change: 優化摘要格式
```

---

## 七、AI Gateway + Filter Chain

### 架構圖

```
┌──────────────────────────────────────────────────────────────────┐
│                      UnifiedChatClient (SPI)                     │
│                                                                   │
│  chat(request) → GatewayResponse                                 │
│  chatStream(request) → Flux<StreamPacket>                        │
└──────────────────────────┬───────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────────┐
│                   GatewayFilterChainExecutor                      │
│                                                                   │
│  Filter Chain (按 order 排序):                                    │
│                                                                   │
│  10-19: 預處理層                                                   │
│  ├── HealthFilter        (健康檢查)                                │
│  ├── SensitiveWordFilter (敏感詞過濾)                              │
│  └── SemanticCacheFilter (語義緩存)                                │
│                                                                   │
│  20-29: 容錯層                                                    │
│  ├── RetryFilter         (重試: 最多3次)                           │
│  └── FallbackFilter      (降級)                                    │
│                                                                   │
│  30-39: 觀測層                                                    │
│  ├── MetricsFilter       (Micrometer 指標)                         │
│  ├── TraceFilter         (OpenTelemetry Span)                      │
│  └── CostFilter          (Token 費用計算)                          │
│                                                                   │
│  40-49: 後處理層                                                  │
│  ├── AuditLogFilter      (審計日誌)                                │
│  └── PromptInjectionFilter (Prompt注入檢測)                        │
│                                                                   │
│  ≥100: 終端                                                       │
│  └── TerminalFilter      (實際調用 LLM)                           │
└──────────────────────────────────────────────────────────────────┘
```

### Gateway 請求/響應模型

```java
GatewayRequest {
    messages: List<GatewayMessage>    // 消息列表
    systemPrompt: String              // 可選 System Prompt
    functionNames: Set<String>        // Function 白名單
    options: GatewayOptions           // 調用選項
    traceId: String                   // 鏈路追蹤 ID
    caller: String                    // 調用方標識
    promptTemplate: String            // Prompt 模板名
    promptVariables: Map              // Prompt 變量
}

GatewayResponse {
    content: String                   // LLM 文本
    toolCalls: List<ToolCall>         // Function Calling 結果
    tokenUsage: TokenUsage            // Token 用量
    model: String                     // 實際模型
    finishReason: String              // 結束原因
    latencyMs: long                   // 延遲
    ttftMs: long                      // 首 Token 延遲
    fallback: boolean                 // 是否降級
    retryCount: int                   // 重試次數
    cached: boolean                   // 是否命中緩存
}
```

### 流式協議（StreamPacket）

```json
{
  "type": "thinking | text | tool_call | complete | error",
  "content": "...",
  "taskId": "uuid",
  "seq": 1,
  "ts": 1234567890,
  "traceId": "trace-uuid"
}
```

---

## 八、執行引擎（ExecutionEngine）

```
ExecutionEngine (SPI)
  │
  ├── execute(ctx, task, options) → 同步阻塞
  ├── executeAsync(ctx, task, options) → CompletableFuture
  ├── executeParallel(ctx, tasks, options) → 並行執行
  ├── executeStream(ctx, StreamTask, options) → Flux 流
  └── executeWithRetry(ctx, task, options, retryPolicy) → 帶重試

ExecutionContext {
    traceId: String           // 鏈路追蹤 ID
    userId: Long              // 用戶 ID
    operation: String         // 操作名
    startTime: Instant        // 開始時間
}

ExecutionOptions {
    name: String              // 執行名稱
    timeout: Duration         // 超時
    tags: Map<String,String> // 標籤
}

ExecutionContextHolder:
  使用 ScopedValue 跨線程傳播 ExecutionContext
  確保 Filter Chain 中的 RetryFilter 等可獲取 traceId
```

---

## 九、全鏈路追蹤（Full-Link Tracing）

### 追蹤架構

```
┌──────────────────────────────────────────────────────────────────┐
│                         AiTracingService                         │
│                    (OpenTelemetry Tracer Wrapper)                 │
│                                                                   │
│  Span 命名規範:                                                   │
│  ai.router          - 意圖路由                                    │
│  ai.skill.execute   - Skill 執行                                  │
│  ai.agent.execute   - Agent 執行                                  │
│  ai.gateway.filter-chain - Gateway 過濾器鏈                       │
│  ai.gateway.llm     - LLM 調用                                    │
│  ai.memory.write    - Memory 寫入 (OtelMemoryTracer)              │
│  ai.memory.read     - Memory 讀取                                  │
│  ai.memory.consolidate - Memory 歸納                              │
│  ai.rag.retrieve    - RAG 檢索                                    │
│  ai.rag.eval        - RAG 評測                                    │
│  ai.function.{name} - Function 執行                               │
└──────────────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────▼────────────────────────────────────────┐
│                      TraceStore (SPI)                            │
│                                                                   │
│  TraceStorage (InMemory: 開發/調試)                              │
│  ElasticsearchTraceStore (生產: 持久化 + 查詢)                   │
│                                                                   │
│  方法:                                                           │
│  ├── store(span)              - 存儲 Span                        │
│  ├── getTraceSpans(traceId)   - 按 traceId 查詢                  │
│  ├── getRecentTraces(limit)   - 最近 Traces 列表                  │
│  └── size() / clear()         - 管理                             │
└──────────────────────────────────────────────────────────────────┘
```

### TraceSpan 結構

```java
TraceSpan {
    traceId: String       // 追蹤 ID（跨系統傳播）
    spanId: String        // Span ID
    parentSpanId: String  // 父 Span ID
    name: String          // Span 名
    kind: String          // INTERNAL | CLIENT | SERVER
    status: String        // OK | ERROR
    startTime: long       // 開始時間 (epoch nanos)
    endTime: long         // 結束時間
    durationMs: long      // 持續時間 (ms)
    attributes: Map       // 自定義屬性
}
```

### 完整的請求追蹤路徑

```
用戶請求
  │
  ▼
IntentRouter
  │ OTel Span: ai.router
  │ attributes: intent, confidence
  ▼
SkillRuntime.execute()
  │ OTel Span: ai.skill.execute
  │ attributes: skillNames, model, temperature
  │
  ├── PromptComposer.compose()
  │     │
  │     ├── RAG Context
  │     │     └── OTel Span: ai.rag.retrieve
  │     │         attributes: query, routeCount, durationMs
  │     │
  │     └── Memory Recall
  │           └── OTel Span: ai.memory.read
  │               attributes: memoryType, topK, hits
  │
  ├── UnifiedChatClient.chat()
  │     └── OTel Span: ai.gateway.filter-chain
  │           ├── RetryFilter
  │           ├── MetricsFilter
  │           ├── TraceFilter
  │           ├── CostFilter
  │           ├── AuditLogFilter
  │           └── TerminalFilter → OTel Span: ai.gateway.llm
  │
  └── Memory Store
        └── OTel Span: ai.memory.write (OtelMemoryTracer)
            attributes: memoryType, eventType

Agent 場景（額外路徑）:
  │
  ├── ReActLoop.PLAN
  │     └── OTel Span: ai.agent.plan
  ├── ReActLoop.ACT
  │     ├── Tool Step → OTel Span: ai.function.{name}
  │     └── LLM Step → OTel Span: ai.gateway.filter-chain
  ├── ReActLoop.OBSERVE
  │     └── OTel Span: ai.agent.observe
  └── ReActLoop.REFLECT
        └── OTel Span: ai.agent.reflect
```

### 記憶系統追蹤（MemoryTracing）

```
MemoryEventBus (事件總線)
    │
    ├── MemoryEvent
    │     ├── eventId: UUID
    │     ├── type: WRITE | READ | CAS_WRITE | DELETE | CONSOLIDATE | ERROR
    │     ├── memoryId: id
    │     ├── memoryType: WORKING | EPISODIC | SEMANTIC | PROCEDURAL
    │     ├── traceId: String
    │     └── data: Map (事件特有元數據)
    │
    ├── MemoryTracer (InMemory) — 調試端點查詢
    │     └── TraceRecord { eventType, memoryId, timestamp, data }
    │         按 traceId 聚合，最多保留 100 條/traceId
    │
    └── OtelMemoryTracer (OTel) — 生產環境
          └── OTel Span: ai.memory.{eventType}
              attributes: memory.traceId, memory.type, memory.id, memory.dataKeys

MemoryMetrics (Micrometer)
    ├── ai.memory.append.total / duration
    ├── ai.memory.read.total / duration
    ├── ai.memory.cache.hit / miss
    ├── ai.memory.cas.conflict
    ├── ai.memory.store.error
    ├── ai.memory.event.published
    ├── ai.memory.active.count (Gauge)
    └── ai.memory.cell.count (Gauge)
```

**設計原則**：
- `MemoryTracer` 與 `OtelMemoryTracer` 互補，並行監聽 MemoryEventBus
- MemoryTracer 提供 InMemory 調試查詢（`/api/agent/admin/memory/traces`）
- OtelMemoryTracer 輸出 OTel Span 供 Jaeger/Grafana Tempo 可視化
- 均通過 `MemoryEventListener` SPI 註冊，可獨立擴展
- `MemoryMetrics` 基於 Micrometer，支援 Prometheus/InfluxDB

---

## 十、RAG 評測（RAG Evaluation）

### 評測架構

```
RagEvaluationService
    │
    ├── RagEvaluationDataset (測試集)
    │     ├── name: String
    │     ├── queries: List<QueryCase>
    │     └── QueryCase { id, query, relevantDocIds }
    │
    ├── EnhancedRagService.retrieveDocuments()
    │     └── 被測系統
    │
    └── RagEvaluationMetrics (指標計算)
          ├── MRR (Mean Reciprocal Rank)
          │     = 1/rank_of_first_relevant
          ├── NDCG@K (Normalized Discounted Cumulative Gain)
          │     = DCG / IDCG
          ├── Recall@K
          │     = relevant_retrieved / total_relevant
          └── Precision@K
                = relevant_retrieved / K
```

### 記憶檢索評測

```
RetrievalEvaluator
    │
    ├── EvaluationDataset (測試集)
    ├── RetrievalPipeline (被測管道)
    ├── EvaluationMetric 列表:
    │     ├── MrrMetric
    │     ├── NdcgMetric
    │     ├── RecallAtKMetric
    │     └── PrecisionAtKMetric
    │
    └── RetrievalTraceCollector (溯源記錄)
          └── RetrievalTrace {
                traceId, query, knowledgeBase,
                timestamp, durationMs, retrieverType,
                topK, minScore, retrievedDocs,
                expectedDocIds, metricScores
              }
```

### 評測流程

```
Dataset                    Evaluation                     Report
┌──────────┐              ┌──────────────┐              ┌──────────┐
│QueryCase1│──query────▶  │EnhancedRag   │──retrieved──▶│ MRR: 0.85│
│  id=q1   │              │Service       │              │NDCG: 0.92│
│  query=..│              │              │              │Recall:0.6│
│  reldocs │──expected──▶ │Metrics       │──score─────▶ │Precis:0.8│
│  =[d1,d3]│              │Calculator    │              │          │
└──────────┘              └──────────────┘              └──────────┘
```

---

## 十一、文檔處理管道（Document Pipeline）

```
DocumentSource (SPI)
    │
    ├── ByteArrayDocumentSource (byte[])
    ├── InputStreamDocumentSource (InputStream)
    ├── MultipartFileDocumentSource (文件上傳)
    └── URL/OSS/S3 (未來)
    │
    ▼
DocumentInput (統一輸入模型)
    │
    ▼
Parser (解析器)
    │
    ├── ParserPhase: 文本提取
    └── 輸出: ParsedDocument / DocumentAST
    │
    ▼
ChunkSplitter (切分器)
    │
    ├── V1.0: ParagraphChunkSplitter (段落)
    ├── V1.0: HeadingChunkSplitter (標題)
    └── V2.0: ASTHeadingChunkSplitter (AST 結構化)
    │
    ├── 輸出 V1.0: DocumentSegment[]
    └── 輸出 V2.0: DocumentChunk[] (引用 AST Node)
    │
    ▼
DocumentProcessor (處理器)
    │ 按 order() 升序執行
    │ 操作 DocumentAST
    ▼
DocumentIngestor (索引)
    │
    ├── WkcDocument → TextSegment → Embedding → EmbeddingStore.add()
    └── WkcDocument { docId, title, content, metadata }
```

---

## 十二、整合後完整架構

```
┌──────────────┐    ┌─────────────────────────────────────────────────────┐
│   外部       │    │                    open-platform                      │
│   請求       │    │                                                      │
│ (HTTP/WS)   │    │  ┌─────────────────────────────────────────────────┐ │
└──────┬───────┘    │  │              Skill Runtime                       │ │
       │            │  │  IntentRouter → SkillSelector → DependencyResolver│ │
       ▼            │  │  → PromptComposer → UnifiedChatClient            │ │
┌──────────────┐    │  └───────────────────────┬─────────────────────────┘ │
│ wkc-gateway  │    │                          │                          │
│ (路由+JWT)   │    │  ┌───────────────────────▼─────────────────────────┐ │
└──────┬───────┘    │  │              Agent Runtime                       │ │
       │            │  │  AiAgentFactory → ReActLoop                      │ │
       ▼            │  │  (Plan→Act→Observe→Reflect)                      │ │
┌──────────────┐    │  │    ├── MemoryBridge ──▶ MemoryRuntime            │ │
│  Controller  │    │  │    ├── RAGBridge ──────▶ EnhancedRagService     │ │
│  Layer       │    │  │    └── SkillFunction ──▶ SkillRuntime            │ │
└──────┬───────┘    │  └───────────────────────┬─────────────────────────┘ │
       │            │                          │                          │
       ▼            │  ┌───────────────────────▼─────────────────────────┐ │
┌──────────────┐    │  │              AI Gateway                          │ │
│ wkc-ai-     │    │  │  UnifiedChatClient                               │ │
│ platform    │────▶│  │  └─ GatewayFilterChain                          │ │
│ (即本模組)   │    │  │      (Health→Retry→Metrics→Trace→Cost→Audit)    │ │
└──────────────┘    │  │  └─ StreamPacket (SSE 流式)                     │ │
                    │  └───────────────────────┬─────────────────────────┘ │
                    │                          │                          │
                    │  ┌───────────────────────▼─────────────────────────┐ │
                    │  │              Execution Engine                    │ │
                    │  │  async/parallel/timeout/retry/stream             │ │
                    │  │  ExecutionContextHolder (ScopedValue)            │ │
                    │  └───────────────────────┬─────────────────────────┘ │
                    │                          │                          │
                    │  ┌──────────┬────────────┼────────────┬───────────┐ │
                    │  │          │            │            │           │ │
                    │  ▼          ▼            ▼            ▼           ▼ │
                    │ ┌──────┐ ┌──────┐ ┌──────────┐ ┌────────┐ ┌─────┐ │
                    │ │Memory│ │ RAG  │ │Document  │ │Function│ │Skill│ │
                    │ │System│ │System│ │Pipeline  │ │Registry│ │ .md │ │
                    │ └──────┘ └──────┘ └──────────┘ └────────┘ └─────┘ │
                    │                                                      │
                    │  ┌─────────────────────────────────────────────────┐ │
                    │  │           Observability Layer                   │ │
                    │  │  AiTracingService (OTel) | TraceStore           │ │
                    │  │  MemoryTracer | OtelMemoryTracer | MemoryMetrics│ │
                    │  │  RagEvaluationService | RetrievalEvaluator      │ │
                    │  └─────────────────────────────────────────────────┘ │
                    └─────────────────────────────────────────────────────┘
```

---

## 十三、核心數據流（以用戶請求為例）

### 場景：用戶發送「總結今天的新聞」

```
1. HTTP 請求 → wkc-gateway (JWT 鑒權)
       │
2. open-platform Controller
       │
3. IntentRouter
   ├── 識別意圖: "summarize"
   └── OTel Span: ai.router
       │
4. SkillSelector (IntentSkillSelector)
   └── 映射: "summarize" → ["news-summary"]
       │
5. SkillRuntime.execute(["news-summary"], ctx)
       │
6. PromptComposer.compose()
   ├── 加載 news-summary.md Frontmatter + Body
   ├── 調用 EnhancedRagService.retrieve(query)
   │    ├── QueryRewriter.rewrite()
   │    ├── VectorRetriever.retrieve() (ES)
   │    ├── BM25Retriever.retrieve() (ES)
   │    ├── RRF Fusion
   │    ├── LLM Reranker
   │    └── Context Assembly
   ├── 調用 MemoryRuntime.retrieve() (歷史記憶)
   ├── 注入 Function 工具描述
   └── 渲染 Handlebars → 最終 System Prompt
       │
7. UnifiedChatClient.chat(request)
   └── GatewayFilterChain:
       ├── RetryFilter (出錯重試)
       ├── MetricsFilter (指標記錄)
       ├── TraceFilter (OTel Span)
       ├── CostFilter (費用計算)
       ├── AuditLogFilter (審計日誌)
       └── TerminalFilter → LLM API 調用
       │
8. 結果返回 → GatewayResponse
   └── OTel Span: ai.gateway.filter-chain (完整調用鏈)
```

---

## 十四、包結構總覽

```
com.yin.ai.platform
├── agent/             # Agent 系統 (SPI + 實現)
│   ├── config/        # Spring 配置
│   ├── executor/      # Agent 步驟執行器
│   ├── integration/   # Agent↔Skill 橋接
│   ├── memory/        # Agent↔Memory 橋接
│   ├── observer/      # 結果觀察 SPI
│   ├── planner/       # 任務規劃 SPI
│   ├── rag/           # Agent↔RAG 橋接
│   ├── reflector/     # 反思決策 SPI
│   └── runtime/       # Agent Runtime 實現
├── config/            # 全局配置
├── context/           # 請求上下文
├── document/          # 文檔處理管道
│   ├── cleaner/       # 文檔清洗
│   ├── model/ast/     # AST 模型
│   ├── model/chunk/   # Chunk 模型
│   ├── parser/        # 解析器
│   ├── processor/     # 文檔處理器
│   ├── runtime/       # 管道調度
│   ├── source/        # 文檔來源 SPI
│   └── splitter/      # 切分器 SPI
├── engine/            # 執行引擎
│   ├── bridge/        # 引擎橋接
│   └── config/        # 引擎配置
├── exception/         # 異常定義
├── filter/            # 通用過濾器
├── function/          # Function 框架
│   ├── adapter/       # Function 適配器
│   └── config/        # Function 配置
├── gateway/           # AI Gateway
│   ├── adapter/       # 模型適配器
│   ├── audit/         # 審計
│   ├── cache/         # 響應緩存
│   ├── config/        # Gateway 配置
│   ├── cost/          # 費用計算
│   ├── fallback/      # 降級策略
│   ├── filter/        # 過濾器鏈
│   ├── guard/         # 安全防護
│   ├── health/        # 健康檢查
│   ├── model/         # 模型管理
│   ├── router/        # 意圖路由
│   └── stream/        # 流式協議
├── knowledge/         # 知識庫
├── legacy/            # 舊版兼容
├── loader/            # Skill 加載器
├── memory/            # 記憶系統
│   ├── api/impl/      # Runtime 門面
│   ├── audit/         # 記憶審計
│   ├── cache/         # 記憶緩存
│   ├── compression/   # 壓縮策略
│   ├── config/        # 記憶配置
│   ├── consolidation/ # STM→LTM 歸納
│   ├── core/          # 核心模型
│   ├── cost/          # 記憶成本
│   ├── degradation/   # 降級策略
│   ├── episodic/      # 情節記憶
│   ├── evaluation/    # 記憶檢索評測
│   ├── event/         # 事件系統
│   ├── integration/   # 記憶整合
│   ├── lifecycle/     # 生命週期
│   ├── migration/     # 遷移策略
│   ├── multiagent/    # 多 Agent 記憶共享
│   ├── observe/       # 記憶觀測
│   ├── policy/        # 策略聚合
│   ├── procedural/    # 程序記憶
│   ├── retrieval/     # 檢索管道
│   ├── security/      # 安全策略
│   ├── semantic/      # 語義記憶
│   ├── session/       # Session 記憶
│   ├── store/adapter/ # 存儲適配器
│   ├── temporal/      # 時間管理
│   └── working/       # 工作記憶
├── observability/     # 可觀測性
├── observe/           # 觀測基礎設施
│   ├── tracing/       # 追蹤系統
│   └── config/        # 觀測配置
├── prompt/            # 提示詞系統
│   ├── model/         # 提示詞模型
│   ├── store/         # 提示詞存儲
│   └── config/        # 提示詞配置
├── rag/               # RAG 系統
│   ├── enhanced/      # 增強型 RAG
│   ├── evaluation/    # RAG 評測
│   └── config/        # RAG 配置
├── router/            # 路由系統
│   ├── cache/         # 路由緩存
│   ├── config/        # 路由配置
│   ├── handler/       # 路由處理器
│   ├── matcher/       # 路由匹配器
│   └── query/         # 路由查詢
├── runtime/           # Runtime 層
│   ├── adapter/       # Runtime 適配器
│   ├── config/        # Runtime 配置
│   └── postprocess/   # 後處理器
├── scheduler/         # 定時任務
└── skill/             # Skill 系統
    ├── composer/      # Prompt 組裝器
    ├── config/        # Skill 配置
    ├── repository/    # Skill 存儲庫
    ├── resolver/      # 依賴解析器
    └── selector/      # Skill 選擇器
```
