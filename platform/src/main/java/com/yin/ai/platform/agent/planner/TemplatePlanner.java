package com.yin.ai.platform.agent.planner;

import com.yin.ai.platform.agent.AgentContext;

import java.util.Collections;
import java.util.List;

/**
 * 模板规划器 — 使用预定义步骤模板生成计划。
 * <p>
 * 适合已知流程的确定性任务。步骤在构造时注入，plan() 直接返回。
 */
public class TemplatePlanner implements Planner {

    private final List<Step> steps;
    private final String directResult;

    public TemplatePlanner(List<Step> steps) {
        this(steps, null);
    }

    public TemplatePlanner(List<Step> steps, String directResult) {
        this.steps = steps != null ? List.copyOf(steps) : Collections.emptyList();
        this.directResult = directResult;
    }

    @Override
    public Plan plan(AgentContext ctx) {
        if (directResult != null) {
            return Plan.direct(directResult);
        }
        if (steps.isEmpty()) {
            return Plan.empty();
        }
        return Plan.ofSteps(steps);
    }
}
