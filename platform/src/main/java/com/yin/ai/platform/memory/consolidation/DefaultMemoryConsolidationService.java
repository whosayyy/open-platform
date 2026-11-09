package com.yin.ai.platform.memory.consolidation;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryId;
import com.yin.ai.platform.memory.orchestration.ConsolidationRequest;
import com.yin.ai.platform.memory.orchestration.MemoryCandidate;
import com.yin.ai.platform.memory.orchestration.MemoryConsolidationResult;
import com.yin.ai.platform.memory.orchestration.MemoryConsolidationService;
import com.yin.ai.platform.memory.orchestration.MemoryOrchestrator;
import com.yin.ai.platform.memory.orchestration.MemoryWriteResult;
import com.yin.ai.platform.core.memory.procedural.ProceduralMemoryStore;
import com.yin.ai.platform.core.memory.store.EpisodicMemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Unified extraction and promotion pipeline. Source episodes are retained on every failure. */
public class DefaultMemoryConsolidationService implements MemoryConsolidationService {
    private final EpisodicMemoryStore episodicStore;
    private final List<MemoryConsolidator> consolidators;
    private final ProceduralMemoryStore procedureStore;
    private final MemoryEpisodePort episodePort;
    private MemoryOrchestrator orchestrator;

    public DefaultMemoryConsolidationService(EpisodicMemoryStore episodicStore,
                                             List<MemoryConsolidator> consolidators,
                                             ProceduralMemoryStore procedureStore,
                                             MemoryEpisodePort episodePort) {
        this.episodicStore = episodicStore;
        this.consolidators = consolidators != null ? List.copyOf(consolidators) : List.of();
        this.procedureStore = procedureStore;
        this.episodePort = episodePort;
    }

    public void setOrchestrator(MemoryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public MemoryConsolidationResult consolidate(ConsolidationRequest request) {
        if (request == null || request.sourceMemoryId() == null || request.context() == null) {
            return failed("invalid consolidation request", List.of());
        }
        MemoryId source = request.sourceMemoryId();
        episodicStore.refreshRetention(source);
        List<MemoryCell> cells = episodicStore.readAll(source);
        if (!request.sourceIds().isEmpty()) {
            cells = cells.stream().filter(cell -> request.sourceIds().contains(cell.cellId().id())).toList();
        }
        if (cells.isEmpty()) {
            return new MemoryConsolidationResult(MemoryConsolidationResult.Status.SKIPPED,
                    List.of(), List.of(), "no source episodes");
        }
        if (orchestrator == null) return failed("memory orchestrator unavailable", ids(cells));

        LinkedHashSet<String> promoted = new LinkedHashSet<>();
        boolean extractedAny = false;
        try {
            for (MemoryConsolidator consolidator : consolidators) {
                ConsolidationResult extracted = consolidator.consolidate(source, cells);
                extractedAny = extractedAny || !extracted.isEmpty();
                for (var fact : extracted.facts()) {
                    MemoryCandidate candidate = new MemoryCandidate(fact.toTriple(), "LLM_EXTRACTION",
                            fact.sourceCellIds().isEmpty() ? source.id() : fact.sourceCellIds().getFirst(),
                            com.yin.ai.platform.core.memory.MemoryType.SEMANTIC,
                            com.yin.ai.platform.core.memory.security.MemoryScope.USER,
                            fact.subject(), fact.predicate(), fact.object(), fact.createdAt(),
                            Map.of("confidence", fact.confidence(), "consolidated", true));
                    MemoryWriteResult result = orchestrator.remember(candidate, request.context());
                    if (result.status() == MemoryWriteResult.Status.FAILED) {
                        return failed(result.reason(), ids(cells));
                    }
                    promoted.addAll(result.affectedIds());
                }
                if (episodePort != null) {
                    for (var episode : extracted.episodes()) {
                        double importance = episode.cellCount() >= 3 ? 0.65 : 0.35;
                        if (importance >= 0.5) promoted.add(episodePort.save(episode, importance, request.context()));
                    }
                }
                if (procedureStore != null && request.successfulTask()) {
                    for (var procedure : extracted.procedures()) {
                        if (procedure.stepCount() >= 3) promoted.add(procedureStore.save(procedure).id());
                    }
                }
            }
            // Successful low-value sessions may be forgotten only after every promotion completes.
            if (request.sessionEnded() && !extractedAny) episodicStore.deleteAll(source);
            return new MemoryConsolidationResult(MemoryConsolidationResult.Status.COMPLETED,
                    List.copyOf(promoted), ids(cells), "consolidation completed");
        } catch (RuntimeException exception) {
            episodicStore.refreshRetention(source);
            return new MemoryConsolidationResult(MemoryConsolidationResult.Status.RETRY_PENDING,
                    List.copyOf(promoted), ids(cells), exception.getMessage());
        }
    }

    private MemoryConsolidationResult failed(String reason, List<String> retained) {
        return new MemoryConsolidationResult(MemoryConsolidationResult.Status.FAILED,
                List.of(), retained, reason);
    }

    private List<String> ids(List<MemoryCell> cells) {
        return cells.stream().map(cell -> cell.cellId().id()).toList();
    }
}
