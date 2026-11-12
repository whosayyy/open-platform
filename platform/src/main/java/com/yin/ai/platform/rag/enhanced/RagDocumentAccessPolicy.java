package com.yin.ai.platform.rag.enhanced;

import com.yin.ai.platform.core.rag.RetrievedDocument;

/** Final candidate authorization check, executed after ES/vector retrieval and reranking. */
public interface RagDocumentAccessPolicy {
    boolean canRead(RetrievedDocument document, String tenantId, Long userId);

    default int priority() {
        return 1_000;
    }
}
