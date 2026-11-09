package com.yin.ai.platform.workflow.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classpath Stage Prompt 注册表 — 从 {@code classpath*:prompts/{ref}.md} 加载。
 */
@Slf4j
@Component
public class ClasspathStagePromptRegistry implements StagePromptRegistry {

    private static final String BASE_PATTERN = "classpath*:prompts/%s.md";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> load(String promptRef) {
        if (promptRef == null || promptRef.isBlank()) {
            return Optional.empty();
        }
        String cached = cache.get(promptRef);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(String.format(BASE_PATTERN, promptRef));
            if (resources.length == 0) {
                log.debug("[STAGE-PROMPT] not found: {}", promptRef);
                return Optional.empty();
            }
            String content = read(resources[0]);
            cache.put(promptRef, content);
            return Optional.of(content);
        } catch (IOException e) {
            log.warn("[STAGE-PROMPT] load failed ref={}: {}", promptRef, e.getMessage());
            return Optional.empty();
        }
    }

    private String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
