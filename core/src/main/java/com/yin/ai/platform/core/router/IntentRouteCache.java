package com.yin.ai.platform.core.router;

import java.util.Optional;

/** Cache SPI used by the core router without knowing Redis or Caffeine. */
public interface IntentRouteCache {

    Optional<IntentMatchResult> get(String normalizedQuery);

    void put(String normalizedQuery, IntentMatchResult result);

    static IntentRouteCache noop() {
        return new IntentRouteCache() {
            @Override
            public Optional<IntentMatchResult> get(String normalizedQuery) {
                return Optional.empty();
            }

            @Override
            public void put(String normalizedQuery, IntentMatchResult result) {
            }
        };
    }
}
