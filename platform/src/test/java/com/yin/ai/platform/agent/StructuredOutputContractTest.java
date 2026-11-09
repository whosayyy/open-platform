package com.yin.ai.platform.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link StructuredOutputContract} 常量与工厂方法。
 */
@DisplayName("StructuredOutputContract")
class StructuredOutputContractTest {

    @Test
    @DisplayName("of(schema) → 默认工具名 + 传入 schema")
    void ofUsesDefaultToolName() {
        Map<String, Object> schema = Map.of("type", "object");

        StructuredOutputContract contract = StructuredOutputContract.of(schema);

        assertThat(contract.toolName()).isEqualTo(StructuredOutputContract.DEFAULT_TOOL_NAME);
        assertThat(contract.jsonSchema()).isEqualTo(schema);
    }

    @Test
    @DisplayName("显式工具名构造")
    void customToolName() {
        Map<String, Object> schema = Map.of("type", "object");

        StructuredOutputContract contract =
                new StructuredOutputContract("submitCollectNews", schema);

        assertThat(contract.toolName()).isEqualTo("submitCollectNews");
        assertThat(contract.jsonSchema()).isEqualTo(schema);
    }

    @Test
    @DisplayName("默认工具描述非空且包含工具指令")
    void defaultDescription() {
        assertThat(StructuredOutputContract.DEFAULT_TOOL_DESCRIPTION)
                .isNotBlank()
                .contains("本工具")
                .contains("结果 JSON");
    }
}
