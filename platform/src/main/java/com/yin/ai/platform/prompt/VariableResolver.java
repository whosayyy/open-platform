package com.yin.ai.platform.prompt;

import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.prompt.model.VariableDef;
import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 变量解析器 — 根据 Variable Registry 定义解析运行时变量值。
 * <p>
 * 按 source 类型分发：
 * <ul>
 *   <li>system     → 系统变量（currentTime 等）</li>
 *   <li>memory     → 会话记忆</li>
 *   <li>rag        → RAG 检索结果</li>
 *   <li>security   → 登录用户信息</li>
 *   <li>function   → 函数注册表信息</li>
 *   <li>config     → 返回默认值</li>
 *   <li>request    → 请求参数</li>
 * </ul>
 */
@Slf4j
public class VariableResolver {

    private final VariableRegistry registry;
    private final SessionMemoryManager sessionMemoryManager;
    private final FunctionRegistry functionRegistry;

    public VariableResolver(VariableRegistry registry,
                            SessionMemoryManager sessionMemoryManager,
                            FunctionRegistry functionRegistry) {
        this.registry = registry;
        this.sessionMemoryManager = sessionMemoryManager;
        this.functionRegistry = functionRegistry;
    }

    /**
     * 解析变量值。
     *
     * @param variableName 变量名
     * @param ctx          执行上下文
     * @return 解析后的值（可能为 null）
     */
    public Object resolve(String variableName, SkillExecutionContext ctx) {
        VariableDef def = registry.get(variableName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown variable: " + variableName));

        try {
            return switch (def.getSource()) {
                case "system"   -> resolveSystem(def, ctx);
                case "memory"   -> resolveMemory(def, ctx);
                case "rag"      -> resolveRag(def, ctx);
                case "security" -> resolveSecurity(def, ctx);
                case "function" -> resolveFunction(def, ctx);
                case "config"   -> def.getDefaultValue();
                case "request"  -> resolveRequest(def, ctx);
                default         -> def.getDefaultValue();
            };
        } catch (Exception e) {
            log.warn("[PROMPT] variable={} resolve failed, using default", variableName, e);
            return def.getDefaultValue();
        }
    }

    private Object resolveSystem(VariableDef def, SkillExecutionContext ctx) {
        return switch (def.getName()) {
            case "currentTime" -> LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern(def.getFormat() != null ? def.getFormat() : "yyyy-MM-dd HH:mm:ss"));
            default -> def.getDefaultValue();
        };
    }

    private Object resolveMemory(VariableDef def, SkillExecutionContext ctx) {
        if ("conversationContext".equals(def.getName())) {
            if (ctx.getSessionId() != null) {
                String ctx2 = sessionMemoryManager.buildConversationContext(ctx.getSessionId());
                return ctx2 != null ? ctx2 : def.getDefaultValue();
            }
            return def.getDefaultValue();
        }
        return def.getDefaultValue();
    }

    private Object resolveRag(VariableDef def, SkillExecutionContext ctx) {
        if ("ragContext".equals(def.getName())) {
            if (ctx.getAttributes() != null && ctx.getAttributes().containsKey("ragContext")) {
                Object rag = ctx.getAttributes().get("ragContext");
                return rag != null && !rag.toString().isEmpty() ? rag : def.getDefaultValue();
            }
            return def.getDefaultValue();
        }
        return def.getDefaultValue();
    }

    private Object resolveSecurity(VariableDef def, SkillExecutionContext ctx) {
        // Security context is provided by GetLoginUserUtil at the application layer
        // Here we return context-level overrides if available
        if (ctx.getAttributes() != null && ctx.getAttributes().containsKey(def.getName())) {
            return ctx.getAttributes().get(def.getName());
        }
        return def.getDefaultValue();
    }

    private Object resolveFunction(VariableDef def, SkillExecutionContext ctx) {
        if ("tools".equals(def.getName())) {
            // Tools are collected by PromptComposer — here we just return the marker
            return def.getDefaultValue();
        }
        return def.getDefaultValue();
    }

    private Object resolveRequest(VariableDef def, SkillExecutionContext ctx) {
        if (ctx.getAttributes() != null && ctx.getAttributes().containsKey(def.getName())) {
            return ctx.getAttributes().get(def.getName());
        }
        if (ctx.getUserInput() != null && "userInput".equals(def.getName())) {
            return ctx.getUserInput();
        }
        return def.getDefaultValue();
    }
}
