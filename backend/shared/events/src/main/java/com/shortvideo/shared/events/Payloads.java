package com.shortvideo.shared.events;

import java.util.Map;

/** Reads fields out of a decoded event payload. */
public final class Payloads {

    /**
     * A field the consumer cannot do without. Throws {@link IllegalArgumentException}, which the Kafka
     * error handler treats as not retryable, so a malformed event goes straight to the dead-letter topic
     * and raises its alert. The alternatives are worse: {@code String.valueOf(null)} stores the text
     * {@code "null"} as an id, and a bare cast carries the null on until something unrelated fails.
     */
    public static String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Event payload is missing required field '" + key + "'");
        }
        return value.toString();
    }

    private Payloads() {}
}
