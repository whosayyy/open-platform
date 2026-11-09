package com.yin.ai.platform.agent.tool;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic-first tool selection that never expands the authorized candidate set. */
@Component
public class DefaultToolSelectionPolicy implements ToolSelectionPolicy {

    public static final int LLM_SELECTION_THRESHOLD = 8;

    private final ToolCandidateSelector selector;
    private final RoadmapFeatureProperties rollout;
    private final MeterRegistry meters;

    @Autowired
    public DefaultToolSelectionPolicy(ObjectProvider<ToolCandidateSelector> selector,
                                      RoadmapFeatureProperties rollout,
                                      ObjectProvider<MeterRegistry> meters) {
        this(selector.getIfAvailable(), rollout, meters.getIfAvailable());
    }

    public DefaultToolSelectionPolicy(ToolCandidateSelector selector,
                                      RoadmapFeatureProperties rollout,
                                      MeterRegistry meters) {
        this.selector = selector;
        this.rollout = rollout;
        this.meters = meters;
    }

    @Override
    public Selection select(String goal, Collection<FunctionDefinition> candidates,
                            Set<String> declaredTools, Set<String> requiredTools,
                            AgentContext context) {
        Set<String> declared = declaredTools != null ? Set.copyOf(declaredTools) : Set.of();
        Set<String> required = requiredTools != null ? Set.copyOf(requiredTools) : Set.of();
        List<FunctionDefinition> eligible = safe(candidates).stream()
                .filter(FunctionDefinition::isAvailable)
                .filter(FunctionDefinition::isCallableByLLM)
                .filter(def -> declared.isEmpty() || declared.contains(def.getName()))
                .toList();

        if (!required.isEmpty()) {
            List<FunctionDefinition> mandatory = eligible.stream()
                    .filter(def -> required.contains(def.getName())).toList();
            return result(mandatory, "required", false);
        }
        if (eligible.size() <= LLM_SELECTION_THRESHOLD || selector == null
                || rollout == null || !rollout.getAgentConstraints().isEnforcing()) {
            return result(eligible, "deterministic", false);
        }

        try {
            Set<String> selected = selector.select(goal, eligible, context);
            Set<String> authorized = eligible.stream().map(FunctionDefinition::getName)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> safeSelection = new LinkedHashSet<>(selected != null ? selected : Set.of());
            safeSelection.retainAll(authorized);
            if (!safeSelection.isEmpty()) {
                return result(eligible.stream().filter(def -> safeSelection.contains(def.getName())).toList(),
                        "llm", false);
            }
        } catch (RuntimeException ignored) {
            // Fail closed to the explicit Skill declaration below.
        }

        List<FunctionDefinition> fallback = declared.isEmpty() ? List.of()
                : eligible.stream().filter(def -> declared.contains(def.getName())).toList();
        return result(fallback, "skill_fallback", true);
    }

    private Collection<FunctionDefinition> safe(Collection<FunctionDefinition> candidates) {
        return candidates != null ? candidates : List.of();
    }

    private Selection result(Collection<FunctionDefinition> tools, String strategy, boolean fallback) {
        if (meters != null) {
            meters.counter("ai.agent.tool_selection.total", "strategy", strategy,
                    "outcome", fallback ? "fallback" : "selected").increment();
            meters.summary("ai.agent.tool_selection.count", "strategy", strategy).record(tools.size());
        }
        return new Selection(List.copyOf(tools), strategy, fallback);
    }
}
