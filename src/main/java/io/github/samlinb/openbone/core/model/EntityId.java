package io.github.samlinb.openbone.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Globally unique across entity kinds. References use this value, never names
 * or list indexes.
 */
public record EntityId(String value) {
    public EntityId {
        Objects.requireNonNull(value, "id");
        if (!UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("ID must be canonical UUID: " + value);
    }

    public static EntityId create() {
        return new EntityId(UUID.randomUUID().toString());
    }
}
