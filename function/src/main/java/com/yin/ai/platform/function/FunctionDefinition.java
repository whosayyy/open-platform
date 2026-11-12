package com.yin.ai.platform.function;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Function 元数据 — 自描述的 Function 定义。
 * <p>
 * 可用于生成 Spring AI ToolCallback、MCP Tool 描述等协议无关的元数据。
 */
@Data
@Builder
public class FunctionDefinition {

    /** 全局唯一名称（用于注册和调用） */
    private String name;

    /** 描述（用于 LLM Function Calling 的 tool description） */
    private String description;

    /** 版本 */
    @Builder.Default
    private String version = "1.0.0";

    /** 参数列表 */
    private List<ParamDef> parameters;

    /** 返回类型 */
    @Builder.Default
    private ReturnType returnType = ReturnType.OBJECT;

    /** 分类（search / publish / query / media / system） */
    private String category;

    /** 超时时间（毫秒） */
    @Builder.Default
    private long timeoutMs = 10000;

    /** 是否允许 LLM 自动调用（Function Calling）；写操作应设为 false */
    @Builder.Default
    private boolean callableByLLM = true;

    /** 标签（用于分组和过滤） */
    @Builder.Default
    private Set<String> tags = Set.of();

    /** Where the function came from. Remote MCP tools are never re-exported. */
    @Builder.Default
    private Origin origin = Origin.INTERNAL;

    /** Security classification. UNSPECIFIED is fail-closed for LLM/MCP exposure. */
    @Builder.Default
    private Risk risk = Risk.UNSPECIFIED;

    /** Optional Spring Security authority required to invoke this function. */
    private String requiredAuthority;

    /** Static source or MCP connection id. */
    @Builder.Default
    private String sourceId = "internal";

    /** Dynamic sources remain discoverable during outages but fail fast on invocation. */
    @Builder.Default
    private boolean available = true;

    public enum Origin { INTERNAL, MCP }

    public enum Risk { READ_ONLY, WRITE, DESTRUCTIVE, UNSPECIFIED }

    public enum ParamType {
        STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY
    }

    public enum ReturnType {
        STRING, OBJECT, LIST, MAP, VOID
    }

    @Data
    @Builder
    public static class ParamDef {
        private String name;
        @Builder.Default
        private ParamType type = ParamType.STRING;
        private boolean required;
        private String description;
        private Object defaultValue;
    }
}
