package com.yin.ai.platform.memory.retrieval;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import com.yin.ai.platform.core.memory.MemoryVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VectorMemoryRetrieverTest {

    private final VectorMemoryRetriever retriever = new VectorMemoryRetriever();

    @Test
    void ranksCellsByCosineSimilarityAndSkipsDimensionMismatch() {
        MemoryCell relevant = MemoryCell.of(MemoryCellId.of("relevant"),
                        MemoryType.SEMANTIC, "猫科动物")
                .withVector(MemoryVector.of(new float[]{1, 0}, "test"));
        MemoryCell irrelevant = MemoryCell.of(MemoryCellId.of("irrelevant"),
                        MemoryType.SEMANTIC, "数据库")
                .withVector(MemoryVector.of(new float[]{0, 1}, "test"));
        MemoryCell mismatch = MemoryCell.of(MemoryCellId.of("mismatch"),
                        MemoryType.SEMANTIC, "错误维度")
                .withVector(MemoryVector.of(new float[]{1, 0, 0}, "test"));
        MemoryQuery query = MemoryQuery.hybrid("猫", new MemoryVector(
                new float[]{1, 0}, "test", 2), 5);

        var results = retriever.retrieve(query, List.of(irrelevant, mismatch, relevant));

        assertThat(results).extracting(result -> result.cell().cellId().id())
                .containsExactly("relevant");
        assertThat(results.getFirst().explanation()).isEqualTo("VECTOR_COSINE");
    }
}
