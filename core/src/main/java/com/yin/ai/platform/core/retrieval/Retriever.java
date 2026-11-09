package com.yin.ai.platform.core.retrieval;

import com.yin.ai.platform.core.context.ExecutionContext;

/** Retrieval SPI implemented by Elasticsearch, vector stores, or in-memory adapters. */
public interface Retriever {

    RetrievalResult retrieve(RetrievalQuery query, ExecutionContext context);
}
