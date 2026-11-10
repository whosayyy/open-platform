package com.yin.ai.platform.function;

import com.yin.ai.platform.engine.metrics.AiMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * Function 抽象基类。
 * <p>
 * 统一处理：参数校验 → 幂等检查 → 执行 → 结果记录。
 * 子类只需实现 {@link #doExecute(FunctionInput)}。
 */
@Slf4j
public abstract class AbstractFunction implements Function {

    private AiMetrics aiMetrics;

    /** 注入 AiMetrics（由 FunctionRegistry 或构造器调用）。 */
    public void setAiMetrics(AiMetrics aiMetrics) {
        this.aiMetrics = aiMetrics;
    }

    @Override
    public FunctionResult execute(FunctionInput input) {
        long start = System.currentTimeMillis();
        String fnName = getDefinition().getName();

        log.info("[FUNC] name={} status=START caller={}", fnName, input.getCaller());

        try {
            // 1. 参数校验
            validateInput(input);

            // 2. 执行
            FunctionResult result = doExecute(input);

            // 3. 记录
            result.setDurationMs(System.currentTimeMillis() - start);

            log.info("[FUNC] name={} status=END success={} durationMs={}",
                    fnName, result.isSuccess(), result.getDurationMs());

            if (aiMetrics != null) {
                aiMetrics.recordFunctionExecution(fnName, "success", result.getDurationMs());
            }

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[FUNC] name={} status=FAIL durationMs={}", fnName, duration, e);

            if (aiMetrics != null) {
                aiMetrics.recordFunctionExecution(fnName, "error", duration);
            }

            return FunctionResult.fail(fnName + "_ERROR", e.getMessage());
        }
    }

    /**
     * 参数校验：检查必填参数是否存在。
     */
    protected void validateInput(FunctionInput input) {
        if (getDefinition().getParameters() == null || getDefinition().getParameters().isEmpty()) {
            return;
        }
        for (FunctionDefinition.ParamDef param : getDefinition().getParameters()) {
            if (param.isRequired() && (input.getParameters() == null
                    || !input.getParameters().containsKey(param.getName())
                    || input.getParameters().get(param.getName()) == null)) {
                throw new IllegalArgumentException(
                        "Missing required parameter: " + param.getName()
                        + " for function: " + getDefinition().getName());
            }
        }
    }

    /**
     * 子类：核心执行逻辑。
     */
    protected abstract FunctionResult doExecute(FunctionInput input);
}
