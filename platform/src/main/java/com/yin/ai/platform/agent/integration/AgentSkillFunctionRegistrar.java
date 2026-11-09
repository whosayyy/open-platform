package com.yin.ai.platform.agent.integration;

import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.runtime.SkillRuntime;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 自动将 SkillLoader 中的所有 Skill 注册为 {@link AgentSkillFunction}，
 * 使 Agent 可通过 FunctionRegistry 透明调用 Skill。
 * <p>
 * 命名约定: "skill:" + skillName（如 "skill:summary"）。
 * 在 FunctionRegistry 初始化完成后通过 @PostConstruct 批量注册。
 */
public class AgentSkillFunctionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillFunctionRegistrar.class);

    private final SkillLoader skillLoader;
    private final SkillRuntime skillRuntime;
    private final FunctionRegistry functionRegistry;

    public AgentSkillFunctionRegistrar(SkillLoader skillLoader,
                                        SkillRuntime skillRuntime,
                                        FunctionRegistry functionRegistry) {
        this.skillLoader = skillLoader;
        this.skillRuntime = skillRuntime;
        this.functionRegistry = functionRegistry;
    }

    @PostConstruct
    void registerSkillsAsFunctions() {
        var names = skillLoader.listSkillNames();
        int count = 0;
        for (String name : names) {
            try {
                AgentSkillFunction fn = new AgentSkillFunction(name, skillRuntime, skillLoader);
                functionRegistry.register(fn);
                count++;
                log.info("[SKILL-FN] registered skill as function: skill:{}", name);
            } catch (Exception e) {
                log.warn("[SKILL-FN] failed to register skill: {}: {}", name, e.getMessage());
            }
        }
        log.info("[SKILL-FN] registered {} skills as functions", count);
    }
}
