package com.yin.security.util;

import com.yin.domain.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户的统一入口。
 * <p>
 * 由 WKC AI Platform 自带（vendor 自主工程 {@code wkc-common}）。
 */
public final class GetLoginUserUtil {

    private GetLoginUserUtil() {
    }

    /**
     * 从 SecurityContext 获取当前登录用户。
     */
    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser) {
            return (LoginUser) authentication.getPrincipal();
        }
        return null;
    }

    /**
     * 获取当前用户 ID。
     * <p>
     * SecurityContext 缺失时降级到 {@link UserContext}（ScopedValue）方式。
     */
    public static Long getUserId() {
        LoginUser loginUser = getLoginUser();
        if (loginUser != null) {
            return loginUser.getUserId();
        }
        return UserContext.getUser();
    }

    /**
     * 获取当前用户名。
     */
    public static String getUsername() {
        LoginUser loginUser = getLoginUser();
        return loginUser != null ? loginUser.getUsername() : null;
    }
}
