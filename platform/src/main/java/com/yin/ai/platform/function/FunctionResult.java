package com.yin.ai.platform.function;

import lombok.Builder;
import lombok.Data;

/**
 * Function 执行结果。
 */
@Data
@Builder
public class FunctionResult {

    /** 是否成功 */
    private boolean success;

    /** 结果数据（类型由 FunctionDefinition.returnType 约定） */
    private Object data;

    /** 失败时的错误码 */
    private String errorCode;

    /** 失败时的错误消息 */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 幂等命中标记 */
    private boolean idempotentHit;

    public static FunctionResult ok(Object data) {
        return FunctionResult.builder().success(true).data(data).build();
    }

    public static FunctionResult fail(String errorCode, String errorMessage) {
        return FunctionResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
