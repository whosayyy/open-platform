package com.yin.ai.platform.core.agent.research;

import com.yin.ai.platform.core.agent.plan.AgentPlan;

import java.util.List;

/** Core policy for collecting verified evidence sources and validating a report. */
public interface ResearchSynthesisPolicy {

    List<ResearchSource> collectSources(AgentPlan plan);

    String formatSources(List<ResearchSource> sources);

    Verification verify(String report, List<ResearchSource> sources);

    record Verification(boolean verified, String report, List<String> unverifiedUrls) {
        public Verification {
            report = report == null ? "" : report;
            unverifiedUrls = unverifiedUrls == null ? List.of() : List.copyOf(unverifiedUrls);
        }
    }
}
