package com.yin.ai.platform.core.skill;

import com.yin.ai.platform.core.skill.repository.SkillRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Skill 加载器 — 委托 {@link SkillRepository} 获取数据，自身只负责缓存。
 * <p>
 * 不包含任何文件 IO 或业务判断逻辑。所有存储访问通过 Repository SPI 完成，
 * 支持任意存储后端切换。
 * <p>
 * 缓存策略：缓存 SkillManifest（元数据），不缓存最终的 System Prompt。
 * 最终 Prompt 由外层组件动态组合，确保多 Skill 按需拼接。
 */
@Slf4j
public class DefaultSkillLoader implements SkillLoader {

    private final SkillRepository repository;
    private final Map<String, SkillManifest> cache = new ConcurrentHashMap<>();

    public DefaultSkillLoader(SkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SkillManifest> load(String skillName) {
        SkillManifest cached = cache.get(skillName);
        if (cached != null) {
            if (!cached.isEnabled()) {
                log.debug("[LOADER] skillName={} status=SKIPPED reason=disabled", skillName);
                return Optional.empty();
            }
            return Optional.of(cached);
        }

        Optional<SkillManifest> loaded = repository.load(skillName);
        loaded.ifPresentOrElse(
                manifest -> {
                    if (!manifest.isEnabled()) {
                        log.info("[LOADER] skillName={} status=SKIPPED reason=disabled", skillName);
                        return;
                    }
                    cache.put(skillName, manifest);
                    log.debug("[LOADER] skillName={} status=CACHED version={}", skillName, manifest.getVersion());
                },
                () -> log.debug("[LOADER] skillName={} status=NOT_FOUND", skillName)
        );

        return loaded.filter(SkillManifest::isEnabled);
    }

    @Override
    public List<SkillManifest> loadAll() {
        List<SkillManifest> all = repository.loadAll();
        List<SkillManifest> enabled = all.stream()
                .filter(SkillManifest::isEnabled)
                .peek(m -> cache.putIfAbsent(m.getName(), m))
                .toList();
        log.info("[LOADER] loadAll: total={} enabled={} cached={}", all.size(), enabled.size(), cache.size());
        return enabled;
    }

    @Override
    public Optional<SkillManifest> reload(String skillName) {
        log.info("[LOADER] skillName={} status=RELOADING", skillName);
        cache.remove(skillName);
        return load(skillName);
    }

    @Override
    public Set<String> listSkillNames() {
        if (cache.isEmpty()) {
            loadAll();
        }
        return Set.copyOf(cache.keySet());
    }
}
