package com.yin.ai.platform.core.skill.selector;

import com.yin.ai.platform.core.router.RouteContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentSkillSelectorTest {

    private static final RouteContext CONTEXT = RouteContext.builder().build();

    @Test
    void usesConfiguredIntentMapping() {
        IntentSkillSelector selector = new IntentSkillSelector(
                Map.of("summarize", List.of("base", "summary")),
                List.of("fallback"));

        SkillSelectionResult result = selector.select(CONTEXT, "summarize");

        assertThat(result.getSkillNames()).containsExactly("base", "summary");
        assertThat(result.getSelectorName()).isEqualTo("intent");
    }

    @Test
    void usesCallerProvidedFallbackWhenIntentIsUnknown() {
        IntentSkillSelector selector = new IntentSkillSelector(
                Map.of(), List.of("fallback"));

        SkillSelectionResult result = selector.select(CONTEXT, "unknown");

        assertThat(result.getSkillNames()).containsExactly("fallback");
    }

    @Test
    void treatsNullConfigurationAsEmpty() {
        IntentSkillSelector selector = new IntentSkillSelector(null, null);

        SkillSelectionResult result = selector.select(CONTEXT, "unknown");

        assertThat(result.getSkillNames()).isEmpty();
    }
}
