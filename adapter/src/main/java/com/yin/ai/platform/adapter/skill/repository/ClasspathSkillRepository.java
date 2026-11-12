package com.yin.ai.platform.adapter.skill.repository;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.core.skill.repository.SkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 classpath:/skills/*.md 的 SkillRepository 实现。
 * <p>
 * 从 DefaultSkillLoader 提取文件读取和 YAML 解析逻辑，
 * 使用 ReentrantLock 保证并发解析安全，支持基于 lastModified 的热加载检测。
 * <p>
 * <b>注意：</b>以 fat JAR 部署时 {@link Resource#lastModified()} 可能返回 0，
 * 此时热加载不可靠。生产环境热加载建议使用 RedisSkillRepository (V3.0)。
 */
@Slf4j
public class ClasspathSkillRepository implements SkillRepository {

    @Override
    public int priority() {
        return 400;
    }

    private static final String SKILL_PATH_PATTERN = "classpath*:/skills/*.md";

    private static final Pattern FRONT_MATTER_PATTERN =
            Pattern.compile("^---[ \\t]*\\R(.*?)\\R---[ \\t]*\\R?", Pattern.DOTALL);

    private final Map<String, SkillManifest> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastModifiedCache = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Lock writeLock = new ReentrantLock();
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Override
    public Optional<SkillManifest> load(String skillName) {
        SkillManifest cached = cache.get(skillName);
        if (cached != null) {
            return Optional.of(cached);
        }
        return loadFromClasspath(skillName);
    }

    @Override
    public List<SkillManifest> loadAll() {
        if (!cache.isEmpty()) {
            return List.copyOf(cache.values());
        }
        return scanAndLoadAll();
    }

    @Override
    public Set<String> listSkillNames() {
        if (cache.isEmpty()) {
            scanAndLoadAll();
        }
        return Set.copyOf(cache.keySet());
    }

    private List<SkillManifest> scanAndLoadAll() {
        List<SkillManifest> manifests = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(SKILL_PATH_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String skillName = filename.substring(0, filename.length() - 3);
                parseAndCache(skillName, resource).ifPresent(manifests::add);
            }
            log.info("[REPO] scan complete, loaded {} skills: {}", manifests.size(),
                    manifests.stream().map(SkillManifest::getName).toList());
        } catch (IOException e) {
            log.error("[REPO] scan failed", e);
        }
        return manifests;
    }

    private Optional<SkillManifest> loadFromClasspath(String skillName) {
        String resourcePath = "classpath:/skills/" + skillName + ".md";
        try {
            Resource[] resources = resolver.getResources(resourcePath);
            if (resources.length == 0) {
                log.warn("[REPO] skillName={} status=NOT_FOUND path={}", skillName, resourcePath);
                return Optional.empty();
            }
            return parseAndCache(skillName, resources[0]);
        } catch (IOException e) {
            log.error("[REPO] skillName={} status=READ_FAILED", skillName, e);
            return Optional.empty();
        }
    }

    private Optional<SkillManifest> parseAndCache(String skillName, Resource resource) {
        long lastModified = getLastModified(resource);

        // Fast path: pre-lock cache check
        SkillManifest cached = cache.get(skillName);
        if (cached != null && lastModifiedCache.getOrDefault(skillName, 0L) == lastModified) {
            return Optional.of(cached);
        }

        writeLock.lock();
        try {
            // Double check
            cached = cache.get(skillName);
            if (cached != null && lastModifiedCache.getOrDefault(skillName, 0L) == lastModified) {
                return Optional.of(cached);
            }

            String content;
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String yamlSection = "";
            String markdownBody = content;
            Matcher m = FRONT_MATTER_PATTERN.matcher(content);
            if (m.find()) {
                yamlSection = m.group(1);
                markdownBody = content.substring(m.end()).trim();
            }

            SkillManifest manifest = yamlMapper.readValue(yamlSection, SkillManifest.class);
            manifest.setSystemPromptTemplate(markdownBody);

            cache.put(skillName, manifest);
            lastModifiedCache.put(skillName, lastModified);

            log.info("[REPO] skillName={} version={} status=LOADED source={}",
                    skillName, manifest.getVersion(), resource.getDescription());
            return Optional.of(manifest);

        } catch (IOException e) {
            log.error("[REPO] skillName={} status=PARSE_FAILED error={}", skillName, e.getMessage(), e);
            return Optional.empty();
        } finally {
            writeLock.unlock();
        }
    }

    private long getLastModified(Resource resource) {
        try {
            return resource.lastModified();
        } catch (IOException e) {
            return 0;
        }
    }
}
