package com.yin.ai.platform.core.model;

@FunctionalInterface
public interface ModelEventListener {

    ModelEventListener NOOP = event -> { };

    void onEvent(ModelEvent event);
}
