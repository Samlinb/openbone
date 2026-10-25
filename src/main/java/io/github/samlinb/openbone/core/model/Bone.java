package io.github.samlinb.openbone.core.model;

import java.util.Objects;
import io.github.samlinb.openbone.core.math.*;

public record Bone(EntityId id, String name, EntityId parentId, Transform local, double length) {
    public Bone {
        Objects.requireNonNull(id, "bone.id");
        Names.require(name);
        Objects.requireNonNull(local, "bone.local");
        Finite.require(length, "length");
        if (length < 0)
            throw new IllegalArgumentException("Bone length must be nonnegative");
    }

    public Bone withLocal(Transform value) {
        return new Bone(id, name, parentId, value, length);
    }

    public Bone withName(String value) {
        return new Bone(id, value, parentId, local, length);
    }

    public Bone withParent(EntityId value, Transform transform) {
        return new Bone(id, name, value, transform, length);
    }
}
