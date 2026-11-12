package com.yin.ai.platform.runtime;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.yin.ai.platform.function.FunctionRegistry;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.memory.SessionMemoryManager;
import com.yin.ai.platform.prompt.PromptCenter;
import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.runtime.SkillExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 组合器 — 动态拼接多个 Skill 的 System Prompt。
 * <p>
 * 支持多 Skill 按需组合：
 * <ol>
 *   <li>按 priority 降序 + 依赖顺序排序 Manifest</li>
 *   <li>加载所有 Skill 声明的 Fragment，编译为 Handlebars Partials</li>
 *   <li>逐 Skill 使用 Handlebars 编译 systemPromptTemplate</li>
 *   <li>注入全局变量：currentTime、tools、conversationContext、ragContext</li>
 *   <li>以 {@code \n---\n} 分隔拼接</li>
 * </ol>
 */
@Slf4j
public class PromptComposer {

    private final FunctionRegistry functionRegistry;
    private final SessionMemoryManager sessionMemoryManager;
    private final PromptCenter promptCenter;
    private final Handlebars handlebars = new Handlebars();
    private final ConcurrentHashMap<String, Template> templateCache = new ConcurrentHashMap<>();

    public PromptComposer(FunctionRegistry functionRegistry,
                          SessionMemoryManager sessionMemoryManager,
                          PromptCenter promptCenter) {
        this.functionRegistry = functionRegistry;
        this.sessionMemoryManager = sessionMemoryManager;
        this.promptCenter = promptCenter;
    }

    /**
     * 组合多个 Skill 的 System Prompt。
     *
     * @param manifests  待组合的 Skill 清单列表
     * @param ctx        执行上下文
     * @param ragContext RAG 检索上下文（可选）
     * @return 拼接完成的 System Prompt
     */
    private static final Pattern PARTIAL_PATTERN = Pattern.compile("\\{\\{\\s*>\\s*(\\S+)\\s*}}");

    public String compose(List<SkillManifest> manifests, SkillExecutionContext ctx, String ragContext) {
        if (manifests == null || manifests.isEmpty()) {
            return "";
        }

        // 1. 排序：priority DESC，被依赖的在前
        List<SkillManifest> sorted = new ArrayList<>(manifests);
        sorted.sort(Comparator.comparingInt(SkillManifest::getPriority).reversed());

        // 2. 收集全局工具列表（所有 Skill tools 的并集）
        List<Map<String, String>> allToolInfos = collectToolInfos(manifests);

        // 3. 加载 Fragment（name → content 映射）
        Map<String, String> fragments = loadFragments(manifests);

        // 4. 逐 Skill 编译 + 拼接
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            SkillManifest manifest = sorted.get(i);
            String section = renderSection(manifest, ctx, ragContext, allToolInfos, fragments);
            if (section != null && !section.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n---\n");
                }
                sb.append(section);
            }
        }

        log.debug("[COMPOSER] composed {} skills → {} chars", sorted.size(), sb.length());
        return sb.toString();
    }

    /**
     * 加载所有 Manifest 声明的 Fragment，返回 name → content 映射。
     */
    private Map<String, String> loadFragments(List<SkillManifest> manifests) {
        Map<String, String> fragments = new LinkedHashMap<>();
        for (SkillManifest manifest : manifests) {
            if (manifest.getFragments() == null) continue;
            for (String ref : manifest.getFragments()) {
                String[] parts = parseFragmentRef(ref);
                String name = parts[0];
                String version = parts[1];

                // 跳过已加载的的Fragment
                if (fragments.containsKey(name)) continue;

                promptCenter.getFragment(name, version).ifPresentOrElse(
                        frag -> {
                            fragments.put(name, frag.getFragment());
                            log.debug("[COMPOSER] loaded fragment: {}@{}", name, frag.getVersion());
                        },
                        () -> log.warn("[COMPOSER] fragment not found: {}", ref)
                );
            }
        }
        return fragments;
    }

    private String[] parseFragmentRef(String ref) {
        if (ref.contains("@")) {
            return ref.split("@", 2);
        }
        return new String[]{ref, null};
    }

    private String renderSection(SkillManifest manifest, SkillExecutionContext ctx,
                                  String ragContext, List<Map<String, String>> toolInfos,
                                  Map<String, String> fragments) {
        Map<String, Object> variables = new HashMap<>();

        variables.put("currentTime",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // 工具列表
        variables.put("tools", toolInfos);

        // 对话上下文
        if (manifest.getMemory() != null && manifest.getMemory().isEnabled()
                && ctx.getSessionId() != null) {
            variables.put("conversationContext",
                    sessionMemoryManager.buildConversationContext(ctx.getSessionId()));
        } else {
            variables.put("conversationContext", "");
        }

        // RAG 上下文
        variables.put("ragContext", ragContext != null ? ragContext : "");

        // Fragment Partial 展开：替换 {{> name}} 为 Fragment 内容
        String templateBody = manifest.getSystemPromptTemplate();
        if (!fragments.isEmpty()) {
            templateBody = expandPartials(templateBody, fragments);
        }

        try {
            Template template = templateCache.computeIfAbsent(templateBody, body -> {
                try {
                    return handlebars.compileInline(body);
                } catch (IOException e) {
                    throw new RuntimeException("Template compile error for skill="
                            + manifest.getName() + ": " + e.getMessage(), e);
                }
            });
            return template.apply(variables);
        } catch (IOException e) {
            log.error("[COMPOSER] template compile failed for skill={}", manifest.getName(), e);
            return manifest.getSystemPromptTemplate();
        }
    }

    /**
     * 将模板中的 {@code {{> fragmentName}}} 替换为 Fragment 实际内容。
     */
    public String expandPartials(String template, Map<String, String> fragments) {
        Matcher m = PARTIAL_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = fragments.getOrDefault(name, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private List<Map<String, String>> collectToolInfos(List<SkillManifest> manifests) {
        List<Map<String, String>> toolInfos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SkillManifest manifest : manifests) {
            if (manifest.getTools() != null) {
                for (String toolName : manifest.getTools()) {
                    if (seen.add(toolName)) {
                        functionRegistry.getDefinition(toolName).ifPresentOrElse(
                                def -> toolInfos.add(Map.of(
                                        "name", def.getName(),
                                        "description", def.getDescription()
                                )),
                                () -> log.warn("[COMPOSER] unknown tool: {} for skill: {}",
                                        toolName, manifest.getName())
                        );
                    }
                }
            }
        }
        return toolInfos;
    }
}
