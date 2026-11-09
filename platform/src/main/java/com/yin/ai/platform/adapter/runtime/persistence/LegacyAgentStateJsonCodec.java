package com.yin.ai.platform.adapter.runtime.persistence;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.codec.JsonJacksonCodec;

/** Stable JSON codec for the versioned legacy Agent Runtime Redis schema. */
final class LegacyAgentStateJsonCodec {

    static final JsonJacksonCodec INSTANCE = new JsonJacksonCodec(mapper()) {
        @Override
        protected void initTypeInclusion(ObjectMapper objectMapper) {
            objectMapper.activateDefaultTyping(
                    trustedSnapshotTypes(),
                    ObjectMapper.DefaultTyping.EVERYTHING,
                    JsonTypeInfo.As.PROPERTY);
        }
    };

    private LegacyAgentStateJsonCodec() {
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static PolymorphicTypeValidator trustedSnapshotTypes() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.yin.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.util.")
                .build();
    }
}
