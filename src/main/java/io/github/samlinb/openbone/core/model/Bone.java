package io.github.samlinb.openbone.core.model;

import java.util.Objects;
import io.github.samlinb.openbone.core.math.*;

public record Bone(EntityId id, String name, EntityId parentId, Transform local, double length,
        Rgba color, Inherit inherit) {
    public enum Inherit { normal, onlyTranslation, noRotationOrReflection, noScale, noScaleOrReflection }

    public Bone(EntityId id, String name, EntityId parentId, Transform local, double length) {
        this(id, name, parentId, local, length, Rgba.WHITE, Inherit.normal);
    }

    public Bone {
        Objects.requireNonNull(color, "bone.color");
        Objects.requireNonNull(inherit, "bone.inherit");
        Objects.requireNonNull(id, "bone.id");
        Names.require(name);
        Objects.requireNonNull(local, "bone.local");
        Finite.require(length, "length");
        if (length < 0)
            throw new IllegalArgumentException("Bone length must be nonnegative");
    }

    public Bone withLocal(Transform value) {
        return new Bone(id, name, parentId, value, length, color, inherit);
    }

    public Bone withName(String value) {
        return new Bone(id, value, parentId, local, length, color, inherit);
    }

    public Bone withParent(EntityId value, Transform transform) {
        return new Bone(id, name, value, transform, length, color, inherit);
    }
}
