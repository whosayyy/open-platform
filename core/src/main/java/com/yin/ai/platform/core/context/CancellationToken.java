package com.yin.ai.platform.core.context;

import java.util.concurrent.CancellationException;

/** Provider-neutral cancellation signal propagated through platform core calls. */
@FunctionalInterface
public interface CancellationToken {

    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("AI platform operation was cancelled");
        }
    }
}
