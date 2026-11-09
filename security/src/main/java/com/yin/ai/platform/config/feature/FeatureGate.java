package com.yin.ai.platform.config.feature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级特性开关 — 标注的方法在被禁用时抛出 {@link FeatureDisabledException}。
 *
 * <pre>{@code
 * @FeatureGate("new-workflow-engine")
 * public void executeNewWorkflow() { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureGate {
    String value();
}
