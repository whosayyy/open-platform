package com.yin.ai.platform.engine.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring Security ContextHolderStrategy 切换。
 * <p>
 * 从 MODE_THREADLOCAL 改为 MODE_INHERITABLETHREADLOCAL，
 * 确保子线程能继承父线程的 SecurityContext。
 * <p>
 * 注意：InheritableThreadLocal 在虚拟线程中仍然不完全可靠。
 * 最终保障是 ExecutionEngine 的 injectContext() 显式注入。
 */
@Configuration
public class SecurityContextPropagationConfig {

    @PostConstruct
    public void configureStrategy() {
        SecurityContextHolder.setStrategyName(
                SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }
}
