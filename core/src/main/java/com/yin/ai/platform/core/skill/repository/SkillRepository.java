package com.yin.ai.platform.core.skill.repository;

import com.yin.ai.platform.core.skill.SkillManifest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Skill Repository — 抽象 Skill 存储来源（SPI）。
 * <p>
 * 分离"Skill 从哪里来"与"Skill 如何被加载"两个关注点。
 * SkillLoader 通过此接口获取 Skill 数据，不关心底层存储类型。
 * <p>
 * 基础设施实现可来自本地资源、远程配置或对象存储，核心加载器只依赖本接口。
 */
public interface SkillRepository {

    /**
     * Deterministic resolution priority. Lower values win when repositories contain the same skill.
     */
    default int priority() {
        return 1_000;
    }

    /**
     * 按名称加载单个 Skill。
     *
     * @param skillName Skill 唯一名称
     * @return 已解析的 SkillManifest，不存在返回 {@code Optional.empty()}
     */
    Optional<SkillManifest> load(String skillName);

    /**
     * 加载所有可用 Skill。
     */
    List<SkillManifest> loadAll();

    /**
     * 列出所有可用 Skill 名称。
     */
    Set<String> listSkillNames();

    /**
     * 检查指定 Skill 是否存在。
     */
    default boolean exists(String skillName) {
        return listSkillNames().contains(skillName);
    }

    /**
     * 失效指定 Skill 的缓存（热更新后调用）。
     */
    default void invalidate(String skillName) {
        // no-op by default
    }

    /**
     * 失效所有缓存。
     */
    default void invalidateAll() {
        // no-op by default
    }
}
