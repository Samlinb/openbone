package io.github.samlinb.openbone.core.model;

import java.util.*;

/**
 * Immutable P8 resource metadata. Empty skins mean the legacy implicit default
 * skin.
 */
public record Resources(List<Skin> skins, String activeSkin, List<EventDefinition> events,
        Map<EntityId, AtlasRegion> atlas) {
    public static final Resources EMPTY = new Resources(List.of(), "default", List.of(), Map.of());

    public Resources {
        skins = List.copyOf(skins);
        events = List.copyOf(events);
        atlas = Map.copyOf(atlas);
        Objects.requireNonNull(activeSkin);
    }

    public Resources skin(String name) {
        return new Resources(skins, name, events, atlas);
    }

    public Resources retain(Set<EntityId> removed) {
        var next = new ArrayList<Skin>();
        for (var skin : skins) {
            var slots = new LinkedHashMap<EntityId, Map<String, EntityId>>();
            skin.attachments().forEach((id, entries) -> {
                if (!removed.contains(id)) {
                    var row = new LinkedHashMap<String, EntityId>();
                    entries.forEach((key, a) -> {
                        if (!removed.contains(a))
                            row.put(key, a);
                    });
                    slots.put(id, row);
                }
            });
            next.add(new Skin(skin.name(), slots));
        }
        var regions = new HashMap<>(atlas);
        regions.keySet().removeAll(removed);
        return new Resources(next, activeSkin, events, regions);
    }
}
