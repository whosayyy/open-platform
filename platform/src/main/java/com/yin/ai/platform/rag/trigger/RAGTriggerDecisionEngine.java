package com.yin.ai.platform.rag.trigger;

import com.yin.ai.platform.agent.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG 触发决策引擎 — 综合 Intent、知识边界检测结果和配置决定是否触发 RAG。
 * <p>
 * 决策逻辑：
 * <ol>
 *   <li>Intent 在 alwaysRagIntents 中 → 始终触发</li>
 *   <li>KnowledgeBoundaryDetector 判定需要外部知识 → 触发</li>
 *   <li>检测不确定且置信度低于阈值 → 保守触发（宁可多检不漏检）</li>
 *   <li>明确不需要外部知识 → 不触发</li>
 * </ol>
 */
public class RAGTriggerDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(RAGTriggerDecisionEngine.class);

    private final KnowledgeBoundaryDetector detector;
    private final RAGTriggerPolicy policy;

    public RAGTriggerDecisionEngine(KnowledgeBoundaryDetector detector, RAGTriggerPolicy policy) {
        this.detector = detector;
        this.policy = policy;
    }

    /**
     * 判断是否应该触发 RAG 检索。
     *
     * @param userInput 用户输入
     * @param intent    识别的意图（可为 null）
     * @param ctx       执行上下文（可为 null）
     */
    public boolean shouldTrigger(String userInput, String intent, AgentContext ctx) {
        if (!policy.boundaryDetectionEnabled()) {
            log.debug("[RAG-TRIGGER] boundary detection disabled, always trigger");
            return true;
        }

        // Rule 1: Always-trigger intents
        if (policy.alwaysTriggerOnIntent()
                && intent != null
                && policy.alwaysRagIntents().contains(intent.toUpperCase())) {
            log.info("[RAG-TRIGGER] intent={} decision=TRIGGER reason=always_rag_intent", intent);
            return true;
        }

        if (detector == null || !detector.isAvailable()) {
            log.debug("[RAG-TRIGGER] detector unavailable, default trigger");
            return true;
        }

        // Rule 2: Use boundary detector
        KnowledgeBoundaryAssessment assessment;
        try {
            assessment = detector.detect(userInput, ctx);
        } catch (Exception e) {
            log.warn("[RAG-TRIGGER] detector failed, conservative trigger: {}", e.getMessage());
            return true;
        }

        if (!assessment.needsExternalKnowledge()) {
            log.info("[RAG-TRIGGER] decision=SKIP reason={} confidence={}",
                    assessment.reason(), assessment.confidence());
            return false;
        }

        if (assessment.confidence() >= policy.detectionConfidenceThreshold()) {
            log.info("[RAG-TRIGGER] decision=TRIGGER domain={} reason={} confidence={}",
                    assessment.knowledgeDomain(), assessment.reason(), assessment.confidence());
            return true;
        }

        // Rule 3: Low confidence → conservative trigger
        log.info("[RAG-TRIGGER] decision=TRIGGER reason=conservative (low confidence={})",
                assessment.confidence());
        return true;
    }
}
