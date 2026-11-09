package com.yin.ai.platform.core.router;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 路由上下文，承载调用方传入的 session/user/metadata 信息。
 */
@Data
@Builder
public class RouteContext {

    /** 会话 ID（可选，Chat 场景使用） */
    private String sessionId;

    /** 用户 ID（可选） */
    private Long userId;

    /** 扩展元数据（调用方传入的已知信息，如 resourceId、action 等）。 */
    private Map<String, Object> metadata;

    /** 链路追踪 ID */
    private String traceId;
}
