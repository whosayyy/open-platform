package com.yin.ai.platform.runtime;

import com.yin.ai.platform.gateway.GatewayResponse;
import lombok.Builder;
import lombok.Data;

/**
 * Skill 执行结果。
 */
@Data
@Builder
public class SkillResult {

    /** 是否成功 */
    private boolean success;

    /** 结果数据 */
    private Object data;

    /** 失败的异常 */
    private Throwable error;

    /** 关联的 Gateway 响应（含 token 统计等） */
    private GatewayResponse gatewayResponse;

    /** 执行耗时（毫秒） */
    private long durationMs;

    public static SkillResult ok(Object data) {
        return SkillResult.builder().success(true).data(data).build();
    }

    public static SkillResult fail(Throwable error) {
        return SkillResult.builder().success(false).error(error).build();
    }
}
