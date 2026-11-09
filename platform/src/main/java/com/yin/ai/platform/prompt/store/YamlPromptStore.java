package com.yin.ai.platform.prompt.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.model.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML 文件 Prompt 存储 — 从 classpath:/prompts/ 加载 Fragment 和 Template。
 * <p>
 * Fragment 路径：prompts/fragments/{name}.yaml
 * Template 路径：prompts/templates/standalone/{name}.yaml
 * 历史版本归档：prompts/versions/{name}/
 */
@Slf4j
public class YamlPromptStore implements PromptStore {

    private static final String FRAGMENTS_BASE = "prompts/fragments/";
    private static final String TEMPLATES_BASE = "prompts/templates/standalone/";

    private final Map<String, PromptFragment> fragmentCache = new ConcurrentHashMap<>();
    private final Map<String, PromptTemplate> templateCache = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public Optional<PromptFragment> loadFragment(String name, String version) {
        if (version == null || version.isEmpty() || "latest".equals(version)) {
            return loadLatestFragment(name);
        }

        String cacheKey = name + "@" + version;
        PromptFragment cached = fragmentCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);

        // 先加载当前版本
        Optional<PromptFragment> fragment = loadFragmentFile(name);
        if (fragment.isPresent() && version.equals(fragment.get().getVersion())) {
            fragmentCache.put(cacheKey, fragment.get());
            return fragment;
        }

        // 查找历史版本
        return loadFragmentVersion(name, version);
    }

    @Override
    public Optional<PromptFragment> loadLatestFragment(String name) {
        Optional<PromptFragment> fragment = loadFragmentFile(name);
        fragment.ifPresent(f -> fragmentCache.put(name + "@" + f.getVersion(), f));
        return fragment;
    }

    @Override
    public List<String> listFragmentNames() {
        // 类路径不支持目录列举，从缓存获取
        return new ArrayList<>(fragmentCache.keySet().stream()
                .map(k -> k.split("@")[0])
                .distinct()
                .toList());
    }

    @Override
    public Optional<PromptTemplate> loadTemplate(String name, String version) {
        if (version == null || version.isEmpty() || "latest".equals(version)) {
            return loadLatestTemplate(name);
        }

        String cacheKey = "tpl:" + name + "@" + version;
        PromptTemplate cached = templateCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);

        Optional<PromptTemplate> template = loadTemplateFile(name);
        if (template.isPresent() && version.equals(template.get().getVersion())) {
            templateCache.put(cacheKey, template.get());
            return template;
        }
        return Optional.empty();
    }

    @Override
    public Optional<PromptTemplate> loadLatestTemplate(String name) {
        Optional<PromptTemplate> template = loadTemplateFile(name);
        template.ifPresent(t -> templateCache.put("tpl:" + name + "@" + t.getVersion(), t));
        return template;
    }

    @Override
    public List<String> listTemplateNames() {
        return new ArrayList<>(templateCache.keySet().stream()
                .map(k -> k.startsWith("tpl:") ? k.substring(4).split("@")[0] : k.split("@")[0])
                .distinct()
                .toList());
    }

    @Override
    public void refresh() {
        fragmentCache.clear();
        templateCache.clear();
        log.info("[PROMPT] store cache refreshed");
    }

    // ——— 内部 ———

    private Optional<PromptFragment> loadFragmentFile(String name) {
        String path = FRAGMENTS_BASE + name + ".yaml";
        try {
            Resource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.debug("[PROMPT] fragment not found: {}", path);
                return Optional.empty();
            }
            PromptFragment fragment = yamlMapper.readValue(resource.getInputStream(), PromptFragment.class);
            log.debug("[PROMPT] loaded fragment: {}@{}", fragment.getName(), fragment.getVersion());
            return Optional.of(fragment);
        } catch (IOException e) {
            log.error("[PROMPT] failed to load fragment: {}", path, e);
            return Optional.empty();
        }
    }

    private Optional<PromptFragment> loadFragmentVersion(String name, String version) {
        String path = "prompts/versions/" + name + "/" + version + ".yaml";
        try {
            Resource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("[PROMPT] fragment version not found: {}@{}", name, version);
                return Optional.empty();
            }
            PromptFragment fragment = yamlMapper.readValue(resource.getInputStream(), PromptFragment.class);
            fragmentCache.put(name + "@" + version, fragment);
            return Optional.of(fragment);
        } catch (IOException e) {
            log.error("[PROMPT] failed to load fragment version: {}@{}", name, version, e);
            return Optional.empty();
        }
    }

    private Optional<PromptTemplate> loadTemplateFile(String name) {
        String path = TEMPLATES_BASE + name + ".yaml";
        try {
            Resource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.debug("[PROMPT] template not found: {}", path);
                return Optional.empty();
            }
            PromptTemplate template = yamlMapper.readValue(resource.getInputStream(), PromptTemplate.class);
            log.debug("[PROMPT] loaded template: {}@{}", template.getName(), template.getVersion());
            return Optional.of(template);
        } catch (IOException e) {
            log.error("[PROMPT] failed to load template: {}", path, e);
            return Optional.empty();
        }
    }
}
