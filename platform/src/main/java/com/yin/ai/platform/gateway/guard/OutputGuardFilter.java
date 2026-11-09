package com.yin.ai.platform.gateway.guard;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.filter.FilterChain;
import com.yin.ai.platform.gateway.filter.GatewayFilter;
import com.yin.ai.platform.gateway.filter.StreamFilterChain;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 输出端 Guard 过滤器（order=45）— 对 LLM 响应进行 PII 脱敏。
 * <p>
 * 位置：AuditLog(40) → OutputGuard(45) → Terminal(100)。
 * 职责：对 LLM 返回的文本内容调用 {@link PIIDetector#mask(String)} 脱敏处理。
 * <p>
 * 与 {@code SensitiveWordResponseFilter}（ResponsePostProcessorChain）的分工边界：
 * <ul>
 *   <li>OutputGuardFilter（Gateway Filter Chain）：PII 脱敏（身份证/手机号/银行卡/邮箱/地址）</li>
 *   <li>SensitiveWordResponseFilter（ResponsePostProcessorChain）：内容合规敏感词过滤</li>
 * </ul>
 * 两者在不同层、不同职责，不重叠。
 */
@Slf4j
@Component
public class OutputGuardFilter implements GatewayFilter {

    private final PIIProperties piiProperties;
    private final List<PIIDetector> piiDetectors;

    public OutputGuardFilter(PIIProperties piiProperties, List<PIIDetector> piiDetectors) {
        this.piiProperties = piiProperties;
        this.piiDetectors = piiDetectors;
    }

    @Override
    public String name() {
        return "output-guard";
    }

    @Override
    public int order() {
        return 45;
    }

    @Override
    public boolean enabledByDefault() {
        return piiProperties.isEnabled();
    }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        GatewayResponse response = chain.proceed(request);

        if (!piiProperties.isEnabled()) {
            return response;
        }

        // 对 LLM 响应内容进行 PII 脱敏
        if (response != null && response.getContent() != null) {
            String masked = maskContent(response.getContent());
            if (!masked.equals(response.getContent())) {
                log.debug("[GUARD-OUT] traceId={} PII masked in response", request.getTraceId());
                response.setContent(masked);
            }
        }

        return response;
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        if (!piiProperties.isEnabled()) {
            return chain.proceed(request);
        }

        return chain.proceed(request)
                .map(packet -> {
                    if ("text".equals(packet.getType()) && packet.getContent() instanceof String text) {
                        String masked = maskContent(text);
                        if (!masked.equals(text)) {
                            return StreamPacket.builder()
                                    .type("text")
                                    .content(masked)
                                    .build();
                        }
                    }
                    return packet;
                });
    }

    private String maskContent(String text) {
        if (text == null || text.isBlank() || piiDetectors.isEmpty()) {
            return text;
        }
        String result = text;
        for (PIIDetector detector : piiDetectors) {
            try {
                result = detector.mask(result);
            } catch (Exception e) {
                log.error("[GUARD-OUT] PII mask error: {}", e.getMessage());
            }
        }
        return result;
    }
}
