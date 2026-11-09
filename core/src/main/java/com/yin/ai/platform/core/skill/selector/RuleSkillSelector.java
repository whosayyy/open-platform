package com.yin.ai.platform.core.skill.selector;

import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.core.router.RouteContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于规则的 Skill 选择器 — 通过 RouteContext.metadata 匹配 SkillManifest.tags。
 * <p>
 * 优先级低于 IntentSkillSelector，在链式调用中作为补充选择。
 * 使用场景：当 RouteContext 携带 contentId、action 等已知元数据时，
 * 可自动匹配带有对应 tag 的 Skill。
 * <p>
 * <b>性能优化：</b>首次 select() 时从 SkillLoader.loadAll() 构建 tag→skillNames 倒排索引，
 * 后续调用直接从 index 做 O(M) 查找，不再每次遍历所有 Manifest。
 * 100+ Skill 场景下比 O(N×M) 遍历快一个数量级。
 * <p>
 * 例如：RouteContext.metadata = {action: "review"} → 匹配 tag="review" 的 Skill。
 */
@Slf4j
public class RuleSkillSelector implements SkillSelector {

    private final SkillLoader skillLoader;

    /** tag → skillNames 倒排索引，volatile + DCL 保证线程安全的懒加载 */
    private volatile Map<String, List<String>> tagIndex;

    public RuleSkillSelector(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public SkillSelectionResult select(RouteContext context, String intent) {
        Map<String, Object> metadata = context.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return emptyResult();
        }

        Map<String, List<String>> index = ensureTagIndex();
        if (index.isEmpty()) {
            return emptyResult();
        }

        // O(M) HashMap 查找，M=metadata 条目数（通常 1-3）
        Set<String> matched = new HashSet<>();
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            String tagCandidate = key + ":" + value;

            //如果索引中存在，则加入结果
            collectMatches(index, key, matched);
            collectMatches(index, value, matched);
            collectMatches(index, tagCandidate, matched);
        }

        List<String> skillNames = List.copyOf(matched);
        log.debug("[RULE-SELECTOR] matched {} skills from metadata keys={}: {}",
                matched.size(), metadata.keySet(), skillNames);

        return SkillSelectionResult.builder()
                .skillNames(skillNames)
                .selectorName("rule")
                .confidence(matched.isEmpty() ? 0.0 : 0.70)
                .build();
    }

    /**
     * 刷新 tag 索引 — 热加载 Skill 后调用，强制下次 select() 重建索引。
     */
    public void refreshTagIndex() {
        this.tagIndex = null;
        log.info("[RULE-SELECTOR] tag index invalidated");
    }

    @Override
    public String selectorName() {
        return "rule";
    }

    // ——— private ———

    private Map<String, List<String>> ensureTagIndex() {
        if (tagIndex != null) {
            return tagIndex;
        }
        synchronized (this) {
            if (tagIndex == null) {
                tagIndex = buildTagIndex();
                log.info("[RULE-SELECTOR] tag index built: {} entries", tagIndex.size());
            }
        }
        return tagIndex;
    }

    private Map<String, List<String>> buildTagIndex() {
        Map<String, List<String>> index = new HashMap<>();
        List<SkillManifest> allSkills = skillLoader.loadAll();

        for (SkillManifest manifest : allSkills) {
            if (manifest.getTags() == null || manifest.getTags().isEmpty()) {
                continue;
            }
            for (String tag : manifest.getTags()) {
                index.computeIfAbsent(tag, k -> new ArrayList<>()).add(manifest.getName());
            }
        }
        // 冻结内部 List 为不可变副本
        index.replaceAll((tag, names) -> List.copyOf(names));
        return Map.copyOf(index);
    }

    private static void collectMatches(Map<String, List<String>> index, String key,
                                        Set<String> collector) {
        if (key == null || key.isEmpty()) {
            return;
        }
        List<String> matched = index.get(key);
        if (matched != null) {
            collector.addAll(matched);
        }
    }

    private static SkillSelectionResult emptyResult() {
        return SkillSelectionResult.builder()
                .skillNames(List.of())
                .selectorName("rule")
                .confidence(0.0)
                .build();
    }
}
