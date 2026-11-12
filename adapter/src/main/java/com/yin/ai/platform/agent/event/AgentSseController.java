package com.yin.ai.platform.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @deprecated Spring Web transport belongs to the Spring adapter layer. Use
 * {@link com.yin.ai.platform.adapter.spring.web.AgentSseController}.
 * This compatibility type is intentionally not a Spring component.
 */
@Deprecated(forRemoval = false)
public class AgentSseController
        extends com.yin.ai.platform.adapter.spring.web.AgentSseController {

    public AgentSseController(DualWriteEventPublisher eventPublisher, ObjectMapper objectMapper) {
        super(eventPublisher, objectMapper);
    }
}
