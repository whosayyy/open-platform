package com.yin.ai.platform.memory.orchestration;

import com.yin.ai.platform.memory.TokenCounter;
import com.yin.ai.platform.core.memory.api.ScoredMemoryCell;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies the documented ranking weights and hard per-type/total token limits. */
public class DefaultMemoryContextBuilder {
    static final double RELEVANCE_WEIGHT = 0.45;
    static final double IMPORTANCE_WEIGHT = 0.25;
    static final double RECENCY_WEIGHT = 0.15;
    static final double CONFIDENCE_WEIGHT = 0.15;

    public MemoryContextBundle build(MemoryRetrievalPlan plan,
                                     List<ScoredMemoryCell> candidates) {
        if (candidates == null || candidates.isEmpty() || plan.tokenBudget() == 0) {
            return MemoryContextBundle.empty(plan);
        }
        MemoryContextBudget budget = MemoryContextBudget.balanced(plan.tokenBudget());
        List<Ranked> ranked = candidates.stream()
                .filter(candidate -> candidate != null && candidate.cell() != null)
                .filter(candidate -> candidate.score() >= plan.minScore())
                .map(candidate -> new Ranked(candidate, finalScore(candidate)))
                .sorted(Comparator.comparingDouble(Ranked::rank).reversed()
                        .thenComparing((Ranked value) -> value.cell().cell().timestamp(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(value -> value.cell().cell().cellId().id()))
                .toList();

        EnumMap<MemoryType, Integer> usedByType = new EnumMap<>(MemoryType.class);
        List<ScoredMemoryCell> selected = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();
        Map<String, Integer> discarded = new HashMap<>();
        int total = 0;
        for (Ranked value : ranked) {
            if (selected.size() >= plan.maxItems()) {
                discarded.merge("maxItems", 1, Integer::sum);
                continue;
            }
            MemoryType type = normalize(value.cell().sourceType());
            String line = "[" + type.name().toLowerCase() + "] "
                    + value.cell().cell().content().strip() + "\n";
            int tokens = TokenCounter.estimate(line);
            int typeLimit = typeLimit(budget, type);
            if (usedByType.getOrDefault(type, 0) + tokens > typeLimit) {
                discarded.merge("typeBudget", 1, Integer::sum);
                continue;
            }
            if (total + tokens > budget.totalTokens()) {
                discarded.merge("totalBudget", 1, Integer::sum);
                continue;
            }
            selected.add(new ScoredMemoryCell(value.cell().cell(), value.rank(),
                    value.cell().sourceType(), value.cell().explanation()));
            rendered.append(line);
            usedByType.merge(type, tokens, Integer::sum);
            total += tokens;
        }
        return new MemoryContextBundle(plan, selected, rendered.toString(), total, discarded);
    }

    private double finalScore(ScoredMemoryCell scored) {
        MemoryCell cell = scored.cell();
        double relevance = normalizeScore(scored.score());
        double importance = number(cell.attributes().get("importance"), 0.5);
        double confidence = number(cell.attributes().get("confidence"), 0.85);
        double ageDays = cell.timestamp() == null ? 365
                : Math.max(0, Duration.between(cell.timestamp(), Instant.now()).toHours() / 24.0);
        double recency = Math.exp(-ageDays / 30.0);
        return relevance * RELEVANCE_WEIGHT + importance * IMPORTANCE_WEIGHT
                + recency * RECENCY_WEIGHT + confidence * CONFIDENCE_WEIGHT;
    }

    private double normalizeScore(double value) {
        if (value <= 0) return 0;
        return value <= 1 ? value : value / (1 + value);
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return Math.clamp(number.doubleValue(), 0, 1);
        try {
            return value != null ? Math.clamp(Double.parseDouble(value.toString()), 0, 1) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private MemoryType normalize(MemoryType type) {
        if (type == null) return MemoryType.EPISODIC;
        return type == MemoryType.SESSION ? MemoryType.EPISODIC : type;
    }

    private int typeLimit(MemoryContextBudget budget, MemoryType type) {
        return switch (type) {
            case WORKING -> budget.workingTokens();
            case EPISODIC, SESSION -> budget.episodicTokens();
            case SEMANTIC -> budget.semanticTokens();
            case PROCEDURAL -> budget.proceduralTokens();
        };
    }

    private record Ranked(ScoredMemoryCell cell, double rank) {}
}
