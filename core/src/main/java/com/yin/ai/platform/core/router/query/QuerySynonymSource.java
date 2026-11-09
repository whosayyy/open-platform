package com.yin.ai.platform.core.router.query;

import java.util.Map;

/** Application extension point for deterministic, domain-specific query vocabulary. */
@FunctionalInterface
public interface QuerySynonymSource {
    Map<String, String> synonyms();
}
