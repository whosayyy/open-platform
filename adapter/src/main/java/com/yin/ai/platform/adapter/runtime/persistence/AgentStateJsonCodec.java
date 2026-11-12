package com.yin.ai.platform.adapter.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Encodes only portable scalar collections; no core implementation type metadata is stored. */
final class AgentStateJsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper mapper = new ObjectMapper();

    String encode(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent state is not JSON portable", exception);
        }
    }

    Map<String, Object> decode(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored agent state is invalid", exception);
        }
    }
}
