package com.yin.ai.platform.core.router.matcher;

/** Framework-neutral metadata rule consumed by {@link RuleMatcher}. */
public record MetadataRoutingRule(
        String metadataKey,
        String conditionKey,
        String conditionValue,
        String intent) {

    public MetadataRoutingRule {
        metadataKey = metadataKey == null ? "" : metadataKey;
        conditionKey = conditionKey == null ? "" : conditionKey;
        conditionValue = conditionValue == null ? "" : conditionValue;
        intent = intent == null ? "" : intent;
    }
}
