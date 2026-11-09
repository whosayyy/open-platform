package com.yin.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录用户信息 —— 存储在 SecurityContext 中，由网关通过请求头透传。
 * <p>
 * 由 WKC AI Platform 自带（vendor 自主工程 {@code wkc-common}），使平台可独立构建与发布。
 */
@Data
@NoArgsConstructor
public class LoginUser implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private Set<String> permissions;
    private Set<String> roles;
    private String token;
    private User user;

    public LoginUser(User user, Set<String> permissions) {
        this.user = user;
        this.userId = user.getId();
        this.username = user.getUsername();
        this.nickname = user.getNickname();
        this.avatar = user.getAvatar();
        this.phone = user.getPhone();
        this.email = user.getEmail();
        this.permissions = permissions;
    }

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (permissions == null || permissions.isEmpty()) {
            return Collections.emptyList();
        }
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @Override
    public String getPassword() {
        return user != null ? user.getPassword() : null;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
