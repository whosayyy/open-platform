package com.yin.ai.platform.core.skill;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Skill 加载器 — SPI 接口。
 * <p>
 * 从指定来源加载 Skill 定义，
 * 解析 YAML Frontmatter 和 Markdown 正文，返回 {@link SkillManifest}。
 */
public interface SkillLoader {

    /**
     * 按名称加载 Skill。
     *
     * @param skillName Skill 唯一名称
     * @return 已解析的 SkillManifest，不存在返回 Optional.empty()
     */
    Optional<SkillManifest> load(String skillName);

    /**
     * 加载所有 Skill。
     */
    List<SkillManifest> loadAll();

    /**
     * 重新加载指定 Skill（热加载）。
     */
    Optional<SkillManifest> reload(String skillName);

    /**
     * 列出所有可用的 Skill 名称。
     */
    Set<String> listSkillNames();
}
