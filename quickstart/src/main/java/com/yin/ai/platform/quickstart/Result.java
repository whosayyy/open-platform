package com.yin.ai.platform.quickstart;

import java.util.Map;
import java.util.Optional;

/**
 * 业务开发者看到的唯一返回类型。
 * <p>
 * 刻意不暴露 {@code AgentResult} / {@code GatewayResponse} / {@code ExecutionResult}
 * 等平台内部类型：业务代码只关心「成没成功、文本是什么、为什么失败、花了多少」。
 * <p>
 * 流式场景下每个 {@code Result} 承载一段增量文本（{@link #delta()} 为 true）。
 */
public record Result(
        boolean ok,
        String text,
        String error,
        boolean delta,
        Map<String, Object> metadata
) {

    public Result {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 成功结果（非流式）。 */
    public static Result success(String text) {
        return new Result(true, text, null, false, Map.of());
    }

    /** 成功结果（非流式，带元数据）。 */
    public static Result success(String text, Map<String, Object> metadata) {
        return new Result(true, text, null, false, metadata);
    }

    /** 流式增量片段。 */
    public static Result chunk(String deltaText) {
        return new Result(true, deltaText, null, true, Map.of());
    }

    /** 失败结果。 */
    public static Result failure(String error) {
        return new Result(false, "", error == null ? "unknown error" : error, false, Map.of());
    }

    /** 失败结果，保留异常类型信息便于排查。 */
    public static Result failure(Throwable error) {
        if (error == null) {
            return failure("unknown error");
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return new Result(false, "", message, false, Map.of("errorType", error.getClass().getName()));
    }

    public boolean failed() {
        return !ok;
    }

    /** 错误信息，便于业务侧可选地读取。 */
    public Optional<String> errorOptional() {
        return Optional.ofNullable(error);
    }

    /** 本次调用使用/消耗的模型名（若执行方提供）。 */
    public Optional<String> model() {
        Object value = metadata.get("model");
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
}
