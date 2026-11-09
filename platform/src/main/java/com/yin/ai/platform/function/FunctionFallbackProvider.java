package com.yin.ai.platform.function;

import java.util.Map;
import java.util.Optional;

/**
 * Function 执行回退 SPI。
 * <p>
 * 当 {@link FunctionRegistry} 中找不到指定 Function 时，按 priority 顺序尝试各回退提供者。
 * 业务模块（如 wkc-ai-service）可注册实现来处理非 Function 框架的工具（如 @Tool 注解的 Bean）。
 */
public interface FunctionFallbackProvider {

    /**
     * 尝试处理 FunctionRegistry 中不存在的函数调用。
     *
     * @param functionName 函数名称
     * @param args         函数参数
     * @return 执行结果，如果此提供者不能处理则返回空
     */
    Optional<FunctionResult> tryInvoke(String functionName, Map<String, Object> args);

    /**
     * 优先级，数值越小越先执行。
     */
    default int order() {
        return 0;
    }
}
