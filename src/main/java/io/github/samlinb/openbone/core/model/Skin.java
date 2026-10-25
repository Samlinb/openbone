package io.github.samlinb.openbone.core.model;

import java.util.*;

/**
 * A placeholder name is scoped by skin and slot; attachment identity is global.
 */
public record Skin(String name, Map<EntityId, Map<String, EntityId>> attachments) {
    public Skin {
        Names.require(name);
        var copy = new LinkedHashMap<EntityId, Map<String, EntityId>>();
        attachments.forEach((slot, entries) -> {
            Objects.requireNonNull(slot);
            entries.forEach((key, id) -> {
                Names.require(key);
                Objects.requireNonNull(id);
            });
            copy.put(slot, Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        });
        attachments = Collections.unmodifiableMap(copy);
    }

    public EntityId find(EntityId slot, String name) {
        return attachments.getOrDefault(slot, Map.of()).get(name);
    }
}
