package com.yin.ai.platform.engine;

import com.yin.domain.LoginUser;
import com.yin.security.util.GetLoginUserUtil;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 执行上下文 — 封装一次 AI 请求的所有上下文信息。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>不可变（record），线程安全</li>
 *   <li>不依赖 ThreadLocal，通过 ExecutionContextHolder 获取</li>
 *   <li>包含 Spring Security、Trace、MDC 所需的所有字段</li>
 * </ul>
 *
 * @param traceId         链路追踪 ID
 * @param conversationId  对话 ID（多轮对话场景）
 * @param userId          用户 ID
 * @param loginUser       完整登录用户信息
 * @param securityContext Spring Security 上下文
 * @param mdcContext      SLF4J MDC 键值对
 * @param caller          调用方标识（skillName）
 * @param operation       操作名称（chat/summary/review/search）
 * @param createdAt       创建时间
 * @param attributes      扩展属性
 */
@Deprecated(since = "0.0.1", forRemoval = false)
public record ExecutionContext(
        String traceId,
        String conversationId,
        Long userId,
        LoginUser loginUser,
        org.springframework.security.core.context.SecurityContext securityContext,
        Map<String, String> mdcContext,
        String caller,
        String operation,
        Instant createdAt,
        Map<String, Object> attributes
) {

    /** 工厂：从当前 HTTP 请求创建 */
    public static ExecutionContext fromCurrentRequest() {
        LoginUser loginUser = null;
        try {
            loginUser = GetLoginUserUtil.getLoginUser();
        } catch (Exception ignored) {
            // 非 Web 上下文（MQ 监听、定时任务等）
        }

        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }

        return new ExecutionContext(
                traceId,
                MDC.get("conversationId"),
                loginUser != null ? loginUser.getUserId() : null,
                loginUser,
                SecurityContextHolder.getContext(),
                MDC.getCopyOfContextMap() != null
                        ? new HashMap<>(MDC.getCopyOfContextMap())
                        : new HashMap<>(),
                null,
                null,
                Instant.now(),
                new HashMap<>()
        );
    }

    /** 派生：设置 caller */
    public ExecutionContext withCaller(String caller) {
        return new ExecutionContext(
                traceId, conversationId, userId, loginUser,
                securityContext, mdcContext, caller, operation,
                createdAt, new HashMap<>(attributes)
        );
    }

    /** 派生：设置 operation */
    public ExecutionContext withOperation(String operation) {
        return new ExecutionContext(
                traceId, conversationId, userId, loginUser,
                securityContext, mdcContext, caller, operation,
                createdAt, new HashMap<>(attributes)
        );
    }

    /** 派生：添加属性 */
    public ExecutionContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new ExecutionContext(
                traceId, conversationId, userId, loginUser,
                securityContext, mdcContext, caller, operation,
                createdAt, newAttrs
        );
    }
}
