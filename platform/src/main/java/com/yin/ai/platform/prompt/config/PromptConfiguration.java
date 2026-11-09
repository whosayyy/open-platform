package com.yin.ai.platform.prompt.config;

import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.prompt.DefaultPromptCenter;
import com.yin.ai.platform.prompt.PromptCenter;
import com.yin.ai.platform.prompt.VariableRegistry;
import com.yin.ai.platform.prompt.VariableResolver;
import com.yin.ai.platform.prompt.experiment.PromptSelector;
import com.yin.ai.platform.prompt.experiment.StableHashPromptSelector;
import com.yin.ai.platform.prompt.store.PromptStore;
import com.yin.ai.platform.prompt.store.YamlPromptStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt Center 模块统一配置。
 * <p>
 * 装配链：PromptStore → VariableRegistry → VariableResolver → PromptCenter。
 * 通过 {@code ai.prompt.store-type=yaml} 激活 YamlPromptStore。
 */
@Configuration
@EnableConfigurationProperties(PromptCenterProperties.class)
public class PromptConfiguration {

    // ——— Store ———

    @Bean
    @ConditionalOnProperty(name = "ai.prompt.store-type", havingValue = "yaml", matchIfMissing = true)
    public PromptStore promptStore() {
        return new YamlPromptStore();
    }

    // ——— Variable ———

    @Bean
    public VariableRegistry variableRegistry() {
        VariableRegistry registry = new VariableRegistry();
        registry.load();
        return registry;
    }

    @Bean
    public VariableResolver variableResolver(VariableRegistry variableRegistry,
                                             SessionMemoryManager sessionMemoryManager,
                                             FunctionRegistry functionRegistry) {
        return new VariableResolver(variableRegistry, sessionMemoryManager, functionRegistry);
    }

    // ——— PromptCenter Facade ———

    @Bean
    public PromptCenter promptCenter(PromptStore promptStore, VariableRegistry variableRegistry) {
        return new DefaultPromptCenter(promptStore, variableRegistry);
    }

    @Bean
    public PromptSelector promptSelector() {
        return new StableHashPromptSelector();
    }
}
