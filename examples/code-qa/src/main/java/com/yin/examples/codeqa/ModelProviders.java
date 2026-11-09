package com.yin.examples.codeqa;

import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.ai.platform.core.model.ModelCall;
import com.yin.ai.platform.core.model.ModelClient;
import com.yin.ai.platform.core.model.ModelEvent;
import com.yin.ai.platform.core.model.ModelEventListener;
import com.yin.ai.platform.core.model.ModelMessage;
import com.yin.ai.platform.core.model.ModelRequest;
import com.yin.ai.platform.core.model.ModelResponse;
import com.yin.ai.platform.core.model.ModelRole;
import com.yin.ai.platform.core.model.ModelToolCall;
import com.yin.ai.platform.core.model.ModelUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 模型提供方实现 —— 消费方唯一需要写的"脏活"。
 * <p>
 * 这个类展示了接入一个 OpenAI 兼容服务的最小代价：只实现
 * {@link ModelClient} 的两个方法，平台其余部分（Agent 编排、工具调用闭环、
 * 事件、状态、成本与审计边界）都不需要碰。
 * <p>
 * 刻意不引入任何 HTTP 客户端依赖：用 JDK 自带的 {@link HttpClient}。
 */
public final class ModelProviders {

    private ModelProviders() {
    }

    /** 运行模式。 */
    public enum Mode {
        /** 确定性本地模型：离线可跑，CI 用这个。 */
        STUB,
        /** 真实 OpenAI 兼容服务。 */
        LIVE
    }

    /** 从环境变量推断模式，默认 STUB 以保证离线可用。 */
    public static Mode modeFromEnv() {
        String mode = System.getenv("CODE_QA_MODE");
        if (mode != null && mode.equalsIgnoreCase("live")) {
            return Mode.LIVE;
        }
        return Mode.STUB;
    }

    /**
     * 创建模型客户端。
     * <p>
     * 注意这里不需要 ToolRegistry：平台修复后，{@code ModelRequest} 直接携带
     * 完整的 {@code ToolDefinition}（含参数 schema），适配器无需自己持有注册表。
     * 这正是"消费方只关注业务"的应有形态。
     */
    public static ModelClient create(Mode mode, SnippetStore store) {
        return mode == Mode.LIVE ? new OpenAiCompatibleClient() : new StubCodeModelClient();
    }

    // ---------------------------------------------------------------- STUB

    /**
     * 确定性本地模型：把"模型决定调用工具"这一步也模拟出来，
     * 这样 {@code search_code} 的完整闭环可以在 CI 上被验证。
     * <p>
     * 行为：
     * <ol>
     *   <li>第一次被问到问题 → 返回一个 {@code search_code} 工具调用</li>
     *   <li>收到工具结果后 → 基于工具结果给出回答</li>
     * </ol>
     * 真实模型也是这个流程，只是决策由模型自己做。
     */
    static final class StubCodeModelClient implements ModelClient {

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            List<ModelMessage> messages = request.messages();
            boolean hasToolObservation = messages.stream()
                    .anyMatch(m -> m.role() == ModelRole.TOOL);
            if (hasToolObservation) {
                String observation = messages.get(messages.size() - 1).content();
                return new ModelResponse(
                        "根据代码库检索结果：\n\n" + observation
                                + "\n\n(由本地 stub 模型生成，未调用真实模型服务)",
                        "stop", ModelUsage.empty(), Map.of("provider", "stub"));
            }

            String question = lastUserMessage(messages);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("query", extractKeywords(question));
            return new ModelResponse(
                    "",
                    "tool_calls",
                    ModelUsage.empty(),
                    Map.of("provider", "stub"),
                    List.of(new ModelToolCall("call-1", "search_code", arguments)));
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            ModelResponse response = execute(request, context);
            if (!response.content().isEmpty()) {
                listener.onEvent(new ModelEvent("text", response.content(), Map.of()));
            }
            return new StubCall(response);
        }

        private static String lastUserMessage(List<ModelMessage> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role() == ModelRole.USER) {
                    return messages.get(i).content();
                }
            }
            return "";
        }

        /** 极简关键词抽取：只用来驱动示例的检索，真实实现应由模型决定。 */
        private static String extractKeywords(String question) {
            if (question == null || question.isBlank()) {
                return "agent";
            }
            String cleaned = question.replaceAll("[？?。，,！!；;：:]", " ").trim();
            String[] tokens = cleaned.split("\\s+");
            return tokens.length > 0 ? tokens[tokens.length - 1] : cleaned;
        }
    }

    private static final class StubCall implements ModelCall {

        private final ModelResponse response;

        StubCall(ModelResponse response) {
            this.response = response;
        }

        @Override
        public java.util.concurrent.CompletionStage<ModelResponse> completion() {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }

    // ---------------------------------------------------------------- LIVE

    /**
     * OpenAI 兼容服务的真实实现（默认 DeepSeek 端点）。
     * <p>
     * 环境变量：{@code AI_API_KEY}、{@code AI_BASE_URL}、{@code AI_MODEL}、
     * {@code CODE_QA_CONNECT_TIMEOUT}（秒，默认 20）、{@code CODE_QA_RETRY}（默认 2）。
     * <p>
     * 自带连接重试是刻意的：真实网络会出现瞬时 connect timeout，
     * 而平台的引擎级重试需要注入 {@code ExecutionEngine} 才能启用，
     * 门面默认不装配它。消费方在自己的适配器里兜住这一层最直接。
     */
    static final class OpenAiCompatibleClient implements ModelClient {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(intEnv("CODE_QA_CONNECT_TIMEOUT", 20)))
                .build();

        private final int maxAttempts = Math.max(1, intEnv("CODE_QA_RETRY", 2));

        private final String apiKey = env("AI_API_KEY", "");
        private final String baseUrl = env("AI_BASE_URL", "https://api.deepseek.com");
        private final String model = env("AI_MODEL", "deepseek-chat");

        @Override
        public ModelResponse execute(ModelRequest request, ExecutionContext context) {
            if (apiKey.isBlank()) {
                throw new IllegalStateException(
                        "CODE_QA_MODE=live requires AI_API_KEY to be set");
            }
            IllegalStateException lastFailure = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return callOnce(request);
                } catch (IllegalStateException e) {
                    lastFailure = e;
                    // 只重试连接类故障；HTTP 4xx 之类的确定性错误重试没有意义
                    if (!isTransient(e) || attempt == maxAttempts) {
                        throw e;
                    }
                    System.out.println("[WARN] 第 " + attempt + " 次调用失败（" + e.getMessage()
                            + "），重试中...");
                    sleepQuietly(500L * attempt);
                }
            }
            throw lastFailure != null ? lastFailure
                    : new IllegalStateException("model call failed without a recorded error");
        }

        /** 真实网络抖动表现为 connect/resolve 超时，值得重试。 */
        private static boolean isTransient(IllegalStateException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            return message.contains("timed out")
                    || message.contains("Connection reset")
                    || message.contains("GOAWAY")
                    || message.contains("HTTP 429")
                    || message.contains("HTTP 502")
                    || message.contains("HTTP 503")
                    || message.contains("HTTP 504");
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model call interrupted", interrupted);
            }
        }

        private ModelResponse callOnce(ModelRequest request) {
            try {
                ObjectNode payload = MAPPER.createObjectNode();
                payload.put("model", request.options().model() != null
                        ? request.options().model() : model);
                payload.put("stream", false);
                payload.set("messages", toMessages(request.messages()));
                if (request.hasToolDefinitions()) {
                    payload.set("tools", toTools(request.toolDefinitions()));
                } else if (!request.allowedTools().isEmpty()) {
                    // 兼容只给了工具名的请求：schema 未知，模型只能猜参数
                    payload.set("tools", toToolsByName(request.allowedTools()));
                }

                String body = MAPPER.writeValueAsString(payload);
                if (debug()) {
                    System.out.println("[DEBUG] request body = " + body);
                }

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint()))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = http.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (debug()) {
                    System.out.println("[DEBUG] response body = " + response.body());
                }
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("model provider returned HTTP "
                            + response.statusCode() + ": " + truncate(response.body()));
                }
                return parse(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model call interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("model call failed: " + e.getMessage(), e);
            }
        }

        @Override
        public ModelCall submit(ModelRequest request, ExecutionContext context,
                                ModelEventListener listener) {
            // 示例只演示同步路径；流式接入方式与 execute 相同，只是按 SSE 逐行解析。
            throw new UnsupportedOperationException(
                    "streaming is not implemented in this example; use execute()");
        }

        /**
         * 把平台消息翻译成 OpenAI 兼容消息。
         * <p>
         * 关键点：当 assistant 消息带有 {@code toolCalls} 元数据时，必须把 {@code tool_calls}
         * 原样回传。OpenAI 协议要求 role=tool 的消息前置一条声明了对应 tool_call_id 的
         * assistant 消息，否则服务端会返回 400。平台的 {@code ReActAgentPattern} 正是把
         * toolCalls 放在 assistant 消息的 metadata 里传下来的。
         */
        private static ArrayNode toMessages(List<ModelMessage> messages) {
            ArrayNode array = MAPPER.createArrayNode();
            for (ModelMessage message : messages) {
                ObjectNode node = array.addObject();
                node.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
                node.put("content", message.content() == null ? "" : message.content());

                if (message.role() == ModelRole.ASSISTANT) {
                    appendToolCalls(node, message);
                }
                if (message.role() == ModelRole.TOOL) {
                    if (message.name() != null) {
                        node.put("name", message.name());
                    }
                    Object toolCallId = message.metadata().get("toolCallId");
                    if (toolCallId != null) {
                        node.put("tool_call_id", toolCallId.toString());
                    }
                }
            }
            return array;
        }

        /** 回传 assistant 的 tool_calls，并允许 content 为空（协议允许）。 */
        private static void appendToolCalls(ObjectNode node, ModelMessage message) {
            Object raw = message.metadata().get("toolCalls");
            if (!(raw instanceof List<?> calls) || calls.isEmpty()) {
                return;
            }
            ArrayNode toolCalls = node.putArray("tool_calls");
            for (Object item : calls) {
                if (!(item instanceof ModelToolCall call)) {
                    continue;
                }
                ObjectNode entry = toolCalls.addObject();
                entry.put("id", call.id() == null ? "call-0" : call.id());
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", call.name());
                function.put("arguments", writeArguments(call.arguments()));
            }
            if (message.content() == null || message.content().isEmpty()) {
                node.putNull("content");
            }
        }

        private static String writeArguments(Map<String, Object> arguments) {
            try {
                return MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
            } catch (Exception e) {
                return "{}";
            }
        }

        /**
         * 把平台工具定义翻译成 OpenAI 的 function 定义。
         * <p>
         * 直接用 {@code request.toolDefinitions()} 里的 schema，不需要自己持有
         * {@code ToolRegistry}——这正是平台修复后适配器的样子。
         */
        private ArrayNode toTools(List<com.yin.ai.platform.core.tool.ToolDefinition> definitions) {
            ArrayNode array = MAPPER.createArrayNode();
            for (com.yin.ai.platform.core.tool.ToolDefinition definition : definitions) {
                ObjectNode function = MAPPER.createObjectNode();
                function.put("name", definition.name());
                function.put("description", definition.description());
                ObjectNode schema = function.putObject("parameters");
                if (!definition.inputSchema().isEmpty()) {
                    schema.setAll((ObjectNode) MAPPER.valueToTree(definition.inputSchema()));
                } else {
                    schema.put("type", "object");
                    schema.putObject("properties");
                }
                ObjectNode wrapper = array.addObject();
                wrapper.put("type", "function");
                wrapper.set("function", function);
            }
            return array;
        }

        /**
         * 兼容路径：请求只给了工具名。schema 未知，只能告诉模型一个空参数对象。
         * <p>
         * 手工构造的 {@code ModelRequest} 会走到这里；平台内部编排总是提供完整定义。
         */
        private ArrayNode toToolsByName(List<String> names) {
            ArrayNode array = MAPPER.createArrayNode();
            for (String name : names) {
                ObjectNode function = MAPPER.createObjectNode();
                function.put("name", name);
                function.put("description", "Platform tool: " + name);
                ObjectNode schema = function.putObject("parameters");
                schema.put("type", "object");
                schema.putObject("properties");
                ObjectNode wrapper = array.addObject();
                wrapper.put("type", "function");
                wrapper.set("function", function);
            }
            return array;
        }

        private static ModelResponse parse(String body) throws Exception {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("stop");

            List<ModelToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = MAPPER.readValue(
                        function.path("arguments").asText("{}"), Map.class);
                toolCalls.add(new ModelToolCall(
                        call.path("id").asText("call-" + toolCalls.size()),
                        function.path("name").asText(),
                        arguments));
            }

            JsonNode usage = root.path("usage");
            return new ModelResponse(
                    content,
                    finishReason,
                    new ModelUsage(usage.path("prompt_tokens").asLong(),
                            usage.path("completion_tokens").asLong(),
                            usage.path("total_tokens").asLong()),
                    Map.of("provider", "openai-compatible", "model", root.path("model").asText("")),
                    toolCalls);
        }

        /**
         * 拼出 chat completions 端点。
         * <p>
         * 兼容两种写法：{@code https://api.deepseek.com} 与
         * {@code https://xxx/v1}，避免出现 {@code /v1/v1/} 或双斜杠。
         */
        private String endpoint() {
            String base = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base.endsWith("/v1")
                    ? base + "/chat/completions"
                    : base + "/v1/chat/completions";
        }

        /** 打开后会打印请求与响应体，便于排查协议问题。 */
        private static boolean debug() {
            return "true".equalsIgnoreCase(System.getenv("CODE_QA_DEBUG"));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static int intEnv(String name, int fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static String truncate(String value) {
            if (value == null) {
                return "";
            }
            return value.length() <= 300 ? value : value.substring(0, 300) + "...";
        }
    }
}
