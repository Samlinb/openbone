package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/** 4.2 six components: rotation, translation X/Y, scale X/Y, shear Y. */
public record TransformConstraint(EntityId id, String name, int order, boolean enabled,
        List<EntityId> boneIds, EntityId targetId, boolean local, boolean relative,
        List<Double> mix, List<Double> offset) implements Constraint {
    public TransformConstraint {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(targetId);
        boneIds = List.copyOf(boneIds);
        mix = List.copyOf(mix);
        offset = List.copyOf(offset);
        if (order < 0 || boneIds.isEmpty() || new HashSet<>(boneIds).size() != boneIds.size())
            throw new IllegalArgumentException(m("transformconstraint.0001"));
        if (mix.size() != 6 || offset.size() != 6)
            throw new IllegalArgumentException(m("transformconstraint.0002"));
        for (double v : mix) {
            Finite.require(v, "transform mix");
            // Runtime exports permit extrapolating mixes outside the UI's 0–1 range.
            // Preserve finite coefficients rather than clipping imported poses.
        }
        offset.forEach(v -> Finite.require(v, "transform offset"));
    }

    public TransformConstraint withName(String v) {
        return new TransformConstraint(id, v, order, enabled, boneIds, targetId, local, relative, mix, offset);
    }

    public TransformConstraint withOrder(int v) {
        return new TransformConstraint(id, name, v, enabled, boneIds, targetId, local, relative, mix, offset);
    }

    public TransformConstraint withEnabled(boolean v) {
        return new TransformConstraint(id, name, order, v, boneIds, targetId, local, relative, mix, offset);
    }

    public TransformConstraint withTarget(EntityId v) {
        return new TransformConstraint(id, name, order, enabled, boneIds, v, local, relative, mix, offset);
    }

    public TransformConstraint withBones(List<EntityId> v) {
        return new TransformConstraint(id, name, order, enabled, v, targetId, local, relative, mix, offset);
    }

    public TransformConstraint withMode(boolean local, boolean relative) {
        return new TransformConstraint(id, name, order, enabled, boneIds, targetId, local, relative, mix, offset);
    }

    public TransformConstraint withMix(int index, double v) {
        var values = new ArrayList<>(mix);
        values.set(index, v);
        return new TransformConstraint(id, name, order, enabled, boneIds, targetId, local, relative, values, offset);
    }

    public TransformConstraint withOffset(int index, double v) {
        var values = new ArrayList<>(offset);
        values.set(index, v);
        return new TransformConstraint(id, name, order, enabled, boneIds, targetId, local, relative, mix, values);
    }

    public boolean inactive() {
        return !enabled || mix.stream().allMatch(v -> v == 0);
    }
}
