package com.yin.ai.platform.core.skill.repository;

import com.yin.ai.platform.core.skill.SkillManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 组合 SkillRepository — 按仓储优先级顺序解析。
 * <p>
 * 同名 Skill 以优先级更高的仓储结果为准。
 */
@Slf4j
public class CompositeSkillRepository implements SkillRepository {

    private final List<SkillRepository> delegates;

    public CompositeSkillRepository(List<SkillRepository> delegates) {
        this.delegates = delegates.stream()
                .sorted(Comparator.comparingInt(SkillRepository::priority)
                        .thenComparing(repository -> repository.getClass().getName()))
                .toList();
        log.info("[COMPOSITE-REPO] initialized with {} repositories: {}",
                this.delegates.size(),
                this.delegates.stream()
                        .map(r -> r.getClass().getSimpleName() + "(" + r.priority() + ")")
                        .toList());
    }

    @Override
    public Optional<SkillManifest> load(String skillName) {
        for (SkillRepository repo : delegates) {
            Optional<SkillManifest> result = repo.load(skillName);
            if (result.isPresent()) {
                log.debug("[COMPOSITE-REPO] skillName={} resolved by {}", skillName, repo.getClass().getSimpleName());
                return result;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<SkillManifest> loadAll() {
        Set<String> seen = new LinkedHashSet<>();
        List<SkillManifest> merged = new ArrayList<>();
        for (SkillRepository repo : delegates) {
            for (SkillManifest manifest : repo.loadAll()) {
                if (seen.add(manifest.getName())) {
                    merged.add(manifest);
                }
            }
        }
        log.info("[COMPOSITE-REPO] loadAll: {} skills merged from {} repos", merged.size(), delegates.size());
        return merged;
    }

    @Override
    public Set<String> listSkillNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SkillRepository repo : delegates) {
            names.addAll(repo.listSkillNames());
        }
        return names;
    }

    @Override
    public void invalidate(String skillName) {
        for (SkillRepository repo : delegates) {
            repo.invalidate(skillName);
        }
    }

    @Override
    public void invalidateAll() {
        for (SkillRepository repo : delegates) {
            repo.invalidateAll();
        }
    }
}
