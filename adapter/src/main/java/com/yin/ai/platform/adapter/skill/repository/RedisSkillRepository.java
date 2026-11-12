package com.yin.ai.platform.adapter.skill.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.core.skill.repository.SkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的 SkillRepository — 支持跨节点热更新。
 * <p>
 * Skill 定义以 JSON 存储在 Redis Hash {@code ai:skills:defs} 中，
 * 管理端通过 REST API 写入后所有节点立即生效（无需重启）。
 * <p>
 * 与 {@link ClasspathSkillRepository} 共存：优先 Redis，miss 时 fallback classpath。
 */
@Slf4j
public class RedisSkillRepository implements SkillRepository {

    @Override
    public int priority() {
        return 300;
    }

    private static final String REDIS_KEY = "ai:skills:defs";
    private static final ObjectMapper MAPPER = createMapper();

    private final RedissonClient redisson;

    static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.addMixIn(SkillManifest.RulesConfig.class, RulesConfigMixin.class);
        return mapper;
    }

    private abstract static class RulesConfigMixin {
        @JsonProperty("max_agent_steps")
        abstract int getMaxAgentSteps();

        @JsonProperty("max_agent_steps")
        abstract void setMaxAgentSteps(int maxAgentSteps);
    }

    public RedisSkillRepository(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<SkillManifest> load(String skillName) {
        Object raw = redisson.getMap(REDIS_KEY).get(skillName);
        if (raw == null) return Optional.empty();
        try {
            SkillManifest manifest = MAPPER.readValue(raw.toString(), SkillManifest.class);
            return Optional.of(manifest);
        } catch (Exception e) {
            log.warn("[REDIS-SKILL] deserialize failed for {}: {}", skillName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<SkillManifest> loadAll() {
        List<SkillManifest> result = new ArrayList<>();
        Map<Object, Object> all = redisson.getMap(REDIS_KEY);
        for (Map.Entry<Object, Object> entry : all.entrySet()) {
            try {
                SkillManifest m = MAPPER.readValue(entry.getValue().toString(), SkillManifest.class);
                result.add(m);
            } catch (Exception ignored) { }
        }
        log.info("[REDIS-SKILL] loaded {} skills from Redis", result.size());
        return result;
    }

    @Override
    public Set<String> listSkillNames() {
        return redisson.getMap(REDIS_KEY).keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /**
     * 上传/更新 Skill 定义到 Redis（供管理端调用）。
     */
    public void save(SkillManifest manifest) {
        try {
            String json = MAPPER.writeValueAsString(manifest);
            redisson.getMap(REDIS_KEY).put(manifest.getName(), json);
            log.info("[REDIS-SKILL] saved skill={} version={}", manifest.getName(), manifest.getVersion());
        } catch (Exception e) {
            log.error("[REDIS-SKILL] save failed for {}: {}", manifest.getName(), e.getMessage());
        }
    }

    /**
     * 从 Redis 删除 Skill 定义。
     */
    public void remove(String skillName) {
        redisson.getMap(REDIS_KEY).remove(skillName);
        log.info("[REDIS-SKILL] removed skill={}", skillName);
    }
}
