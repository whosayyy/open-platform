package com.yin.ai.platform.prompt.experiment;

/** Result of deterministic prompt variant selection. */
public record PromptSelection(String experimentId, String variantId, String promptVersion,
                              int bucket, boolean fallback) {

    public static PromptSelection fallback(PromptExperiment experiment, int bucket) {
        return new PromptSelection(experiment.id(), "default", experiment.defaultVersion(), bucket, true);
    }
}
