package com.yin.ai.platform.filter;

import java.util.List;

/**
 * 敏感词提供者 SPI。
 * <p>
 * 业务模块实现此接口以提供自定义敏感词列表。
 * 默认实现 {@link YamlSensitiveWordProvider} 从 yaml 配置读取。
 */
@FunctionalInterface
public interface SensitiveWordProvider {
    List<String> getSensitiveWords();
}
