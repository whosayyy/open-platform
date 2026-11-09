package com.yin.ai.platform.gateway.filter;

import com.yin.ai.platform.gateway.GatewayRequest;
import com.yin.ai.platform.gateway.GatewayResponse;
import com.yin.ai.platform.gateway.stream.StreamPacket;
import com.yin.ai.platform.engine.ExecutionContext;
import com.yin.ai.platform.engine.ExecutionContextHolder;
import com.yin.ai.platform.gateway.tenant.identity.ResolvedTenant;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolutionContext;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolutionException;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolutionProperties;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolutionSource;
import com.yin.ai.platform.gateway.tenant.identity.TenantResolver;
import com.yin.ai.platform.rollout.RoadmapFeatureProperties;
import com.yin.domain.LoginUser;
import com.yin.security.util.GetLoginUserUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class TenantContextFilter implements GatewayFilter {

    private final TenantResolver tenantResolver;
    private final TenantResolutionProperties properties;
    private final RoadmapFeatureProperties rollout;

    public TenantContextFilter(TenantResolver tenantResolver,
                               TenantResolutionProperties properties,
                               RoadmapFeatureProperties rollout) {
        this.tenantResolver = tenantResolver;
        this.properties = properties;
        this.rollout = rollout;
    }

    @Override public String name() { return "tenant-context"; }
    @Override public int order() { return 5; }

    @Override
    public GatewayResponse filter(GatewayRequest request, FilterChain chain) {
        ResolvedTenant tenant = resolve(request);
        String previousTenant = MDC.get("tenantId");
        if (tenant.isResolved()) {
            MDC.put("tenantId", tenant.tenantId());
        }
        try {
            return chain.proceed(request);
        } finally {
            restoreMdc(previousTenant);
        }
    }

    @Override
    public Flux<StreamPacket> filterStream(GatewayRequest request, StreamFilterChain chain) {
        return Flux.defer(() -> {
            ResolvedTenant tenant = resolve(request);
            Flux<StreamPacket> result = chain.proceed(request);
            return tenant.isResolved()
                    ? result.contextWrite(context -> context.put("tenantId", tenant.tenantId()))
                    : result;
        });
    }

    private ResolvedTenant resolve(GatewayRequest request) {
        populateAuthenticatedIdentity(request);
        ExecutionContext executionContext = ExecutionContextHolder.getOptional().orElse(null);
        TenantResolutionContext context = new TenantResolutionContext(
                request.getTenantId(), extractJwtTenant(executionContext),
                tenantFromMdcOrContext(executionContext), request.getUserId(),
                properties.isUserIdFallbackEnabled());
        try {
            ResolvedTenant resolved = tenantResolver.resolve(context);
            if (!resolved.isResolved() && rollout.getTenantIsolation().isEnforcing()) {
                throw new TenantResolutionException("Tenant context is required", null);
            }
            request.setTenantId(resolved.tenantId());
            request.setUserId(resolved.userId());
            return resolved;
        } catch (TenantResolutionException conflict) {
            if (rollout.getTenantIsolation().isEnforcing()) {
                throw conflict;
            }
            log.warn("[TENANT-CTX] shadow conflict caller={} traceId={}: {}",
                    request.getCaller(), request.getTraceId(), conflict.getMessage());
            request.setTenantId(conflict.getAuthoritativeTenantId());
            return conflict.getAuthoritativeTenantId() == null
                    ? ResolvedTenant.unresolved(request.getUserId())
                    : new ResolvedTenant(conflict.getAuthoritativeTenantId(), request.getUserId(),
                    TenantResolutionSource.JWT, false);
        }
    }

    private void populateAuthenticatedIdentity(GatewayRequest request) {
        LoginUser loginUser = null;
        try {
            loginUser = GetLoginUserUtil.getLoginUser();
            if (loginUser == null && request.getUserId() == null) {
                Long userId = GetLoginUserUtil.getUserId();
                if (userId != null) {
                    request.setUserId(userId.toString());
                }
            }
        } catch (Exception error) {
            log.debug("[TENANT-CTX] authenticated identity unavailable: {}", error.getMessage());
        }
        if (loginUser == null) {
            ExecutionContext executionContext = ExecutionContextHolder.getOptional().orElse(null);
            loginUser = executionContext != null ? executionContext.loginUser() : null;
            if (request.getUserId() == null && executionContext != null && executionContext.userId() != null) {
                request.setUserId(executionContext.userId().toString());
            }
        }
        populateFromLoginUser(request, loginUser);
    }

    private void populateFromLoginUser(GatewayRequest request, LoginUser loginUser) {
        if (loginUser == null) return;
        if (request.getUserId() == null && loginUser.getUserId() != null) {
            request.setUserId(loginUser.getUserId().toString());
        }
        if (request.getRoles() == null) {
            request.setRoles(loginUser.getRoles() == null ? Set.of() : Set.copyOf(loginUser.getRoles()));
        }
        if (request.getPermissions() == null) {
            request.setPermissions(loginUser.getPermissions() == null
                    ? Set.of() : Set.copyOf(loginUser.getPermissions()));
        }
    }

    private String tenantFromMdcOrContext(ExecutionContext executionContext) {
        String tenantId = MDC.get("tenantId");
        if (tenantId != null && !tenantId.isBlank()) return tenantId;
        if (executionContext == null) return null;
        tenantId = executionContext.mdcContext() != null
                ? executionContext.mdcContext().get("tenantId") : null;
        if ((tenantId == null || tenantId.isBlank()) && executionContext.attributes() != null) {
            Object attribute = executionContext.attributes().get("tenantId");
            tenantId = attribute != null ? attribute.toString() : null;
        }
        return tenantId;
    }

    private String extractJwtTenant(ExecutionContext executionContext) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null && executionContext != null
                    && executionContext.securityContext() != null) {
                authentication = executionContext.securityContext().getAuthentication();
            }
            if (authentication == null) {
                return null;
            }
            String claim = invokeClaimAccessor(authentication.getPrincipal());
            return claim != null ? claim : invokeClaimAccessor(authentication.getDetails());
        } catch (Exception error) {
            log.debug("[TENANT-CTX] JWT tenant claim unavailable: {}", error.getMessage());
            return null;
        }
    }

    private String invokeClaimAccessor(Object source) throws Exception {
        if (source == null) {
            return null;
        }
        try {
            Method method = source.getClass().getMethod("getClaimAsString", String.class);
            Object value = method.invoke(source, properties.getJwtClaimName());
            return value == null ? null : value.toString();
        } catch (NoSuchMethodException ignored) {
            Method method;
            try {
                method = source.getClass().getMethod("getClaims");
            } catch (NoSuchMethodException absent) {
                return null;
            }
            Object claims = method.invoke(source);
            if (claims instanceof Map<?, ?> map) {
                Object value = map.get(properties.getJwtClaimName());
                return value == null ? null : value.toString();
            }
            return null;
        }
    }

    private void restoreMdc(String previousTenant) {
        if (previousTenant == null) {
            MDC.remove("tenantId");
        } else {
            MDC.put("tenantId", previousTenant);
        }
    }
}
