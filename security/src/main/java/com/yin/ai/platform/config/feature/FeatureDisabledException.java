package com.yin.ai.platform.config.feature;

/**
 * 特性被禁用时抛出，供 AOP 切面使用。
 */
public class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException(String flagKey) {
        super("Feature '" + flagKey + "' is currently disabled");
    }
}
