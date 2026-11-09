package com.yin.ai.platform.knowledge;

import lombok.extern.slf4j.Slf4j;

import com.yin.ai.platform.document.model.DocumentSegment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KnowledgeBase 注册中心 — 按名称查找并执行检索。
 * <p>
 * 所有 {@link KnowledgeBase} Bean 由 Spring 自动注入。
 * 运行时按 Skill 声明的 {@code knowledge: [name]} 列表，
 * 依次调用每个匹配的 KnowledgeBase，拼接返回结果。
 */
@Slf4j
public class KnowledgeRegistry {

    private final Map<String, KnowledgeBase> bases;

    public KnowledgeRegistry(List<KnowledgeBase> bases) {
        Map<String, KnowledgeBase> map = new LinkedHashMap<>();
        for (KnowledgeBase kb : bases) {
            if (map.containsKey(kb.getName())) {
                log.warn("[KNOWLEDGE] duplicate knowledge base name '{}', overwriting", kb.getName());
            }
            map.put(kb.getName(), kb);
        }
        this.bases = Collections.unmodifiableMap(map);
        log.info("[KNOWLEDGE] registered {} knowledge base(s): {}", map.size(), map.keySet());
    }

    /**
     * 查询指定知识库列表并拼接结果。
     *
     * @param names 知识库名称列表（来自 Skill YAML）
     * @param query 用户查询文本
     * @return 拼接后的知识上下文；无匹配知识库或全部返回空时返回空字符串
     */
    public String retrieve(List<String> names, String query) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (String name : names) {
            KnowledgeBase kb = bases.get(name);
            if (kb == null) {
                log.warn("[KNOWLEDGE] unknown knowledge base '{}'", name);
                continue;
            }
            try {
                String context = kb.retrieve(query);
                if (context != null && !context.isBlank()) {
                    if (!result.isEmpty()) {
                        result.append("\n\n");
                    }
                    result.append(context);
                }
            } catch (Exception e) {
                log.error("[KNOWLEDGE] retrieve failed for '{}': {}", name, e.getMessage(), e);
            }
        }
        return result.toString();
    }

    /**
     * 将文档分段索引到指定知识库。
     *
     * @param name     知识库名称
     * @param segments 文档分段列表
     */
    public void index(String name, List<DocumentSegment> segments) {
        KnowledgeBase kb = bases.get(name);
        if (kb == null) {
            throw new IllegalArgumentException("Unknown knowledge base: " + name);
        }
        kb.index(segments);
    }

    /** 返回所有已注册知识库名称。 */
    public Set<String> getNames() {
        return bases.keySet();
    }

    public int size() {
        return bases.size();
    }
}
