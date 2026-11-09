package com.yin.ai.platform.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yin.ai.platform.prompt.model.VariableDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变量注册表 — 从 prompts/variables.yaml 加载全局变量定义。
 * <p>
 * 单一真相源：所有模板变量的定义和默认值在此注册。
 * {@code PromptComposer} 渲染时校验变量是否存在。
 */
@Slf4j
public class VariableRegistry {

    private static final String VARIABLES_PATH = "prompts/variables.yaml";

    private final Map<String, VariableDef> variables = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * 加载 variables.yaml 中的变量定义到内存。
     */
    @SuppressWarnings("unchecked")
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource(VARIABLES_PATH);
            if (!resource.exists()) {
                log.warn("[PROMPT] variables file not found: {}, using empty registry", VARIABLES_PATH);
                return;
            }
            Map<String, Object> root = yamlMapper.readValue(resource.getInputStream(), Map.class);
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("variables");
            if (list == null) {
                log.warn("[PROMPT] no 'variables' key in {}", VARIABLES_PATH);
                return;
            }
            for (Map<String, Object> entry : list) {
                VariableDef def = yamlMapper.convertValue(entry, VariableDef.class);
                variables.put(def.getName(), def);
            }
            log.info("[PROMPT] loaded {} variable definitions", variables.size());
        } catch (IOException e) {
            log.error("[PROMPT] failed to load variables from {}", VARIABLES_PATH, e);
        }
    }

    public Optional<VariableDef> get(String name) {
        return Optional.ofNullable(variables.get(name));
    }

    public List<VariableDef> listAll() {
        return List.copyOf(variables.values());
    }

    public int size() {
        return variables.size();
    }

    /** 热加载：清空并重新加载 */
    public void refresh() {
        variables.clear();
        load();
    }
}
