package com.yin.ai.platform.gateway.model;

import java.util.List;
import java.util.Optional;

/**
 * 模型注册中心 SPI — 管理所有可用模型实例的定义。
 * <p>
 * 模型定义从配置（{@code ai.gateway.models.*}）初始化，
 * 运行时可通过 {@link #register(ModelDefinition)} 动态注册新模型。
 */
public interface ModelRegistry {

    /**
     * 获取默认模型定义。
     *
     * @return 默认模型定义
     * @throws IllegalStateException 如果没有已启用的模型
     */
    ModelDefinition getDefault();

    /**
     * 按名称获取模型定义。
     *
     * @param modelName 模型名称（如 "deepseek-chat"、"qwen-plus"）
     * @return 模型定义，不存在返回 empty
     */
    Optional<ModelDefinition> get(String modelName);

    /**
     * 获取指定 provider 下所有模型。
     *
     * @param provider provider 名称
     * @return 该 provider 下的所有模型定义（含未启用）
     */
    List<ModelDefinition> listByProvider(String provider);

    /**
     * 列出所有模型定义（含未启用）。
     */
    List<ModelDefinition> listAll();

    /**
     * 列出所有已启用的模型。
     */
    List<ModelDefinition> listEnabled();

    /**
     * 判断模型是否存在且已启用。
     */
    boolean supports(String modelName);

    /**
     * 动态注册模型定义。
     */
    void register(ModelDefinition definition);

    /**
     * 移除模型定义。
     */
    void unregister(String modelName);

    /**
     * 获取注册的模型总数（含未启用）。
     */
    int size();
}
