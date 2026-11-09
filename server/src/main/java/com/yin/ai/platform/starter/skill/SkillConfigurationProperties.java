package com.yin.ai.platform.starter.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 模块配置属性。
 * <p>
 * 绑定 {@code ai.skill.*} 配置前缀，支持 application.yml 配置驱动。
 */
@Data
@ConfigurationProperties(prefix = "ai.skill")
public class SkillConfigurationProperties {

    /** Intent → Skill 名称列表映射（覆盖默认映射） */
    private Map<String, List<String>> intentMappings = new HashMap<>();

    /** 未命中 Intent 映射时使用的默认 Skill。 */
    private List<String> defaultSkills = List.of("chat");

    /** 选择策略：chain | intent-only | rule-only */
    private String selectionStrategy = "chain";

    /** 存储类型：classpath | redis (V3.0) */
    private String repositoryType = "classpath";

    /** 是否启用热加载（开发模式） */
    private boolean hotReload = true;

    /** 是否启用缓存 */
    private boolean cacheEnabled = true;
}
