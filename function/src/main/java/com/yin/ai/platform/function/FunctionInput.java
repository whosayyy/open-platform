package com.yin.ai.platform.function;

import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.context.SecurityContext;

import java.util.Map;
import java.util.Set;

/**
 * Function 标准化输入。
 */
@Data
@Builder
public class FunctionInput {

    /** 调用参数（key-value） */
    private Map<String, Object> parameters;

    /** 幂等键（可选，用于写操作的重复调用检测） */
    private String requestId;

    /** 链路追踪 ID */
    private String traceId;

    /** 调用方标识（skillName 或 serviceName） */
    private String caller;

    /** Tenant and user identities remain independent for authorization. */
    private String tenantId;
    private String userId;

    @Builder.Default
    private Set<String> roles = Set.of();

    @Builder.Default
    private Set<String> permissions = Set.of();

    /** SecurityContext（用于跨线程恢复） */
    private SecurityContext securityContext;

    public static FunctionInput of(Map<String, Object> params) {
        return FunctionInput.builder().parameters(params).build();
    }
}
