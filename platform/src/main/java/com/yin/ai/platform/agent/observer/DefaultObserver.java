package com.yin.ai.platform.agent.observer;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.executor.StepResult;

/**
 * 默认观察器 — 启发式评估步骤结果质量（无 LLM 依赖）。
 * <p>
 * 评估规则:
 * <ul>
 *   <li>步骤失败 → FAILURE</li>
 *   <li>空输出 → PARTIAL</li>
 *   <li>短输出（&lt; 20 字符）→ PARTIAL</li>
 *   <li>正常输出 → SUCCESS（截断摘要至 200 字符）</li>
 * </ul>
 */
public class DefaultObserver implements Observer {

    @Override
    public Observation observe(StepResult result, AgentContext ctx) {
        if (!result.success()) {
            return Observation.failure(ellipsis(result.error(), 200));
        }
        if (result.output() == null || result.output().isBlank()) {
            return new Observation(Observation.ObservationStatus.PARTIAL,
                    "step completed with empty output");
        }
        int len = result.output().length();
        if (len < 20) {
            return new Observation(Observation.ObservationStatus.PARTIAL,
                    "short output: " + ellipsis(result.output(), 100));
        }
        return Observation.success(ellipsis(result.output(), 200));
    }

    static String ellipsis(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
