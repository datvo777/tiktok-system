package com.shortvideo.shared.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Decodes a Kafka record value into an {@link EventEnvelope} and its typed payload.
 *
 * <p>Generic type erasure means Jackson cannot deserialize {@code EventEnvelope<T>}
 * directly for a compile-time-known {@code T}; this decodes the payload as a raw
 * map first and converts it on demand.
 */
public final class EnvelopeCodec {

    private static final TypeReference<EventEnvelope<Map<String, Object>>> ENVELOPE_TYPE = new TypeReference<>() {};

    public static EventEnvelope<Map<String, Object>> decode(ObjectMapper mapper, String json) throws Exception {
        return mapper.readValue(json, ENVELOPE_TYPE);
    }

    public static <T> T payloadAs(ObjectMapper mapper, EventEnvelope<Map<String, Object>> envelope, Class<T> type) {
        return mapper.convertValue(envelope.payload(), type);
    }

    private EnvelopeCodec() {}
}
