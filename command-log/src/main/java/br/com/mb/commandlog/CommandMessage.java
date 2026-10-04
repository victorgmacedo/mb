package br.com.mb.commandlog;

import java.util.Objects;

public record CommandMessage(String topic, String key, String value, int partition, long offset) {

    public CommandMessage(String topic, String key, String value) {
        this(topic, key, value, -1, -1);
    }

    public CommandMessage {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
