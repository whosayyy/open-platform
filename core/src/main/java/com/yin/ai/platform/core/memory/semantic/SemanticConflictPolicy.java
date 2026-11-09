package com.yin.ai.platform.core.memory.semantic;

import java.util.Locale;

/** Pure temporal conflict classifier shared by the transactional adapter and unit tests. */
public class SemanticConflictPolicy {
    public enum Decision { REINFORCE, SUPERSEDE, CONFLICT }

    public Decision decide(SemanticFact current, SemanticFact incoming) {
        if (normalize(current.object()).equals(normalize(incoming.object()))) {
            return Decision.REINFORCE;
        }
        boolean higherPriority = sourcePriority(incoming.sourceType())
                > sourcePriority(current.sourceType());
        boolean newerAndCredible = incoming.validFrom().isAfter(current.validFrom())
                && incoming.confidence() >= current.confidence() - 0.1;
        return higherPriority || newerAndCredible ? Decision.SUPERSEDE : Decision.CONFLICT;
    }

    public double reinforcedConfidence(double oldConfidence, double newConfidence) {
        return 1 - (1 - oldConfidence) * (1 - newConfidence);
    }

    private int sourcePriority(String source) {
        String value = source != null ? source.toLowerCase(Locale.ROOT) : "";
        if (value.contains("user")) return 3;
        if (value.contains("system") || value.contains("tool")) return 2;
        if (value.contains("llm")) return 1;
        return 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]", "");
    }
}
