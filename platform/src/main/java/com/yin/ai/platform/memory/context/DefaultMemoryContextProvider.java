package com.yin.ai.platform.memory.context;

import com.yin.ai.platform.core.memory.api.MemoryQuery;
import com.yin.ai.platform.memory.orchestration.MemoryContextBundle;
import com.yin.ai.platform.memory.orchestration.MemoryOrchestrator;

import java.util.Map;

public class DefaultMemoryContextProvider implements MemoryContextProvider {
    private final MemoryOrchestrator orchestrator;

    public DefaultMemoryContextProvider(MemoryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public ContextFragment provide(ContextRequest request) {
        MemoryQuery query = request.query();
        if (request.tokenBudget() > 0) query = query.withTokenBudget(request.tokenBudget());
        MemoryContextBundle bundle = orchestrator.retrieve(query, request.accessContext());
        return new ContextFragment("memory", bundle.renderedText(), bundle.actualTokens(),
                Map.of("selected", bundle.memories().size(),
                        "discarded", bundle.discardedByReason(), "plan", bundle.plan(),
                        "memories", bundle.memories()));
    }
}
