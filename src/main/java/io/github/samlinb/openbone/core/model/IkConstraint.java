package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/** Own setup data; 4.2 compress has meaning only for a one-bone constraint. */
public record IkConstraint(EntityId id, String name, int order, boolean enabled,
        List<EntityId> boneIds, EntityId targetId, double mix, double softness,
        boolean bendPositive, boolean compress, boolean stretch, boolean uniform) implements Constraint {
    public IkConstraint {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(targetId);
        boneIds = List.copyOf(boneIds);
        if (order < 0)
            throw new IllegalArgumentException(m("ikconstraint.0001"));
        if (boneIds.isEmpty() || boneIds.size() > 2 || new HashSet<>(boneIds).size() != boneIds.size())
            throw new IllegalArgumentException(m("ikconstraint.0002"));
        Finite.require(mix, "IK mix");
        Finite.require(softness, "IK softness");
        if (mix < 0 || mix > 1 || softness < 0)
            throw new IllegalArgumentException(m("ikconstraint.0003"));
        if (boneIds.size() == 2 && compress)
            throw new IllegalArgumentException(m("ikconstraint.0004"));
        if (boneIds.size() == 1 && softness != 0)
            throw new IllegalArgumentException(m("ikconstraint.0005"));
    }

    public IkConstraint with(String name, int order, boolean enabled, List<EntityId> bones, EntityId target,
            double mix, double softness, boolean bend, boolean compress, boolean stretch, boolean uniform) {
        return new IkConstraint(id, name, order, enabled, bones, target, mix, softness, bend, compress, stretch,
                uniform);
    }

    public IkConstraint withName(String v) {
        return with(v, order, enabled, boneIds, targetId, mix, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withOrder(int v) {
        return with(name, v, enabled, boneIds, targetId, mix, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withEnabled(boolean v) {
        return with(name, order, v, boneIds, targetId, mix, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withTarget(EntityId v) {
        return with(name, order, enabled, boneIds, v, mix, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withBones(List<EntityId> v) {
        return with(name, order, enabled, v, targetId, mix, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withMix(double v) {
        return with(name, order, enabled, boneIds, targetId, v, softness, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withSoftness(double v) {
        return with(name, order, enabled, boneIds, targetId, mix, v, bendPositive, compress, stretch, uniform);
    }

    public IkConstraint withOptions(boolean bend, boolean compress, boolean stretch, boolean uniform) {
        return with(name, order, enabled, boneIds, targetId, mix, softness, bend, compress, stretch, uniform);
    }
}
