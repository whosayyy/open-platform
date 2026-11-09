# 消费者示例：代码库问答助手

这是 `open-platform` 的**第二个独立消费者**，领域与新闻业务无关。

它存在的目的不是"演示"，而是**验收**：

> 平台的抽象是否只在单一业务场景下成立？

一个库是否可用，唯一可信的判据是"第二个消费者能否不改库就接入"。宿主应用做不到这件事——它和平台是同一套假设。这个示例换了一个领域（开发者工具），因此能暴露出被单一场景定形的 API。

## 它证明了什么

- 业务代码只依赖平台的 **2 个包**：`quickstart`（门面）和 `core.tool`（工具 SPI）
- 不依赖 Spring、不依赖任何 ORM、不依赖任何业务模块
- 整个助手就是一个 prompt + 一个工具 + 一次调用（见 `CodeQaAssistant`）
- **工具调用闭环真的工作**：模型返回 `toolCalls` → 平台执行 `search_code` → 结果回灌 → 模型给出最终答案。测试覆盖了这条路径
- 离线可跑：默认 STUB 模型，CI 上不需要 API key

## 怎么跑

### Windows cmd（推荐，最省事）

仓库里带了一个批处理脚本，会自动读项目根目录 `.env`、拼好 classpath、开启 preview：

```cmd
:: 在项目根目录（open-platform 根）一次性准备
mvn install -DskipTests
mvn -f examples/code-qa/pom.xml package -DskipTests

:: 跑内置演示（3 个问题）
examples\code-qa\run.cmd

:: 单次提问
examples\code-qa\run.cmd "RAG 的向量检索走的哪个存储？"

:: 走离线模型，不消耗额度
set CODE_QA_MODE=stub
examples\code-qa\run.cmd
```

`run.cmd` 支持的环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `CODE_QA_MODE` | `live` | 设为 `stub` 走离线确定性模型 |
| `CODE_QA_DEBUG` | 未设 | 设为 `true` 打印完整请求/响应体 |
| `CODE_QA_CONNECT_TIMEOUT` | `20` | 连接超时（秒） |
| `CODE_QA_RETRY` | `2` | 连接类故障的重试次数 |

### Maven 方式

```bash
# 跑测试 + 内置演示（离线确定性，CI 用这个）
mvn -f examples/code-qa/pom.xml verify

# 单次提问
mvn -f examples/code-qa/pom.xml exec:java \
    -Dexec.mainClass=com.yin.examples.codeqa.CodeQaCli \
    -Dexec.args="Agent 的超时是怎么实现的？"
```

## 接真实模型

需要三个环境变量（与项目的 `.env` 同名，可直接复用）：

| 变量 | 示例 | 说明 |
|---|---|---|
| `CODE_QA_MODE` | `live` | 不设或非 live 时走离线 STUB |
| `AI_API_KEY` | `sk-...` | 必填 |
| `AI_BASE_URL` | `https://api.deepseek.com` | 兼容 `/v1` 后缀，不会出现双 `/v1` |
| `AI_MODEL` | `deepseek-v4-flash` | 可选，默认 `deepseek-chat` |

用 `run.cmd` 时这些会从根目录 `.env` 自动读取，不需要手动 export。

### 真实模型下的实测行为

`deepseek-v4-flash` 实测跑通完整多轮工具闭环（一次提问共 4 次模型调用）：

1. 模型收到问题 + `search_code` 的 JSON schema
2. **自主决定**并行发起 2 个 `search_code` 调用（`finish_reason=tool_calls`）
3. 平台执行工具、把结果作为 `role=tool` 消息回灌
4. 模型基于检索结果生成带引用的最终答案（`finish_reason=stop`）

问「这个代码库支持 GraphQL 吗？」时，模型检索后主动区分了
「知识库无命中」与「代码库绝对不存在」，并指出某次检索属于关键词误命中 ——
这正是提示词里"不要猜测"要求的预期行为。

## 业务开发者的代码量

整个助手的核心就是这些（`CodeQaAssistant`）：

```java
ToolRegistry tools = new DefaultToolRegistry();
tools.register(snippets.asTool());
ModelClients.register(ModelProviders.create(mode, snippets, tools));

Agent agent = Agent.of("代码库问答")
        .prompt(SYSTEM_PROMPT)
        .tools(tools)
        .build();

Result result = agent.run(question);
```

业务侧不需要接触：`ExecutionContext`、`traceId`、`AgentRequest`、`AgentResult`、
`AgentRun`、`AgentMode`、`DefaultAgentRuntime`。

## 接入真实模型需要写什么

只有 `ModelProviders.OpenAiCompatibleClient` —— 实现 `ModelClient` 的两个方法。
约 150 行，其中大部分是把平台模型翻译成 OpenAI 的 JSON。

这就是消费方需要承担的"脏活"总量。

---

# 构建这个示例时发现的平台缺口

按发现顺序记录。这些不是示例的 bug，是**平台**的问题被第二个消费者逼出来了。

## 1. 注册了工具但工具被静默丢弃（已修）

`Agent.Builder.tools(...)` 注册了工具，但门面默认路由到 `DIRECT` 模式，
而 `DirectAgentPattern` **完全不处理模型返回的 `toolCalls`**。

后果：开发者写了 `.tools(tools)`，工具永远不被调用，**没有任何错误或警告**。

- 修复：`Agent` 在工具注册表非空时显式路由到 `REACT`
- 位置：`src/main/java/com/yin/ai/platform/quickstart/Agent.java` 的 `routingMetadata()`

## 2. `ModelRequest` 不携带工具参数 schema —— **已修复**

原问题：`ModelRequest.allowedTools()` 是 `List<String>`——**只有工具名，没有 schema**：

```java
// 修复前
public record ModelRequest(
        List<ModelMessage> messages,
        ModelOptions options,
        List<String> allowedTools,   // ← 只有名字
        Map<String, Object> metadata
)
```

而 `ToolDefinition` 是有 `inputSchema` 的。这意味着**消费方的模型适配器无法把工具的参数结构
告诉模型**，除非它自己额外持有 `ToolRegistry`。本示例最初就是这么绕过去的。

**修复方式**（选用加法而非破坏性改动，避免打断已有调用点）：

```java
// 修复后
public record ModelRequest(
        List<ModelMessage> messages,
        ModelOptions options,
        List<String> allowedTools,        // 兼容字段，可从 definitions 自动派生
        List<ToolDefinition> toolDefinitions,  // ← 新增：含 inputSchema
        Map<String, Object> metadata
)
```

配套改动：

- 保留 4 参数兼容构造，现有调用点无需修改
- 只传 `toolDefinitions` 时 `allowedTools` **自动派生**，避免两处不一致
- `PatternContext.callModel()` 从 `ToolRegistry` 填充完整定义，所有 pattern 自动受益
- 新增 `hasToolDefinitions()` 与 `toolDefinition(name)` 便捷方法

**效果**：本示例的适配器**不再需要持有 `ToolRegistry`**（构造参数已删除），
直接用 `request.toolDefinitions()` 就能生成 OpenAI 的 `tools` 字段。
实测发往 DeepSeek 的请求体已携带完整 schema：

```json
"tools":[{"type":"function","function":{"name":"search_code",
  "description":"Search the local code snippet knowledge base by keyword. ...",
  "parameters":{"required":["query"],"type":"object",
    "properties":{"query":{"type":"string",
      "description":"Keywords to search for, e.g. 'retry timeout'"}}}}]}]
```

回归测试见 `ToolDefinitionPropagationTest`（4 例），锁定的契约是
**平台编排调用模型时必须提供完整工具定义**。


## 3. 门面无法显式选择编排模式（已绕过）

`Agent` 没有暴露模式选择。本示例通过在 `AgentRequest.metadata` 里塞
`forcedMode=REACT` 绕过（平台内部的 `DefaultAgentPatternRouter` 认识这个 key）。

问题在于：这是**内部约定**，通过 metadata 字符串传递，没有类型安全，
文档里也没有承诺它是公开契约。业务开发者要么研究源码，要么用不了。

- 建议：门面提供 `.mode(AgentMode)`，或明确文档化 `forcedMode` 为公开契约

## 4. 平台的 optional 依赖不会传递给消费方（非缺陷，但需知悉）

平台把 Jackson / Spring / Redis 都声明为 `<optional>true</optional>`。
这是**正确的库设计**，但意味着消费方必须显式声明自己真正用到的东西。
本示例因此需要自己声明 `jackson-databind`。

这不是 bug，但 README 应该说明，否则新接入者会困惑于
"为什么平台的 JSON 工具类在我这里编译不过"。

## 5. preview 特性约束会传导给消费方（需知悉）

平台使用 Java 21 的 `ScopedValue` / `StructuredTaskScope`，
产物必须开启 preview 才能加载。因此**任何消费方的 JVM 也必须带 `--enable-preview`**，
包括 surefire 的 `argLine` 和 `exec:java`。

这是一个很重的约束：它把消费方锁定在 JDK 21，且每次升级 JDK 都要重新验证。
本示例的 `pom.xml` 里为此加了两处配置。

- 建议：在平台 README 的"生产使用检查清单"里明确写出这一点

## 6. OpenAI 协议的 tool_calls 回传要求，平台不会替适配器处理（已修）

多轮工具调用时，OpenAI 兼容协议要求 `role=tool` 的消息**前置一条声明了对应
`tool_call_id` 的 assistant 消息**，否则服务端返回 400：

```
Messages with role 'tool' must be a response to a preceding message with 'tool_calls'
```

平台把 `toolCalls` 放在 assistant 消息的 **metadata**（`ModelMessage.metadata().get("toolCalls")`）
里传下来，而不是放在消息内容或专门的字段上。**模型适配器必须自己读取并回传**，
这一步没有任何类型约束或文档提示，不写就会在真实模型上直接 400 —— 而 STUB 模型
永远不会暴露这个问题（它不走 HTTP、不校验协议）。

本示例在 `ModelProviders.toMessages()` 里处理了这件事。

- 建议：在 `ModelClient` 的接口 javadoc 里写明"实现方必须回传 assistant 消息的
  `toolCalls` metadata 以满足 OpenAI 系协议"，或在 `ModelMessage` 上加一个显式的
  `toolCalls` 字段，让编译器帮忙而不是靠文档

## 7. 真实模型会并行发起多个工具调用，平台已支持但需适配器配合（非缺陷）

实测 `deepseek-v4-flash` 一次响应里返回了 2 个 `search_code` 调用。
`ReActAgentPattern` 用 for 循环顺序执行它们并各自追加 `role=tool` 消息，
行为正确；但适配器必须保证**每个 tool_call_id 都有对应的一条 tool 消息**，
否则同样会触发上面第 6 条的 400。

