package com.yin.ai.platform.core.skill.selector;

import com.yin.ai.platform.core.router.RouteContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 基于 Intent → Skill 列表的配置驱动选择器。
 * <p>
 * 映射和默认 Skill 由外层组装代码通过普通 Java 集合传入。
 */
@Slf4j
public class IntentSkillSelector implements SkillSelector {

    private final Map<String, List<String>> intentSkillMap;
    private final List<String> defaultSkillNames;

    public IntentSkillSelector(Map<String, List<String>> intentSkillMap,
                               List<String> defaultSkillNames) {
        if (intentSkillMap != null && !intentSkillMap.isEmpty()) {
            this.intentSkillMap = Map.copyOf(intentSkillMap);
        } else {
            this.intentSkillMap = Map.of();
        }
        this.defaultSkillNames = defaultSkillNames == null
                ? List.of()
                : List.copyOf(defaultSkillNames);
        log.info("[INTENT-SELECTOR] initialized with {} intent mappings", this.intentSkillMap.size());
    }

    @Override
    public SkillSelectionResult select(RouteContext context, String intent) {
        List<String> skillNames = intentSkillMap.getOrDefault(intent, defaultSkillNames);
        log.debug("[INTENT-SELECTOR] intent={} skills={}", intent, skillNames);
        return SkillSelectionResult.builder()
                .skillNames(skillNames)
                .selectorName("intent")
                .confidence(0.90)
                .build();
    }

    @Override
    public String selectorName() {
        return "intent";
    }
}
