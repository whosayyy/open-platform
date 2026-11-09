package com.yin.ai.platform.core.skill.resolver;

/**
 * 循环依赖异常 — DependencyResolver 在检测到 Skill 间存在循环依赖时抛出。
 * <p>
 * 携带完整循环路径用于诊断定位。
 */
public class CyclicDependencyException extends RuntimeException {

    private final String cyclePath;

    public CyclicDependencyException(String cyclePath) {
        super("Cyclic skill dependency detected: " + cyclePath);
        this.cyclePath = cyclePath;
    }

    public String getCyclePath() {
        return cyclePath;
    }
}
