package com.yin.ai.platform.adapter.memory.embedding;

import com.yin.ai.platform.core.memory.MemoryVector;
import com.yin.ai.platform.core.memory.semantic.EmbeddingService;
import dev.langchain4j.model.embedding.EmbeddingModel;

/** 将平台现有的 LangChain4j EmbeddingModel 适配到 Memory SPI。 */
public class LangChain4jMemoryEmbeddingService implements EmbeddingService {

    private final EmbeddingModel model;
    private final String modelName;

    public LangChain4jMemoryEmbeddingService(EmbeddingModel model) {
        this.model = model;
        this.modelName = model.getClass().getSimpleName();
    }

    @Override
    public MemoryVector embed(String text) {
        float[] values = model.embed(text != null ? text : "").content().vector();
        return MemoryVector.of(values, modelName);
    }
}
