package com.yin.ai.platform.adapter.spring.context;

import com.yin.ai.platform.core.context.CancellationToken;
import com.yin.ai.platform.core.context.ExecutionContext;
import com.yin.domain.LoginUser;
import com.yin.security.util.GetLoginUserUtil;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Creates the pure core context at the Spring web/security boundary. */
public final class SpringExecutionContextFactory {

    public ExecutionContext create(String caller, String operation) {
        LoginUser loginUser = currentLoginUser();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (authentication != null) {
            attributes.put("authorities", authentication.getAuthorities().stream()
                    .map(Object::toString).sorted().toList());
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        if (mdc != null && !mdc.isEmpty()) attributes.put("mdc", Map.copyOf(mdc));

        return new ExecutionContext(
                textOrDefault(MDC.get("traceId"), UUID.randomUUID().toString()),
                MDC.get("conversationId"),
                MDC.get("tenantId"),
                loginUser != null && loginUser.getUserId() != null
                        ? loginUser.getUserId().toString() : null,
                caller,
                operation,
                Instant.now(),
                attributes,
                CancellationToken.NONE);
    }

    private LoginUser currentLoginUser() {
        try {
            return GetLoginUserUtil.getLoginUser();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
