package com.yin.ai.platform.starter.skill;

import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.runtime.PromptComposer;
import com.yin.ai.platform.adapter.skill.repository.ClasspathSkillRepository;
import com.yin.ai.platform.core.skill.DefaultSkillLoader;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.repository.CompositeSkillRepository;
import com.yin.ai.platform.core.skill.repository.SkillRepository;
import com.yin.ai.platform.core.skill.resolver.DependencyResolver;
import com.yin.ai.platform.core.skill.selector.IntentSkillSelector;
import com.yin.ai.platform.core.skill.selector.RuleSkillSelector;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.prompt.PromptCenter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Skill 模块统一配置 — 合并原 LoaderConfiguration 和 RuntimeConfiguration 中 Skill 相关部分。
 * <p>
 * 装配链：SkillRepository → SkillLoader → SkillSelector → DependencyResolver → PromptComposer。
 * 所有组件通过 SPI 接口 + Spring Bean 自动发现，支持独立替换。
 * <p>
 * Repository 采用组合模式：{@link CompositeSkillRepository} 合并所有 SkillRepository Bean
 * （Classpath + MinIO + ...），MinIO 优先（动态上传覆盖内置），Classpath 兜底。
 */
@Configuration
@EnableConfigurationProperties(SkillConfigurationProperties.class)
public class SkillConfiguration {

    // ——— Repository ———

    @Bean
    public SkillRepository classpathSkillRepository() {
        return new ClasspathSkillRepository();
    }

    @Bean
    @Primary
    public SkillRepository skillRepository(List<SkillRepository> repositories) {
        if (repositories.size() == 1) {
            return repositories.get(0);
        }
        return new CompositeSkillRepository(repositories);
    }

    // ——— Loader ———

    @Bean
    public SkillLoader skillLoader(SkillRepository skillRepository) {
        return new DefaultSkillLoader(skillRepository);
    }

    // ——— Selectors ———

    @Bean
    @Primary
    public IntentSkillSelector intentSkillSelector(SkillConfigurationProperties props) {
        return new IntentSkillSelector(props.getIntentMappings(), props.getDefaultSkills());
    }

    @Bean
    public RuleSkillSelector ruleSkillSelector(SkillLoader skillLoader) {
        return new RuleSkillSelector(skillLoader);
    }

    // ——— Resolver ———

    @Bean
    public DependencyResolver dependencyResolver(SkillLoader skillLoader) {
        return new DependencyResolver(skillLoader);
    }

    // ——— Composer ———

    @Bean
    public PromptComposer promptComposer(FunctionRegistry functionRegistry,
                                          SessionMemoryManager sessionMemoryManager,
                                          PromptCenter promptCenter) {
        return new PromptComposer(functionRegistry, sessionMemoryManager, promptCenter);
    }
}
