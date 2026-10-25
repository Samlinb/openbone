package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Ordered multi-selection; the last ID is primary. Tree and viewport call the
 * same select API.
 */
public record Selection(List<EntityId> ids) {
    public static final Selection EMPTY = new Selection(List.of());

    public Selection {
        ids = List.copyOf(ids);
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Duplicate selection ID");
    }

    public static Selection of(EntityId id) {
        return new Selection(List.of(id));
    }

    public Optional<EntityId> primary() {
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(ids.size() - 1));
    }

    public Selection retainValid(DocumentSnapshot model) {
        return new Selection(ids.stream().filter(model::contains).toList());
    }

    public void validate(DocumentSnapshot model) {
        for (EntityId id : ids)
            if (!model.contains(id))
                throw new IllegalArgumentException("Unknown selection ID: " + id);
    }
}
