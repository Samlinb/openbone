package io.github.samlinb.openbone.application;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import io.github.samlinb.openbone.core.model.EntityId;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;

public record EditorState(DocumentSnapshot model, Selection selection, Set<EntityId> keySelection) {
    public EditorState(DocumentSnapshot model, Selection selection) {
        this(model, selection, Set.of());
    }

    public EditorState {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(selection, "selection");
        selection.validate(model);
        keySelection = Set.copyOf(keySelection);
        for (var id : keySelection)
            if (!hasKey(model, id))
                throw new IllegalArgumentException("Unknown key selection ID: " + id);
    }

    public static EditorState of(DocumentSnapshot model) {
        return new EditorState(model, Selection.EMPTY);
    }

    public EditorState withModel(DocumentSnapshot value) {
        return new EditorState(value, selection.retainValid(value),
                keySelection.stream().filter(k -> hasKey(value, k)).collect(Collectors.toSet()));
    }

    private static boolean hasKey(DocumentSnapshot d, EntityId id) {
        return d.animations().stream().flatMap(a -> a.keys().stream()).anyMatch(k -> k.id().equals(id));
    }
}
