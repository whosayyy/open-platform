package com.yin.ai.platform.core.memory.semantic;

import com.yin.ai.platform.core.memory.MemoryVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fact 模型")
class FactTest {

    @Test
    @DisplayName("创建三元组 Fact")
    void shouldCreateTripleFact() {
        Fact fact = Fact.of("f1", "Alice", "likes", "Bob", 0.9);

        assertThat(fact.id()).isEqualTo("f1");
        assertThat(fact.subject()).isEqualTo("Alice");
        assertThat(fact.predicate()).isEqualTo("likes");
        assertThat(fact.object()).isEqualTo("Bob");
        assertThat(fact.confidence()).isEqualTo(0.9);
        assertThat(fact.toTriple()).isEqualTo("Alice likes Bob");
    }

    @Test
    @DisplayName("confidence 自动 clamp 到 [0.0, 1.0]")
    void shouldClampConfidence() {
        Fact over = Fact.of("f1", "X", "Y", "Z", 1.5);
        assertThat(over.confidence()).isEqualTo(1.0);

        Fact under = Fact.of("f2", "X", "Y", "Z", -0.5);
        assertThat(under.confidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("null 字段使用默认空值")
    void shouldHandleNullFields() {
        Fact fact = new Fact("f1", null, null, null, 0.5,
                null, null, null, null);

        assertThat(fact.subject()).isEmpty();
        assertThat(fact.predicate()).isEmpty();
        assertThat(fact.object()).isEmpty();
        assertThat(fact.vector()).isEmpty();
        assertThat(fact.sourceCellIds()).isEmpty();
        assertThat(fact.metadata()).isEmpty();
    }

    @Test
    @DisplayName("withVector 设置向量嵌入")
    void shouldSetVector() {
        Fact fact = Fact.of("f1", "S", "P", "O", 0.8);
        MemoryVector vec = MemoryVector.of(new float[]{0.1f, 0.2f, 0.3f}, "test-model");

        Fact withVec = fact.withVector(vec);

        assertThat(withVec.vector()).isPresent();
        assertThat(withVec.vector().get().dimension()).isEqualTo(3);
        assertThat(fact.vector()).isEmpty(); // original unchanged
    }

    @Test
    @DisplayName("withSourceCell 追加来源追溯")
    void shouldAddSourceCell() {
        Fact fact = Fact.of("f1", "S", "P", "O", 0.8);
        Fact withSource = fact.withSourceCell("cell-1").withSourceCell("cell-2");

        assertThat(withSource.sourceCellIds()).containsExactly("cell-1", "cell-2");
    }

    @Test
    @DisplayName("toTriple 格式化")
    void shouldFormatTriple() {
        assertThat(Fact.of("f1", "Bob", "knows", "Carol", 0.7).toTriple())
                .isEqualTo("Bob knows Carol");
    }
}
