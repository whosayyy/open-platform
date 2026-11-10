package com.yin.ai.platform.workflow.load;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.yin.ai.platform.workflow.model.ExecutorType;
import com.yin.ai.platform.workflow.model.RetryPolicy;
import com.yin.ai.platform.workflow.model.StepCondition;
import com.yin.ai.platform.workflow.model.StepDefinition;
import com.yin.ai.platform.workflow.model.WorkflowDefinition;
import com.yin.ai.platform.workflow.tool.ToolPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Classpath YAML 的工作流加载器。
 *
 * <p>默认扫描 {@code classpath*:workflows/*.yaml}，兼容单文件路径 {@code workflows/{name}.yaml}。
 */
@Slf4j
@Component
public class YamlWorkflowLoader implements WorkflowLoader {

    private static final String DEFAULT_PATTERN = "classpath*:workflows/*.yaml";

    private final ObjectMapper yamlMapper = new YAMLMapper(new YAMLFactory());
    private final String locationPattern;

    public YamlWorkflowLoader() {
        this(DEFAULT_PATTERN);
    }

    public YamlWorkflowLoader(String locationPattern) {
        this.locationPattern = locationPattern;
    }

    @Override
    public Optional<WorkflowDefinition> load(String workflowName) {
        return loadAll().stream()
                .filter(w -> w.getName().equals(workflowName))
                .findFirst();
    }

    @Override
    public List<WorkflowDefinition> loadAll() {
        List<WorkflowDefinition> result = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(locationPattern);
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    WorkflowYamlDto dto = yamlMapper.readValue(in, WorkflowYamlDto.class);
                    if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
                        log.warn("[WORKFLOW] skip invalid yaml without name: {}", resource.getFilename());
                        continue;
                    }
                    result.add(toDefinition(dto));
                } catch (IOException e) {
                    log.warn("[WORKFLOW] load failed for {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[WORKFLOW] scan failed pattern={}: {}", locationPattern, e.getMessage());
        }
        log.info("[WORKFLOW] loaded {} workflow(s) from {}", result.size(), locationPattern);
        return result;
    }

    private WorkflowDefinition toDefinition(WorkflowYamlDto dto) {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setName(dto.getName());
        wf.setVersion(dto.getVersion() == null ? "1.0.0" : dto.getVersion());
        wf.setDescription(dto.getDescription());
        wf.setSkill(dto.getSkill());

        List<StepDefinition> steps = new ArrayList<>();
        if (dto.getSteps() != null) {
            for (StepYamlDto step : dto.getSteps()) {
                steps.add(toStep(step));
            }
        }
        wf.setSteps(steps);

        Map<String, ToolPolicy> policies = new LinkedHashMap<>();
        if (dto.getToolPolicies() != null) {
            for (Map.Entry<String, List<String>> e : dto.getToolPolicies().entrySet()) {
                policies.put(e.getKey(), ToolPolicy.of(e.getKey(),
                        e.getValue() == null ? Set.of() : Set.copyOf(e.getValue())));
            }
        }
        wf.setToolPolicies(policies);
        return wf;
    }

    private StepDefinition toStep(StepYamlDto step) {
        StepDefinition def = new StepDefinition();
        def.setId(step.getId());
        def.setName(step.getName() == null ? step.getId() : step.getName());
        if (step.getExecutor() != null) {
            def.setExecutorType(ExecutorType.valueOf(step.getExecutor().toUpperCase()));
        }
        def.setPrompt(step.getPrompt());
        def.setDependsOn(step.getDependsOn());
        def.setInput(step.getInput());
        def.setOutput(step.getOutput());
        def.setToolPolicy(step.getToolPolicy());
        def.setHandler(step.getHandler());
        if (step.getCondition() != null) {
            def.setCondition(new StepCondition(
                    step.getCondition().get("type"),
                    step.getCondition().get("expression")));
        }
        if (step.getRetry() != null) {
            RetryPolicy retry = new RetryPolicy();
            Object attempts = step.getRetry().get("max_attempts");
            if (attempts == null) {
                attempts = step.getRetry().get("maxAttempts");
            }
            if (attempts instanceof Number n) {
                retry.setMaxAttempts(n.intValue());
            }
            Object backoff = step.getRetry().get("backoff_millis") != null
                    ? step.getRetry().get("backoff_millis")
                    : step.getRetry().get("backoffMillis");
            if (backoff instanceof Number n) {
                retry.setBackoffMillis(n.longValue());
            }
            def.setRetry(retry);
        }
        if (step.getTimeoutSeconds() != null) {
            def.setTimeoutSeconds(step.getTimeoutSeconds());
        }
        return def;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowYamlDto {
        private String name;
        private String version;
        private String description;
        private String skill;
        private List<StepYamlDto> steps = new ArrayList<>();
        @JsonProperty("tool_policies")
        private Map<String, List<String>> toolPolicies = new HashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSkill() {
            return skill;
        }

        public void setSkill(String skill) {
            this.skill = skill;
        }

        public List<StepYamlDto> getSteps() {
            return steps;
        }

        public void setSteps(List<StepYamlDto> steps) {
            this.steps = steps;
        }

        public Map<String, List<String>> getToolPolicies() {
            return toolPolicies;
        }

        public void setToolPolicies(Map<String, List<String>> toolPolicies) {
            this.toolPolicies = toolPolicies;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepYamlDto {
        private String id;
        private String name;
        private String executor;
        private String prompt;
        @JsonProperty("depends_on")
        private List<String> dependsOn = new ArrayList<>();
        private List<String> input = new ArrayList<>();
        private String output;
        @JsonProperty("tool_policy")
        private String toolPolicy;
        private String handler;
        private Map<String, String> condition;
        private Map<String, Object> retry;
        @JsonProperty("timeout_seconds")
        private Integer timeoutSeconds;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getExecutor() {
            return executor;
        }

        public void setExecutor(String executor) {
            this.executor = executor;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public List<String> getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
        }

        public List<String> getInput() {
            return input;
        }

        public void setInput(List<String> input) {
            this.input = input;
        }

        public String getOutput() {
            return output;
        }

        public void setOutput(String output) {
            this.output = output;
        }

        public String getToolPolicy() {
            return toolPolicy;
        }

        public void setToolPolicy(String toolPolicy) {
            this.toolPolicy = toolPolicy;
        }

        public String getHandler() {
            return handler;
        }

        public void setHandler(String handler) {
            this.handler = handler;
        }

        public Map<String, String> getCondition() {
            return condition;
        }

        public void setCondition(Map<String, String> condition) {
            this.condition = condition;
        }

        public Map<String, Object> getRetry() {
            return retry;
        }

        public void setRetry(Map<String, Object> retry) {
            this.retry = retry;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
