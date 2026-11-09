package com.yin.ai.platform.prompt.experiment;

public interface PromptSelector {

    PromptSelection select(PromptExperiment experiment, PromptSelectionContext context);
}
