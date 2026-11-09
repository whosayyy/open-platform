package com.yin.ai.platform.rag.trigger;

import com.yin.ai.platform.agent.AgentContext;

/**
 * 知识边界检测器 SPI — 判断 LLM 自身知识是否足以回答，还是需要 RAG 检索外部知识。
 */
public interface KnowledgeBoundaryDetector {

    KnowledgeBoundaryAssessment detect(String userInput, AgentContext ctx);

    boolean isAvailable();

    default String name() {
        return getClass().getSimpleName();
    }
}
