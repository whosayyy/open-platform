package com.yin.ai.platform.core.skill.resolver;

import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Skill 依赖解析器 — 递归解析 Skill 依赖关系。
 * <p>
 * <b>算法：</b>DFS + 三色标记法（白/灰/黑）：
 * <ul>
 *   <li>白色 — 未访问</li>
 *   <li>灰色 — 正在访问（当前 DFS 栈中）→ 遇到灰色 = 环</li>
 *   <li>黑色 — 已完成</li>
 * </ul>
 * <p>
 * 结果按拓扑排序（被依赖的在前），自动去重。
 * <p>
 * <b>线程安全：</b>无状态设计，所有状态在栈帧中，天然线程安全。
 *
 * <pre>
 * 示例：
 *   skillNames = ["summary"]
 *   summary.dependencies = ["base"]
 *   ↓ resolve
 *   ["base", "summary"]
 * </pre>
 */
@Slf4j
public class DependencyResolver {

    private final SkillLoader skillLoader;

    public DependencyResolver(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /**
     * 递归解析 Skill 依赖。
     *
     * @param skillNames 初始 Skill 名称列表
     * @return 拓扑排序后的完整 Skill 列表（含依赖，去重）
     * @throws CyclicDependencyException 检测到循环依赖时抛出
     */
    public List<String> resolve(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();   // 黑
        Set<String> visiting = new HashSet<>();  // 灰

        for (String name : skillNames) {
            dfs(name, visiting, visited, result);
        }

        log.debug("[RESOLVER] input={} resolved={}", skillNames, result);
        return result;
    }

    /**
     * 单 Skill 便捷方法。
     */
    public List<String> resolve(String skillName) {
        return resolve(List.of(skillName));
    }

    private void dfs(String skillName, Set<String> visiting, Set<String> visited, List<String> result) {
        if (visited.contains(skillName)) {
            return;
        }
        if (visiting.contains(skillName)) {
            String cyclePath = String.join(" → ", visiting) + " → " + skillName;
            log.error("[RESOLVER] cycle detected: {}", cyclePath);
            throw new CyclicDependencyException(cyclePath);
        }

        visiting.add(skillName);

        SkillManifest manifest = skillLoader.load(skillName).orElse(null);
        if (manifest != null && manifest.getDependencies() != null) {
            for (String dep : manifest.getDependencies()) {
                dfs(dep, visiting, visited, result);
            }
        }

        visiting.remove(skillName);
        visited.add(skillName);

        if (!result.contains(skillName)) {
            result.add(skillName);
        }
    }
}
