package com.yin.ai.platform.filter;

import com.yin.domain.LoginUser;
import com.yin.domain.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 信任网关下发的 X-User-Id 和 X-User-Permissions 头（网关已验证 JWT），
 * 构建登录上下文。
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String gatewayUserId = request.getHeader("X-User-Id");
        if (StringUtils.hasText(gatewayUserId)) {
            User user = new User();
            user.setId(Long.parseLong(gatewayUserId));

            Set<String> permissions = Collections.emptySet();
            String permsHeader = request.getHeader("X-User-Permissions");
            if (StringUtils.hasText(permsHeader)) {
                permissions = new HashSet<>(Arrays.asList(permsHeader.split(",")));
            }

            LoginUser loginUser = new LoginUser(user, permissions);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null,
                            permissions.stream()
                                    .map(SimpleGrantedAuthority::new)
                                    .collect(Collectors.toList()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
