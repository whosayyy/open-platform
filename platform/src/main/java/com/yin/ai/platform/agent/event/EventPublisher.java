package com.yin.ai.platform.agent.event;

/** Legacy Agent Runtime event publication and replay port. */
public interface EventPublisher {

    /**
     * Publish one runtime event without prescribing persistence or transport.
     *
     * @param event 统一事件
     */
    void publish(AgentEvent event);

    /**
     * Replay events after the supplied transport cursor.
     *
     * @param taskId      任务 ID
     * @param lastEventId 客户端最后收到的事件 ID
     * @return 事件流
     */
    reactor.core.publisher.Flux<AgentEvent> replay(String taskId, String lastEventId);
}
