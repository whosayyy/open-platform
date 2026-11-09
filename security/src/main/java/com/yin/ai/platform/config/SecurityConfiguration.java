package com.yin.ai.platform.config;

import com.yin.ai.platform.filter.JwtAuthenticationTokenFilter;
import com.yin.security.filter.InternalApiFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.DispatcherType;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private final JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;
    private final InternalApiFilter internalApiFilter;

    public SecurityConfiguration(JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter,
                                 InternalApiFilter internalApiFilter) {
        this.jwtAuthenticationTokenFilter = jwtAuthenticationTokenFilter;
        this.internalApiFilter = internalApiFilter;
    }

    @Bean
    public FilterRegistrationBean<InternalApiFilter> internalApiFilterRegistration() {
        FilterRegistrationBean<InternalApiFilter> registration = new FilterRegistrationBean<>(internalApiFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/static/**", "/*.html").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(internalApiFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
