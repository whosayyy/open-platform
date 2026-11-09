package com.yin.ai.platform.memory.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 从 JSON 文件加载评测数据集。
 */
public class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);
    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 classpath 加载数据集。
     */
    public EvaluationDataset loadFromClasspath(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Dataset not found: " + resourcePath);
            }
            return objectMapper.readValue(is, EvaluationDataset.class);
        } catch (IOException e) {
            log.error("Failed to load dataset from {}", resourcePath, e);
            throw new RuntimeException("Failed to load dataset: " + resourcePath, e);
        }
    }
}
