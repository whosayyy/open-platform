package com.yin.ai.platform.legacy.config;

import com.yin.ai.platform.legacy.LegacyAdapter;
import com.yin.ai.platform.core.router.IntentRouter;
import com.yin.ai.platform.runtime.SkillRuntime;
import com.yin.ai.platform.core.skill.resolver.DependencyResolver;
import com.yin.ai.platform.core.skill.selector.SkillSelector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V1 兼容适配模块 Spring 配置。
 */
@Configuration
public class LegacyConfiguration {

    @Bean
    public LegacyAdapter legacyAdapter(IntentRouter intentRouter,
                                        SkillRuntime skillRuntime,
                                        SkillSelector skillSelector,
                                        DependencyResolver dependencyResolver) {
        return new LegacyAdapter(intentRouter, skillRuntime, skillSelector, dependencyResolver);
    }
}
