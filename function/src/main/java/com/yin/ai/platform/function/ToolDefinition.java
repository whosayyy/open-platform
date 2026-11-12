package com.yin.ai.platform.function;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 工具元数据：持有名称、描述、参数定义及反射调用所需的 bean 和方法引用。
 *
 * @deprecated 由 {@link FunctionDefinition}（Builder 模式）替代。
 *             新 Function 应使用 {@link AbstractFunction#getDefinition()} 返回 FunctionDefinition，
 *             而非通过反射注册 ToolDefinition。
 */
@Deprecated
public class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, ToolParamDef> parameters;
    private final Object targetBean;
    private final Method method;

    public ToolDefinition(String name, String description,
                          Map<String, ToolParamDef> parameters,
                          Object targetBean, Method method) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.targetBean = targetBean;
        this.method = method;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, ToolParamDef> getParameters() { return parameters; }
    public Object getTargetBean() { return targetBean; }
    public Method getMethod() { return method; }

    public record ToolParamDef(String name, String type, boolean required, String description) {}
}
