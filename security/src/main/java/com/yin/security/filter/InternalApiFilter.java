package com.yin.security.filter;

import com.yin.security.util.InternalApiSignUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 内部 API 签名校验过滤器。
 * <p>
 * 对 {@code /internal/**} 路径校验 HMAC-SHA256 签名，防止：
 * <ul>
 *   <li>外部访问内部 Feign 端点</li>
 *   <li>重放攻击（时间戳须在 5 分钟内）</li>
 *   <li>请求头伪造</li>
 * </ul>
 * <p>
 * 由 WKC AI Platform 自带（vendor 自主工程 {@code wkc-common}）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalApiFilter extends OncePerRequestFilter {

    @Value("${internal-api.secret:}")
    private String internalApiSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (isInternalPath(uri)) {
            if (!StringUtils.hasText(internalApiSecret)) {
                log.warn("Internal API secret not configured, skipping HMAC validation for: {}", uri);
                filterChain.doFilter(request, response);
                return;
            }

            String sign = request.getHeader("X-Internal-Sign");
            String timestamp = request.getHeader("X-Internal-Timestamp");

            if (!StringUtils.hasText(sign) || !StringUtils.hasText(timestamp)) {
                log.warn("Internal API access denied for path: {} - missing signature headers", uri);
                sendForbidden(response, "Internal API access denied: missing signature");
                return;
            }

            String callingServiceId = request.getHeader("X-Internal-Service-Id");
            if (!StringUtils.hasText(callingServiceId)) {
                log.warn("Internal API access denied for path: {} - missing service ID header", uri);
                sendForbidden(response, "Internal API access denied: missing service ID");
                return;
            }

            if (!InternalApiSignUtils.verify(sign, timestamp, callingServiceId, internalApiSecret)) {
                log.warn("Internal API access denied for path: {} - invalid signature or expired "
                        + "timestamp (serviceId={})", uri, callingServiceId);
                sendForbidden(response, "Internal API access denied: invalid signature");
                return;
            }

            log.debug("Internal API access verified for path: {}, from service: {}",
                    uri, callingServiceId);
        }

        filterChain.doFilter(request, response);
    }

    /** 判断是否为需要校验的内部 API 路径。 */
    private boolean isInternalPath(String uri) {
        return uri.contains("/internal/")
                || (uri.startsWith("/api/") && uri.contains("/internal"));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"" + message + "\"}");
    }
}
