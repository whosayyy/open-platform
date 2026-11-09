package com.yin.ai.platform.prompt.experiment;

/** A weighted immutable prompt version used by an experiment. */
public record PromptVariant(String id, String promptVersion, int weight) {

    public PromptVariant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("variant id must not be blank");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("prompt version must not be blank");
        }
        if (weight < 0 || weight > PromptExperiment.BUCKET_COUNT) {
            throw new IllegalArgumentException("variant weight must be between 0 and 10000");
        }
    }
}
