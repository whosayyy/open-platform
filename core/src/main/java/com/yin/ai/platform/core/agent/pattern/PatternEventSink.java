package com.yin.ai.platform.core.agent.pattern;

import java.util.Map;

/** Receives semantic runtime events while the owning runtime supplies event ids. */
@FunctionalInterface
public interface PatternEventSink {

    PatternEventSink NOOP = (type, payload) -> { };

    void emit(String type, Map<String, Object> payload);
}
