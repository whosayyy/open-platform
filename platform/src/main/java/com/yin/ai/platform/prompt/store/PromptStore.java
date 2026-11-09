package com.yin.ai.platform.prompt.store;

import com.yin.ai.platform.prompt.model.PromptFragment;
import com.yin.ai.platform.prompt.model.PromptTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Prompt 存储 SPI — 隔离 Fragment/Template 的物理存储。
 * <p>
 * 当前实现：{@link YamlPromptStore}（classpath YAML 文件）。
 * 未来演进：RedisPromptStore（V2.2）、NacosPromptStore（V2.1）。
 */
public interface PromptStore {

    /** 获取指定版本的 Fragment */
    Optional<PromptFragment> loadFragment(String name, String version);

    /** 获取最新版本的 Fragment */
    Optional<PromptFragment> loadLatestFragment(String name);

    /** 列出所有 Fragment 名称 */
    List<String> listFragmentNames();

    /** 获取指定版本的 Standalone Template */
    Optional<PromptTemplate> loadTemplate(String name, String version);

    /** 获取最新版本的 Standalone Template */
    Optional<PromptTemplate> loadLatestTemplate(String name);

    /** 列出所有 Template 名称 */
    List<String> listTemplateNames();

    /** 刷新缓存 */
    void refresh();
}
