package com.yin.ai.platform.rag.trigger;

/**
 * 知识边界评估结果。
 *
 * @param needsExternalKnowledge 是否需要外部知识检索
 * @param confidence              置信度 0.0 ~ 1.0
 * @param knowledgeDomain         需要的知识领域（如"news"、"legal"、"medical"）
 * @param reason                  判定理由
 */
public record KnowledgeBoundaryAssessment(
        boolean needsExternalKnowledge,
        double confidence,
        String knowledgeDomain,
        String reason) {

    public static KnowledgeBoundaryAssessment needsRag(String domain, String reason) {
        return new KnowledgeBoundaryAssessment(true, 0.9, domain, reason);
    }

    public static KnowledgeBoundaryAssessment noRagNeeded(String reason) {
        return new KnowledgeBoundaryAssessment(false, 0.9, null, reason);
    }

    public static KnowledgeBoundaryAssessment uncertain(String reason) {
        return new KnowledgeBoundaryAssessment(true, 0.5, null, reason);
    }
}
