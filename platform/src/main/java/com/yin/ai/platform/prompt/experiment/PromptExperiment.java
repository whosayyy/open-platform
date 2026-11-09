package com.yin.ai.platform.prompt.experiment;

import java.util.List;

/** Versioned prompt experiment configuration. */
public record PromptExperiment(String id, boolean enabled, String defaultVersion,
                               List<PromptVariant> variants) {

    public static final int BUCKET_COUNT = 10_000;

    public PromptExperiment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("experiment id must not be blank");
        }
        if (defaultVersion == null || defaultVersion.isBlank()) {
            throw new IllegalArgumentException("default prompt version must not be blank");
        }
        variants = variants == null ? List.of() : List.copyOf(variants);
        if (enabled && variants.stream().mapToInt(PromptVariant::weight).sum() != BUCKET_COUNT) {
            throw new IllegalArgumentException("enabled experiment weights must total 10000");
        }
        long uniqueIds = variants.stream().map(PromptVariant::id).distinct().count();
        if (uniqueIds != variants.size()) {
            throw new IllegalArgumentException("variant ids must be unique");
        }
    }
}
