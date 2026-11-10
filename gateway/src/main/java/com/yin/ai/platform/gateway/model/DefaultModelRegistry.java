package com.yin.ai.platform.gateway.model;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认模型注册中心实现。
 * <p>
 * 使用 ConcurrentHashMap 保证线程安全，支持动态注册/注销。
 */
@Slf4j
public class DefaultModelRegistry implements ModelRegistry {

    private final Map<String, ModelDefinition> definitions = new ConcurrentHashMap<>();
    private volatile String defaultModelName;

    public DefaultModelRegistry(List<ModelDefinition> initialDefinitions, String defaultModelName) {
        if (defaultModelName == null || defaultModelName.isBlank()) {
            throw new IllegalArgumentException("defaultModelName is required");
        }
        this.defaultModelName = defaultModelName;

        for (ModelDefinition def : initialDefinitions) {
            definitions.put(def.getName(), def);
        }

        // 验证默认模型存在于注册表中
        if (!definitions.containsKey(defaultModelName)) {
            log.warn("[MODEL] default model '{}' not found in registered models: {}",
                    defaultModelName, definitions.keySet());
        }

        long enabledCount = definitions.values().stream().filter(ModelDefinition::isEnabled).count();
        log.info("[MODEL] initialized {} models ({} enabled), default={}",
                definitions.size(), enabledCount, defaultModelName);
    }

    @Override
    public ModelDefinition getDefault() {
        ModelDefinition def = definitions.get(defaultModelName);
        if (def != null && def.isEnabled()) {
            return def;
        }
        // 默认模型不可用时，回退到第一个启用的模型
        return listEnabled().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled model found (default='" + defaultModelName + "')"));
    }

    @Override
    public Optional<ModelDefinition> get(String modelName) {
        if (modelName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(modelName));
    }

    @Override
    public List<ModelDefinition> listByProvider(String provider) {
        return definitions.values().stream()
                .filter(d -> d.getProvider().equalsIgnoreCase(provider))
                .toList();
    }

    @Override
    public List<ModelDefinition> listAll() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(ModelDefinition::getName))
                .toList();
    }

    @Override
    public List<ModelDefinition> listEnabled() {
        return definitions.values().stream()
                .filter(ModelDefinition::isEnabled)
                .sorted(Comparator.comparing(ModelDefinition::getName))
                .toList();
    }

    @Override
    public boolean supports(String modelName) {
        ModelDefinition def = definitions.get(modelName);
        return def != null && def.isEnabled();
    }

    @Override
    public void register(ModelDefinition definition) {
        definitions.put(definition.getName(), definition);
        log.info("[MODEL] registered model '{}' (provider={}, enabled={})",
                definition.getName(), definition.getProvider(), definition.isEnabled());
    }

    @Override
    public void unregister(String modelName) {
        ModelDefinition removed = definitions.remove(modelName);
        if (removed != null) {
            log.info("[MODEL] unregistered model '{}'", modelName);
        }
    }

    @Override
    public int size() {
        return definitions.size();
    }

    public void setDefaultModelName(String modelName) {
        if (!definitions.containsKey(modelName)) {
            throw new IllegalArgumentException("Unknown model: " + modelName);
        }
        this.defaultModelName = modelName;
        log.info("[MODEL] default model changed to '{}'", modelName);
    }

    Map<String, ModelDefinition> definitions() {
        return Collections.unmodifiableMap(definitions);
    }
}
