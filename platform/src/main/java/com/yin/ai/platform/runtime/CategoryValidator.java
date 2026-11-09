package com.yin.ai.platform.runtime;

import java.util.Set;

/**
 * 分类验证 SPI。
 * <p>
 * 业务模块实现此接口以提供有效的分类列表和默认分类。
 * 用于 AI 响应解析时校验分类字段是否合法。
 */
public interface CategoryValidator {

    boolean isValidCategory(String category);

    String getDefaultCategory();

    default Set<String> getValidCategories() {
        return Set.of();
    }
}
