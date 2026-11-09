package com.yin.ai.platform.agent.pattern;

import com.yin.ai.platform.agent.event.AgentEvent;
import com.yin.ai.platform.agent.event.EventPublisher;
import com.yin.ai.platform.agent.team.AgentTeamCoordinator;
import reactor.core.publisher.Flux;

/** Unified Runtime pattern for the bounded creator research team. */
public class MultiAgentPattern implements AgentPattern {

    private final AgentTeamCoordinator coordinator;

    public MultiAgentPattern(AgentTeamCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public AgentMode mode() {
        return AgentMode.MULTI_AGENT;
    }

    @Override
    public Flux<AgentEvent> execute(AgentExecutionContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            EventPublisher delegate = ctx.getEventPublisher();
            ctx.setEventPublisher(new EventPublisher() {
                @Override
                public void publish(AgentEvent event) {
                    delegate.publish(event);
                    if (ctx.getTaskId().equals(event.taskId())) sink.next(event);
                }

                @Override
                public Flux<AgentEvent> replay(String taskId, String lastEventId) {
                    return delegate.replay(taskId, lastEventId);
                }
            });
            try {
                coordinator.execute(ctx);
                sink.complete();
            } catch (Throwable failure) {
                sink.error(failure);
            }
        }).timeout(AgentTeamCoordinator.TEAM_TIMEOUT)
                .doOnError(error -> coordinator.cancelChildren(ctx.getTaskId(),
                        "multi-agent execution stopped: " + error.getClass().getSimpleName()));
    }

    @Override
    public boolean resumable() {
        return true;
    }
}
