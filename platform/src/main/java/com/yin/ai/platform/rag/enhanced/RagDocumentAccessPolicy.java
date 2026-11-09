package com.yin.ai.platform.rag.enhanced;

/** Final candidate authorization check, executed after ES/vector retrieval and reranking. */
public interface RagDocumentAccessPolicy {
    boolean canRead(RetrievedDocument document, String tenantId, Long userId);

    default int priority() {
        return 1_000;
    }
}
