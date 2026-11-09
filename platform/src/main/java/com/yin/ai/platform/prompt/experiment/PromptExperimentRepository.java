package com.yin.ai.platform.prompt.experiment;

import java.util.List;
import java.util.Optional;

public interface PromptExperimentRepository {

    Optional<PromptExperiment> findById(String experimentId);

    List<PromptExperiment> findAll();

    default void refresh() {
    }
}
