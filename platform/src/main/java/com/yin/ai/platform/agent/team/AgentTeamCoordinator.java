package com.yin.ai.platform.agent.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.AgentConfig;
import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentTask;
import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.TaskEventType;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.AgentMode;
import com.yin.ai.platform.agent.pattern.Artifact;
import com.yin.ai.platform.agent.task.ContextStore;
import com.yin.ai.platform.agent.task.TaskCreateRequest;
import com.yin.ai.platform.agent.task.TaskEngine;
import com.yin.ai.platform.agent.task.TaskHandle;
import com.yin.ai.platform.agent.task.TaskState;
import com.yin.ai.platform.agent.task.TaskTokenAccountant;
import com.yin.ai.platform.agent.task.TaskTokenBudget;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.gateway.GatewayTextClient;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Coordinates a bounded, application-configured team on top of the unified task runtime. */
public class AgentTeamCoordinator {

    public static final long TEAM_TOKEN_BUDGET = 120_000;
    public static final Duration TEAM_TIMEOUT = Duration.ofMinutes(8);

    private final GatewayTextClient chatClient;
    private final FunctionRegistry functionRegistry;
    private final TaskEngine taskEngine;
    private final ContextStore contextStore;
    private final TaskTokenAccountant tokenAccountant;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AgentTeamCoordinator(GatewayTextClient chatClient, FunctionRegistry functionRegistry,
                                TaskEngine taskEngine, ContextStore contextStore,
                                TaskTokenAccountant tokenAccountant, ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.functionRegistry = functionRegistry;
        this.taskEngine = taskEngine;
        this.contextStore = contextStore;
        this.tokenAccountant = tokenAccountant;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void execute(AgentExecutionContext parent) {
        long deadline = System.nanoTime() + TEAM_TIMEOUT.toNanos();
        if (parent.getState() == TaskState.CREATED) parent.transitionTo(TaskState.PLANNING);
        if (parent.getState() == TaskState.FAILED) parent.transitionTo(TaskState.RUNNING);
        tokenAccountant.setBudget(parent.getTaskId(), new TaskTokenBudget(
                TEAM_TOKEN_BUDGET, 50_000, 10, 80));
        taskEngine.updateTeamStatus(parent.getTaskId(), "RUNNING");

        publish(parent, TaskEventType.TEAM_PLAN_CREATED, null, null,
                AgentMode.MULTI_AGENT, "RUNNING",
                "研究、核验、综合三个隔离子任务已编排", 0);
        if (parent.getState() == TaskState.PLANNING) parent.transitionTo(TaskState.RUNNING);

        String dossier = completedResult(parent, TeamRole.SOURCE_RESEARCHER);
        if (dossier == null) {
            dossier = runChild(parent, TeamRole.SOURCE_RESEARCHER, buildResearchPrompt(parent), deadline);
        }

        boolean partial = false;
        String factReport = completedResult(parent, TeamRole.FACT_CHECKER);
        if (factReport == null) {
            try {
                factReport = runChild(parent, TeamRole.FACT_CHECKER,
                        buildFactCheckPrompt(parent, dossier), deadline);
            } catch (RuntimeException factCheckFailure) {
                partial = true;
                factReport = "{\"status\":\"INCOMPLETE\",\"factChecks\":[],"
                        + "\"warnings\":[\"事实核查子任务失败，以下材料尚未完成独立来源复核\"]}";
                parent.putVariable("team.fact_checker.failure", safeMessage(factCheckFailure));
                taskEngine.updateTeamStatus(parent.getTaskId(), "PARTIAL");
                publish(parent, TaskEventType.TEAM_PARTIAL, null, TeamRole.FACT_CHECKER.id,
                        TeamRole.FACT_CHECKER.mode, "PARTIAL",
                        "事实核查未完成，编辑将保留未验证告警", 0);
                meterRegistry.counter("ai.agent.team.partial.count").increment();
            }
        }

        String editorResult = completedResult(parent, TeamRole.MATERIAL_EDITOR);
        if (editorResult == null) {
            editorResult = runChild(parent, TeamRole.MATERIAL_EDITOR,
                    buildEditorPrompt(parent, dossier, factReport, partial), deadline);
        }

        Map<String, Object> pack = normalizePack(editorResult, parent, dossier, factReport, partial);
        String json = writePretty(pack);
        String markdown = renderMarkdown(pack, dossier, factReport);
        parent.addArtifact(Artifact.of("team-result.json", "team_result_json",
                json, "material_editor"));
        parent.addArtifact(Artifact.of("team-result.md", "team_result_markdown",
                markdown, "material_editor"));
        parent.putVariable("teamResultJson", json);
        parent.putVariable("teamResultMarkdown", markdown);
        // One-release aliases for existing service consumers.
        parent.putVariable("materialPackJson", json);
        parent.putVariable("materialPackMarkdown", markdown);
        parent.putVariable("finalOutput", markdown);
        parent.putVariable("teamStatus", partial ? "PARTIAL" : "COMPLETED");
        taskEngine.updateTeamStatus(parent.getTaskId(), partial ? "PARTIAL" : "COMPLETED");
        meterRegistry.counter("ai.agent.team.completed.count", "status",
                partial ? "partial" : "complete").increment();
    }

    private String runChild(AgentExecutionContext parent, TeamRole role, String prompt, long deadline) {
        ensureBeforeDeadline(deadline);
        String rootTaskId = string(parent.getVariable("rootTaskId"));
        if (rootTaskId == null) rootTaskId = parent.getTaskId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("maxTotalTokens", String.valueOf(role.tokenBudget));
        metadata.put("maxTokensPerStep", String.valueOf(role.tokenBudget));
        metadata.put("maxLlmCallsPerStep", "10");
        metadata.put("teamStatus", "RUNNING");
        copyIdentityMetadata(parent, metadata);
        TaskHandle child = taskEngine.create(TaskCreateRequest.child(
                parent.getGoal(), parent.getUserId(), parent.getSessionId(), role.mode,
                parent.getTaskId(), rootTaskId, role.id, metadata));
        parent.putVariable("team." + role.id + ".childTaskId", child.taskId());
        tokenAccountant.setBudget(child.taskId(), new TaskTokenBudget(
                role.tokenBudget, role.tokenBudget, 10, 80));

        publish(parent, TaskEventType.SUBTASK_CREATED, child.taskId(), role.id, role.mode,
                "CREATED", role.displayName + "子任务已创建", 0);
        taskEngine.start(child.taskId());
        publish(parent, TaskEventType.SUBTASK_STARTED, child.taskId(), role.id, role.mode,
                "RUNNING", role.displayName + "开始执行", 0);

        Map<String, Object> childTaskContext = new LinkedHashMap<>();
        childTaskContext.put("sessionId", parent.getSessionId() != null ? parent.getSessionId() : "");
        childTaskContext.put("parentTaskId", parent.getTaskId());
        childTaskContext.put("agentRole", role.id);
        copyIdentityContext(parent, childTaskContext);
        AgentTask childTask = new AgentTask(parent.getGoal(), childTaskContext,
                Set.copyOf(allowedTools(parent, role)), AgentConfig.defaults());
        com.yin.ai.platform.engine.ExecutionContext childExecutionContext =
                parent.getAgentContext().getExecCtx()
                        .withCaller("agent:team:" + role.id)
                        .withOperation("child-task");
        AgentExecutionContext childContext = new AgentExecutionContext(
                child.taskId(), parent.getSessionId(), parent.getUserId(), role.mode,
                AgentContext.from(childTask, childExecutionContext),
                parent.getEventPublisher(), contextStore);
        childContext.transitionTo(TaskState.PLANNING);
        childContext.transitionTo(TaskState.RUNNING);
        taskEngine.transitionTo(child.taskId(), TaskState.RUNNING);

        long started = System.nanoTime();
        try {
            String result = callRole(parent, role, prompt);
            long tokens = estimateTokens(prompt, result);
            if (tokens > role.tokenBudget) {
                throw new IllegalStateException(role.id + " exceeded token budget");
            }
            tokenAccountant.record(child.taskId(), role.id, "configured", tokens);
            tokenAccountant.record(parent.getTaskId(), role.id, "configured", tokens);
            childContext.putVariable("finalOutput", result);
            childContext.putVariable("tokensUsed", tokens);
            childContext.transitionTo(TaskState.COMPLETED);
            contextStore.saveSnapshot(child.taskId(), childContext.snapshot());
            taskEngine.transitionTo(child.taskId(), TaskState.COMPLETED);
            parent.putVariable("team." + role.id + ".result", result);
            parent.putVariable("team." + role.id + ".tokens", tokens);
            publish(parent, TaskEventType.SUBTASK_COMPLETED, child.taskId(), role.id, role.mode,
                    "COMPLETED", role.displayName + "已完成", tokens);
            meterRegistry.timer("ai.agent.team.child.duration", "role", role.id, "status", "success")
                    .record(Duration.ofNanos(System.nanoTime() - started));
            return result;
        } catch (RuntimeException failure) {
            childContext.putVariable("error", safeMessage(failure));
            childContext.transitionTo(TaskState.FAILED);
            contextStore.saveSnapshot(child.taskId(), childContext.snapshot());
            taskEngine.transitionTo(child.taskId(), TaskState.FAILED);
            publish(parent, TaskEventType.SUBTASK_FAILED, child.taskId(), role.id, role.mode,
                    "FAILED", role.displayName + "执行失败", 0);
            meterRegistry.timer("ai.agent.team.child.duration", "role", role.id, "status", "failed")
                    .record(Duration.ofNanos(System.nanoTime() - started));
            throw failure;
        }
    }

    private String callRole(AgentExecutionContext parent, TeamRole role, String prompt) {
        Set<String> functions = Set.copyOf(allowedTools(parent, role));
        String systemPrompt = contextPrompt(parent, "team." + role.id + ".systemPrompt", role.systemPrompt);
        String content = chatClient.generate("agent:team:" + role.id, prompt, systemPrompt,
                role.temperature, null, functions).getContent();
        if (content == null || content.isBlank()) throw new IllegalStateException(role.id + " returned empty result");
        return content;
    }

    private void copyIdentityMetadata(AgentExecutionContext parent, Map<String, String> target) {
        copyString(target, "tenantId", parent.getVariable("tenantId"));
        copyString(target, "traceId", parent.getAgentContext().getExecCtx().traceId());
        copyString(target, "roles", parent.getVariable("roles"));
        copyString(target, "permissions", parent.getVariable("permissions"));
    }

    private void copyIdentityContext(AgentExecutionContext parent, Map<String, Object> target) {
        copyObject(target, "tenantId", parent.getVariable("tenantId"));
        copyObject(target, "traceId", parent.getAgentContext().getExecCtx().traceId());
        copyObject(target, "roles", parent.getVariable("roles"));
        copyObject(target, "permissions", parent.getVariable("permissions"));
    }

    private void copyString(Map<String, String> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) target.put(key, value.toString());
    }

    private void copyObject(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) target.put(key, value);
    }

    private List<String> allowedTools(AgentExecutionContext parent, TeamRole role) {
        if (role == TeamRole.MATERIAL_EDITOR) return List.of();
        Object configured = parent.getVariable("teamAllowedTools");
        Set<String> allowlist = configured == null || configured.toString().isBlank()
                ? Set.of()
                : java.util.Arrays.stream(configured.toString().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        return functionRegistry.getAllDefinitions().stream()
                .filter(FunctionDefinition::isAvailable)
                .filter(def -> def.getRisk() == FunctionDefinition.Risk.READ_ONLY)
                .filter(def -> allowlist.isEmpty() || allowlist.contains(def.getName()))
                .map(FunctionDefinition::getName).sorted().toList();
    }

    private String completedResult(AgentExecutionContext parent, TeamRole role) {
        Object value = parent.getVariable("team." + role.id + ".result");
        return value != null && !value.toString().isBlank() ? value.toString() : null;
    }

    private String buildResearchPrompt(AgentExecutionContext parent) {
        return contextPrompt(parent, "team.source_researcher.prompt",
                "围绕目标收集并整理可追溯资料。") + "\n目标：\n" + parent.getGoal()
                + "\n\n输出 JSON：executiveSummary、keyFindings、sources。每个 source 必须包含 id/title/url/"
                + "publisher/publishedAt/retrievedAt/sourceType/credibility；关键发现通过 evidenceIds 关联来源。"
                + "只收集材料，不生成最终产物，不虚构来源。";
    }

    private String buildFactCheckPrompt(AgentExecutionContext parent, String dossier) {
        return contextPrompt(parent, "team.fact_checker.prompt", "核查材料中的关键事实。")
                + "\n目标：" + parent.getGoal() + "\n\n调研档案：\n" + dossier
                + "\n\n输出 JSON：factChecks 和 warnings。每项 factChecks 包含 claim、status"
                + "（VERIFIED/CONTESTED/UNVERIFIED）、evidenceIds、reason。VERIFIED 至少需要两个独立来源。";
    }

    private String buildEditorPrompt(AgentExecutionContext parent, String dossier,
                                     String factReport, boolean partial) {
        return contextPrompt(parent, "team.material_editor.prompt",
                "将已核验材料综合为结构化交付包，不要写成聊天回复。")
                + "\n目标：" + parent.getGoal()
                + "\n\n调研档案：\n" + dossier + "\n\n事实核查：\n" + factReport
                + "\n\n只输出一个合法 JSON 对象，字段：topic、status、executiveSummary、keyFindings、sources、"
                + "factChecks、recommendations、structure、warnings。status 必须为 " + (partial ? "PARTIAL" : "COMPLETE")
                + "。任何未验证事实必须保留 UNVERIFIED 和警告；不得输出 Markdown 代码围栏。";
    }

    private Map<String, Object> normalizePack(String raw, AgentExecutionContext parent,
                                               String dossier, String factReport, boolean partial) {
        Map<String, Object> pack = parseObject(raw);
        pack.put("topic", parent.getGoal());
        pack.put("generatedAt", Instant.now().toString());
        pack.put("status", partial ? "PARTIAL" : "COMPLETE");
        pack.putIfAbsent("executiveSummary", "团队研究结果已生成");
        for (String field : List.of("keyFindings", "sources", "factChecks", "recommendations", "structure", "warnings")) {
            pack.putIfAbsent(field, new ArrayList<>());
        }
        // One-release aliases for creator clients that still read the old material-pack schema.
        pack.putIfAbsent("angles", pack.get("recommendations"));
        pack.putIfAbsent("outline", pack.get("structure"));
        if (partial) {
            List<Object> warnings = mutableList(pack.get("warnings"));
            warnings.add("事实核查未完整执行，未验证内容不可作为确定事实发布");
            pack.put("warnings", warnings);
        }
        Map<String, Object> team = new LinkedHashMap<>();
        team.put("roles", List.of("source_researcher", "fact_checker", "material_editor"));
        team.put("childTaskIds", List.of(
                valueOrEmpty(parent.getVariable("team.source_researcher.childTaskId")),
                valueOrEmpty(parent.getVariable("team.fact_checker.childTaskId")),
                valueOrEmpty(parent.getVariable("team.material_editor.childTaskId"))));
        team.put("totalTokens", tokenAccountant.getSummary(parent.getTaskId()).totalTokens());
        pack.put("team", team);
        if (pack.get("sources") instanceof List<?> sources && sources.isEmpty()) pack.put("researchDossier", dossier);
        if (pack.get("factChecks") instanceof List<?> checks && checks.isEmpty()) pack.put("factCheckReport", factReport);
        return pack;
    }

    private Map<String, Object> parseObject(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int firstLine = json.indexOf('\n');
                int lastFence = json.lastIndexOf("```");
                json = json.substring(firstLine + 1, lastFence > firstLine ? lastFence : json.length()).trim();
            }
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) json = json.substring(start, end + 1);
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String renderMarkdown(Map<String, Object> pack, String dossier, String factReport) {
        StringBuilder md = new StringBuilder("# ").append(pack.get("topic")).append("\n\n")
                .append("> 状态：").append(pack.get("status")).append("\n\n")
                .append("## 调研摘要\n\n").append(pack.get("executiveSummary")).append("\n\n");
        appendSection(md, "关键发现", pack.get("keyFindings"));
        appendSection(md, "事实核查", pack.get("factChecks"));
        appendSection(md, "建议", pack.get("recommendations"));
        appendSection(md, "结构", pack.get("structure"));
        appendSection(md, "来源", pack.get("sources"));
        appendSection(md, "风险与告警", pack.get("warnings"));
        if (pack.get("sources") instanceof List<?> sources && sources.isEmpty()) {
            md.append("## 原始调研档案\n\n").append(dossier).append("\n\n");
        }
        if (pack.get("factChecks") instanceof List<?> checks && checks.isEmpty()) {
            md.append("## 原始核查记录\n\n").append(factReport).append("\n");
        }
        return md.toString();
    }

    private void appendSection(StringBuilder md, String title, Object value) {
        md.append("## ").append(title).append("\n\n");
        if (value instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) md.append("- ").append(toInlineJson(item)).append("\n");
        } else {
            md.append("- 暂无\n");
        }
        md.append('\n');
    }

    private void publish(AgentExecutionContext parent, TaskEventType type, String childTaskId,
                         String role, AgentMode mode, String status, String summary, long tokens) {
        parent.getEventPublisher().publish(AgentEvent.teamEvent(parent.getTaskId(), type,
                childTaskId, role, mode != null ? mode.name() : null, status, summary, tokens));
    }

    private void ensureBeforeDeadline(long deadline) {
        if (System.nanoTime() >= deadline) throw new IllegalStateException("Multi-agent team timeout exceeded");
    }

    public void cancelChildren(String parentTaskId, String reason) {
        for (var child : taskEngine.listChildren(parentTaskId)) {
            taskEngine.cancelTree(child.taskId(), reason);
        }
    }

    private long estimateTokens(String input, String output) {
        return Math.max(1, (input.length() + output.length()) / 2L);
    }

    private String writePretty(Object value) {
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize material pack", e); }
    }

    private String toInlineJson(Object value) {
        if (value instanceof String text) return text.replaceAll("\\s+", " ").trim();
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return String.valueOf(value); }
    }

    @SuppressWarnings("unchecked")
    private List<Object> mutableList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>((List<Object>) list) : new ArrayList<>();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String contextPrompt(AgentExecutionContext parent, String key, String fallback) {
        Object value = parent.getVariable(key);
        return value != null && !value.toString().isBlank() ? value.toString() : fallback;
    }

    private String valueOrEmpty(Object value) { return value == null ? "" : value.toString(); }
    private String string(Object value) { return value == null ? null : value.toString(); }

    private enum TeamRole {
        SOURCE_RESEARCHER("source_researcher", "研究员", AgentMode.DEEP_RESEARCH, 50_000, 0.2,
                "你是研究员。只使用只读工具收集可追溯来源，输出结构化事实材料，不虚构引用。"),
        FACT_CHECKER("fact_checker", "核验员", AgentMode.DEEP_RESEARCH, 40_000, 0.1,
                "你是核验员。逐项交叉核验结论，两个独立来源才可标记 VERIFIED，不确定时必须标记 UNVERIFIED。"),
        MATERIAL_EDITOR("material_editor", "综合员", AgentMode.PLAN_EXECUTE, 30_000, 0.2,
                "你是综合员。只根据输入证据整理结构化交付包，保留证据关联和未验证警告，严格输出 JSON。" );

        private final String id;
        private final String displayName;
        private final AgentMode mode;
        private final long tokenBudget;
        private final double temperature;
        private final String systemPrompt;

        TeamRole(String id, String displayName, AgentMode mode, long tokenBudget,
                 double temperature, String systemPrompt) {
            this.id = id;
            this.displayName = displayName;
            this.mode = mode;
            this.tokenBudget = tokenBudget;
            this.temperature = temperature;
            this.systemPrompt = systemPrompt;
        }
    }
}
