package com.yin.ai.platform.prompt.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 变量定义 — 描述 Prompt 模板中可用的变量及其解析规则。
 * <p>
 * 全局变量定义在 prompts/variables.yaml，由 {@code VariableRegistry} 加载。
 * 变量通过 {@code VariableResolver} 按 source 类型分发解析。
 */
@Data
public class VariableDef {

    /** 变量名（camelCase） */
    private String name;

    /** 类型：string | long | datetime | list | object */
    private String type;

    /** 日期格式化模式（仅 datetime 类型） */
    private String format;

    /** 变量来源：system | memory | rag | security | function | config | request */
    private String source;

    /** 解析器引用（source=memory/rag/security/function 时指定 method/bean） */
    private String provider;

    /** 描述 */
    private String description;

    /** 默认值（YAML 中 key 为 "default"） */
    @JsonProperty("default")
    private Object defaultValue;

    /** 是否可缓存 */
    private boolean cacheable;
}
