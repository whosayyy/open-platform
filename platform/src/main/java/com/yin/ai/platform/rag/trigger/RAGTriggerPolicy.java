package com.yin.ai.platform.rag.trigger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * RAG 触发策略配置。
 */
@ConfigurationProperties("ai.platform.rag.trigger")
public record RAGTriggerPolicy(
        boolean boundaryDetectionEnabled,
        boolean alwaysTriggerOnIntent,
        Set<String> alwaysRagIntents,
        double detectionConfidenceThreshold) {

    public static RAGTriggerPolicy defaults() {
        return new RAGTriggerPolicy(
                true,
                true,
                Set.of("SEARCH", "QA", "SUMMARY", "HOT_NEWS"),
                0.6);
    }

    public double detectionConfidenceThreshold() {
        return detectionConfidenceThreshold > 0 ? detectionConfidenceThreshold : 0.6;
    }
}
