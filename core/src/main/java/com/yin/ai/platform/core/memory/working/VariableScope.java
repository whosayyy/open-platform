package com.yin.ai.platform.core.memory.working;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 变量作用域 — 支持嵌套调用时的词法作用域链。
 * <p>
 * 查找顺序：当前作用域 → 父作用域 → ... → global scope。
 * put 只写入当前作用域，不向上传播。
 * snapshot() 用于创建 ResumePoint 时捕获当前状态。
 */
public class VariableScope {

    private final String scopeName;
    private final VariableScope parent;
    private final Map<String, Object> variables;
    private final Instant createdAt;

    public VariableScope(String scopeName, VariableScope parent) {
        this.scopeName = scopeName;
        this.parent = parent;
        this.variables = new HashMap<>();
        this.createdAt = Instant.now();
    }

    public VariableScope(String scopeName) {
        this(scopeName, null);
    }

    public String getScopeName() {
        return scopeName;
    }

    public VariableScope getParent() {
        return parent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void put(String key, Object value) {
        variables.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = variables.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    public Optional<Object> resolve(String key) {
        Object value = variables.get(key);
        if (value != null) {
            return Optional.of(value);
        }
        if (parent != null) {
            return parent.resolve(key);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolve(String key, Class<T> type) {
        Optional<Object> value = resolve(key);
        return value.filter(type::isInstance).map(v -> (T) v);
    }

    public boolean containsKey(String key) {
        return variables.containsKey(key) || (parent != null && parent.containsKey(key));
    }

    public Map<String, Object> snapshot() {
        return new HashMap<>(variables);
    }

    public int size() {
        return variables.size();
    }

    @Override
    public String toString() {
        return "VariableScope[" + scopeName + ", vars=" + variables.size() + "]";
    }
}
