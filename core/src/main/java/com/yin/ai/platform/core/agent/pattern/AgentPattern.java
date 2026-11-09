package com.yin.ai.platform.core.agent.pattern;

/** Pluggable, framework-neutral agent orchestration policy. */
public interface AgentPattern {

    AgentMode mode();

    PatternResult execute(PatternContext context);
}
