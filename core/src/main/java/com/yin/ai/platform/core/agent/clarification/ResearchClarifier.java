package com.yin.ai.platform.core.agent.clarification;

import com.yin.ai.platform.core.agent.pattern.PatternContext;

import java.util.Map;

/** Core SPI for research-goal analysis and answer merging. */
public interface ResearchClarifier {

    ResearchClarifier PASSTHROUGH = new ResearchClarifier() {
        @Override
        public ClarificationAnalysis analyze(String goal, PatternContext context) {
            return ClarificationAnalysis.clarified(goal);
        }

        @Override
        public String merge(ClarificationRequest request, Map<String, String> answers,
                            PatternContext context) {
            return request.originalGoal();
        }
    };

    ClarificationAnalysis analyze(String goal, PatternContext context);

    String merge(ClarificationRequest request, Map<String, String> answers,
                 PatternContext context);
}
