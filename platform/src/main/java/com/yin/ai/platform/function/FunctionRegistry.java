package com.yin.ai.platform.function;

import com.yin.ai.platform.function.adapter.SpringAIToolAdapter;
import com.yin.ai.platform.engine.metrics.AiMetrics;
import com.yin.ai.platform.gateway.adapter.ToolCallbackResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Function 注册中心。
 * <p>
 * Spring 启动时自动扫描所有 Function Bean 并注册。
 * 提供按名称调用、并行调用、获取 LLM 可调用 ToolCallback 等功能。
 */
@Slf4j
public class FunctionRegistry implements ApplicationContextAware, ToolCallbackResolver {

    private final Map<String, Function> functions = new ConcurrentHashMap<>();
    private final Map<String, FunctionDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sourceFunctions = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, Function> beans = ctx.getBeansOfType(Function.class);
        AiMetrics aiMetrics = ctx.getBeanProvider(AiMetrics.class).getIfAvailable();
        for (Function fn : beans.values()) {
            if (aiMetrics != null && fn instanceof AbstractFunction af) {
                af.setAiMetrics(aiMetrics);
            }
            register(fn);
        }
        log.info("[FUNC-REG] registered {} functions: {}", functions.size(), functions.keySet());
    }

    public void register(Function fn) {
        String name = fn.getDefinition().getName();
        if (functions.containsKey(name)) {
            throw new IllegalStateException("Duplicate function name: " + name);
        }
        functions.put(name, fn);
        FunctionDefinition definition = fn.getDefinition();
        if (definition.getSourceId() == null || definition.getSourceId().isBlank()) definition.setSourceId("internal");
        definitions.put(name, definition);
        sourceFunctions.computeIfAbsent(definition.getSourceId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(name);
        log.info("[FUNC-REG] registered function: name={} category={} callableByLLM={}",
                name, fn.getDefinition().getCategory(), fn.getDefinition().isCallableByLLM());
    }

    /** Atomically replace all functions belonging to a dynamic source. */
    public synchronized void replaceSource(String sourceId, Collection<? extends Function> replacements) {
        if (sourceId == null || sourceId.isBlank() || "internal".equals(sourceId)) {
            throw new IllegalArgumentException("Dynamic sourceId is required");
        }
        Collection<? extends Function> safe = replacements != null ? replacements : List.of();
        Set<String> incoming = safe.stream().map(fn -> fn.getDefinition().getName()).collect(Collectors.toSet());
        for (String name : incoming) {
            Function existing = functions.get(name);
            if (existing != null && !sourceId.equals(existing.getDefinition().getSourceId())) {
                throw new IllegalStateException("Function name collision: " + name);
            }
        }
        Set<String> previous = Set.copyOf(sourceFunctions.getOrDefault(sourceId, Set.of()));
        previous.stream().filter(name -> !incoming.contains(name)).forEach(name -> {
            functions.remove(name);
            definitions.remove(name);
        });
        sourceFunctions.put(sourceId, ConcurrentHashMap.newKeySet());
        for (Function fn : safe) {
            FunctionDefinition definition = fn.getDefinition();
            if (!sourceId.equals(definition.getSourceId())) {
                throw new IllegalArgumentException("Function source mismatch: " + definition.getName());
            }
            functions.put(definition.getName(), fn);
            definitions.put(definition.getName(), definition);
            sourceFunctions.get(sourceId).add(definition.getName());
        }
        log.info("[FUNC-REG] replaced dynamic source={} functions={}", sourceId, incoming);
    }

    public void setSourceAvailability(String sourceId, boolean available) {
        for (String name : sourceFunctions.getOrDefault(sourceId, Set.of())) {
            FunctionDefinition definition = definitions.get(name);
            if (definition != null) definition.setAvailable(available);
        }
    }

    public Set<String> getSourceFunctionNames(String sourceId) {
        return Set.copyOf(sourceFunctions.getOrDefault(sourceId, Set.of()));
    }

    /**
     * 按名称调用。
     */
    public FunctionResult invoke(String name, Map<String, Object> params) {
        Function fn = functions.get(name);
        if (fn == null) {
            throw new IllegalArgumentException("Unknown function: " + name);
        }
        if (!fn.getDefinition().isAvailable()) {
            return FunctionResult.fail("FUNCTION_SOURCE_UNAVAILABLE",
                    "Function source is unavailable: " + fn.getDefinition().getSourceId());
        }
        String authority = fn.getDefinition().getRequiredAuthority();
        if (authority != null && !authority.isBlank()) {
            var authentication = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            boolean authorized = authentication != null && authentication.isAuthenticated()
                    && authentication.getAuthorities().stream()
                    .anyMatch(granted -> authority.equals(granted.getAuthority()));
            if (!authorized) return FunctionResult.fail("FUNCTION_FORBIDDEN", "Missing authority: " + authority);
        }
        FunctionInput input = FunctionInput.of(params);
        // [全链路追踪] 工具调用透传业务 traceId（引擎线程 MDC），AgentSkillFunction 不再拿到 null
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            input.setTraceId(traceId);
        }
        input.setTenantId(org.slf4j.MDC.get("tenantId"));
        try {
            var loginUser = com.yin.security.util.GetLoginUserUtil.getLoginUser();
            if (loginUser != null) {
                input.setUserId(loginUser.getUserId() != null ? loginUser.getUserId().toString() : null);
                input.setRoles(loginUser.getRoles() != null ? Set.copyOf(loginUser.getRoles()) : Set.of());
                input.setPermissions(loginUser.getPermissions() != null
                        ? Set.copyOf(loginUser.getPermissions()) : Set.of());
            }
        } catch (RuntimeException unavailable) {
            log.debug("[FUNC-REG] authenticated identity unavailable: {}", unavailable.getMessage());
        }
        return fn.execute(input);
    }

    /**
     * 并行调用多个 Function。
     */
    public Map<String, FunctionResult> invokeAll(Map<String, Map<String, Object>> calls) {
        return calls.entrySet().parallelStream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> invoke(e.getKey(), e.getValue())
                ));
    }

    /**
     * 获取所有允许 LLM 自动调用的 Function 的 ToolCallback。
     * 供 AI Gateway 构建 ChatClient 时使用。
     */
    public List<ToolCallback> getLLMCallableToolCallbacks() {
        return functions.values().stream()
                .filter(fn -> fn.getDefinition().isCallableByLLM())
                .filter(fn -> fn.getDefinition().isAvailable())
                .filter(fn -> fn.getDefinition().getRisk() != FunctionDefinition.Risk.UNSPECIFIED)
                .map(SpringAIToolAdapter::toToolCallback)
                .toList();
    }

    /**
     * 获取指定 Function 列表的 ToolCallback。
     */
    public List<ToolCallback> getToolCallbacks(List<String> functionNames) {
        if (functionNames == null || functionNames.isEmpty()) {
            return List.of();
        }
        return functionNames.stream()
                .map(this::get)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(fn -> fn.getDefinition().isAvailable())
                .filter(fn -> fn.getDefinition().getRisk() != FunctionDefinition.Risk.UNSPECIFIED)
                .map(SpringAIToolAdapter::toToolCallback)
                .toList();
    }

    /**
     * {@link ToolCallbackResolver} 实现：网关通过这个窄端口按名称解析工具，
     * 因此网关不需要依赖 FunctionRegistry 本身。
     */
    @Override
    public List<ToolCallback> resolve(List<String> functionNames) {
        return getToolCallbacks(functionNames);
    }

    public Optional<Function> get(String name) {
        return Optional.ofNullable(functions.get(name));
    }

    public Optional<FunctionDefinition> getDefinition(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public Set<String> getFunctionNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    public Collection<FunctionDefinition> getAllDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}
