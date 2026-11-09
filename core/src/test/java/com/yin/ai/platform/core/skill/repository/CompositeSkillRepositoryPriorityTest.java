package com.yin.ai.platform.core.skill.repository;

import com.yin.ai.platform.core.skill.SkillManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeSkillRepositoryPriorityTest {

    @Test
    void lowerPriorityValueWinsRegardlessOfInjectionOrder() {
        SkillRepository fallback = repository(400, "classpath");
        SkillRepository nacos = repository(100, "nacos");

        assertThat(new CompositeSkillRepository(List.of(fallback, nacos)).load("chat"))
                .get().extracting("description").isEqualTo("nacos");
    }

    private SkillRepository repository(int priority, String description) {
        SkillManifest manifest = new SkillManifest();
        manifest.setName("chat");
        manifest.setDescription(description);
        return new SkillRepository() {
            @Override public int priority() { return priority; }
            @Override public Optional<SkillManifest> load(String skillName) { return Optional.of(manifest); }
            @Override public List<SkillManifest> loadAll() { return List.of(manifest); }
            @Override public Set<String> listSkillNames() { return Set.of("chat"); }
        };
    }
}
