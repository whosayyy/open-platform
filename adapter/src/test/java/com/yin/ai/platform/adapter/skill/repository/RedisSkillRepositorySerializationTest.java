package com.yin.ai.platform.adapter.skill.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.skill.SkillManifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSkillRepositorySerializationTest {

    private final ObjectMapper mapper = RedisSkillRepository.createMapper();

    @Test
    void preservesLegacyRulesPropertyName() throws Exception {
        String json = """
                {
                  "name": "sample",
                  "maxTokens": 256,
                  "rules": {"max_agent_steps": 7}
                }
                """;

        SkillManifest manifest = mapper.readValue(json, SkillManifest.class);

        assertThat(manifest.getMaxTokens()).isEqualTo(256);
        assertThat(manifest.getRules().getMaxAgentSteps()).isEqualTo(7);

        JsonNode serialized = mapper.readTree(mapper.writeValueAsString(manifest));
        assertThat(serialized.path("maxTokens").asInt()).isEqualTo(256);
        assertThat(serialized.path("rules").path("max_agent_steps").asInt()).isEqualTo(7);
    }
}
