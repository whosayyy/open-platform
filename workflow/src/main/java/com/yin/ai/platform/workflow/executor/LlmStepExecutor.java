package com.yin.ai.platform.workflow.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.structured.StructuredClient;
import com.yin.ai.platform.workflow.model.Artifact;
import com.yin.ai.platform.workflow.model.ArtifactType;
import com.yin.ai.platform.workflow.model.ExecutorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * LLM 步骤执行器 — 单轮结构化输出，只做语言理解与内容生成。
 *
 * <p>通过 capture-only 工具强制模型按 Schema 输出，结果封装为产物返回 Runtime。
 */
@Slf4j
@Component
public class LlmStepExecutor implements StepExecutor {

    private static final double TEMPERATURE = 0.7;

    private final StructuredClient structuredClient;
    private final ObjectProvider<StructuredOutputSpecProvider> specProvider;
    private final ObjectMapper objectMapper;

    public LlmStepExecutor(StructuredClient structuredClient,
                           ObjectProvider<StructuredOutputSpecProvider> specProvider,
                           ObjectMapper objectMapper) {
        this.structuredClient = structuredClient;
        this.specProvider = specProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutorType type() {
        return ExecutorType.LLM;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) {
        ArtifactType outputType = context.step().outputType();
        if (outputType == null) {
            return StepExecutionResult.fail("NO_OUTPUT_SPEC", "LLM step requires output artifact");
        }
        StructuredOutputSpecProvider provider = specProvider.getIfAvailable();
        StructuredOutputSpec spec = provider != null ? provider.specFor(outputType) : null;
        if (spec == null) {
            return StepExecutionResult.fail("NO_OUTPUT_SPEC", "no structured output spec for " + outputType);
        }

        Optional<String> args = structuredClient.generate(
                spec.toolName(), spec.description(), spec.schema(),
                context.userPrompt(), context.systemPrompt(), TEMPERATURE);
        if (args.isEmpty()) {
            return StepExecutionResult.fail("LLM_OUTPUT_EMPTY",
                    "model produced no tool-call for " + spec.toolName());
        }
        try {
            Object content = parseJsonTolerant(args.get());
            if (content == null) {
                log.warn("[WF-LLM] parse {} failed: no JSON payload after sanitization",
                        spec.toolName());
                return StepExecutionResult.fail("LLM_PARSE_ERROR",
                        "model produced no JSON payload for " + spec.toolName());
            }
            Artifact artifact = Artifact.of(context.taskRun().getTaskRunId(),
                    context.step().getId(), outputType, content);
            return StepExecutionResult.ok(artifact);
        } catch (Exception e) {
            log.warn("[WF-LLM] parse {} failed: {}", spec.toolName(), e.getMessage());
            return StepExecutionResult.fail("LLM_PARSE_ERROR", e.getMessage());
        }
    }

    /**
     * 【BUG 修复】容错 JSON 解析 — 原实现直接 readValue，LLM 偶尔返回
     * {@code ```json ... ```} 代码块包裹 / 前后缀散文 / 混入说明文字时直接抛异常
     * （日志：parse submitArticleOutline failed: Unexpected character ('抛'...)）。
     * 策略：先原样解析；失败后剥离 ``` 代码块、再括号深度扫描取最外层 JSON 片段重试。
     */
    private Object parseJsonTolerant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw.trim(), Object.class);
        } catch (Exception ignored) {
            // fallthrough to tolerant extraction
        }

        String candidate = raw.trim();
        // 1. 剥离 ```json ... ``` 代码块
        int fenceStart = candidate.indexOf("```");
        if (fenceStart >= 0) {
            int nl = candidate.indexOf('\n', fenceStart);
            int contentStart = nl >= 0 ? nl + 1 : fenceStart + 3;
            int fenceEnd = candidate.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                candidate = candidate.substring(contentStart, fenceEnd).trim();
            }
        }
        // 2. 括号深度扫描取最外层 [ 或 { 片段
        char open = 0;
        int start = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '[' || c == '{') {
                open = c;
                start = i;
                break;
            }
        }
        if (start >= 0) {
            char close = open == '[' ? ']' : '}';
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < candidate.length(); i++) {
                char c = candidate.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close && --depth == 0) {
                    try {
                        return objectMapper.readValue(candidate.substring(start, i + 1), Object.class);
                    } catch (Exception ignored2) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
