package com.yin.ai.platform.core.model;

import java.util.concurrent.CompletionStage;

/** Cancellable asynchronous model invocation. */
public interface ModelCall {

    CompletionStage<ModelResponse> completion();

    boolean cancel();
}
