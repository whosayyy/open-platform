package com.yin.ai.platform.prompt;

import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.model.PromptTemplate;
import com.yin.ai.platform.prompt.model.VariableDef;
import com.yin.ai.platform.prompt.store.PromptStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Prompt Center 默认实现。
 * <p>
 * 委托 {@link PromptStore} 加载 Fragment/Template，
 * 委托 {@link VariableRegistry} 管理变量定义。
 */
@Slf4j
public class DefaultPromptCenter implements PromptCenter {

    private final PromptStore promptStore;
    private final VariableRegistry variableRegistry;

    public DefaultPromptCenter(PromptStore promptStore, VariableRegistry variableRegistry) {
        this.promptStore = promptStore;
        this.variableRegistry = variableRegistry;
    }

    // —— Fragment ——

    @Override
    public Optional<PromptFragment> getFragment(String name, String version) {
        long start = System.currentTimeMillis();
        Optional<PromptFragment> result = promptStore.loadFragment(name, version);
        long duration = System.currentTimeMillis() - start;
        log.debug("[PROMPT] action=LOAD_FRAGMENT name={} version={} status={} duration={} ",
                name, version != null ? version : "latest",
                result.isPresent() ? "SUCCESS" : "NOT_FOUND",
                duration);
        return result;
    }

    @Override
    public Optional<PromptFragment> getLatestFragment(String name) {
        return getFragment(name, null);
    }

    @Override
    public List<PromptFragment> listFragments() {
        List<PromptFragment> result = new ArrayList<>();
        for (String name : promptStore.listFragmentNames()) {
            promptStore.loadLatestFragment(name).ifPresent(result::add);
        }
        return result;
    }

    // —— Template ——

    @Override
    public Optional<PromptTemplate> getTemplate(String name, String version) {
        long start = System.currentTimeMillis();
        Optional<PromptTemplate> result = promptStore.loadTemplate(name, version);
        long duration = System.currentTimeMillis() - start;
        log.debug("[PROMPT] action=LOAD_TEMPLATE name={} version={} status={}",
                name, version != null ? version : "latest",
                result.isPresent() ? "SUCCESS" : "NOT_FOUND");
        return result;
    }

    @Override
    public Optional<PromptTemplate> getLatestTemplate(String name) {
        return getTemplate(name, null);
    }

    @Override
    public List<PromptTemplate> listTemplates() {
        List<PromptTemplate> result = new ArrayList<>();
        for (String name : promptStore.listTemplateNames()) {
            promptStore.loadLatestTemplate(name).ifPresent(result::add);
        }
        return result;
    }

    // —— Variable ——

    @Override
    public Optional<VariableDef> getVariable(String name) {
        return variableRegistry.get(name);
    }

    @Override
    public List<VariableDef> listVariables() {
        return variableRegistry.listAll();
    }

    // —— Cache ——

    @Override
    public void refreshCache() {
        log.info("[PROMPT] action=CACHE_REFRESH reason=manual");
        promptStore.refresh();
        variableRegistry.refresh();
    }
}
