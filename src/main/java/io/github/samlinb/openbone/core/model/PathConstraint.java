package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Target identity is a slot, never a bone. Parameters are setup data, not
 * solver state.
 */
public record PathConstraint(EntityId id, String name, int order, boolean enabled, List<EntityId> boneIds,
        EntityId targetId,
        PositionMode positionMode, SpacingMode spacingMode, RotateMode rotateMode, double position, double spacing,
        double rotation, double mixRotate, double mixX, double mixY) implements Constraint {
    public enum PositionMode {
        FIXED, PERCENT
    }

    public enum SpacingMode {
        FIXED, PERCENT, LENGTH, PROPORTIONAL
    }

    public enum RotateMode {
        TANGENT, CHAIN, CHAIN_SCALE
    }

    public PathConstraint {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(targetId);
        Objects.requireNonNull(positionMode);
        Objects.requireNonNull(spacingMode);
        Objects.requireNonNull(rotateMode);
        boneIds = List.copyOf(boneIds);
        if (order < 0 || boneIds.isEmpty() || new HashSet<>(boneIds).size() != boneIds.size())
            throw new IllegalArgumentException(m("pathconstraint.0001"));
        for (double v : new double[] { position, spacing, rotation, mixRotate, mixX, mixY })
            Finite.require(v, "path parameter");
        for (double v : new double[] { mixRotate, mixX, mixY })
            if (v < 0 || v > 1)
                throw new IllegalArgumentException(m("pathconstraint.0002"));
    }

    public boolean inactive() {
        return !enabled || mixRotate == 0 && mixX == 0 && mixY == 0;
    }

    public PathConstraint withName(String v) {
        return new PathConstraint(id, v, order, enabled, boneIds, targetId, positionMode, spacingMode, rotateMode,
                position, spacing, rotation, mixRotate, mixX, mixY);
    }

    public PathConstraint withOrder(int v) {
        return new PathConstraint(id, name, v, enabled, boneIds, targetId, positionMode, spacingMode, rotateMode,
                position, spacing, rotation, mixRotate, mixX, mixY);
    }

    public PathConstraint withEnabled(boolean v) {
        return new PathConstraint(id, name, order, v, boneIds, targetId, positionMode, spacingMode, rotateMode,
                position, spacing, rotation, mixRotate, mixX, mixY);
    }

    public PathConstraint withTarget(EntityId v) {
        return new PathConstraint(id, name, order, enabled, boneIds, v, positionMode, spacingMode, rotateMode, position,
                spacing, rotation, mixRotate, mixX, mixY);
    }

    public PathConstraint withBones(List<EntityId> v) {
        return new PathConstraint(id, name, order, enabled, v, targetId, positionMode, spacingMode, rotateMode,
                position, spacing, rotation, mixRotate, mixX, mixY);
    }

    public PathConstraint withModes(PositionMode p, SpacingMode s, RotateMode r) {
        return new PathConstraint(id, name, order, enabled, boneIds, targetId, p, s, r, position, spacing, rotation,
                mixRotate, mixX, mixY);
    }

    public PathConstraint withValue(int n, double v) {
        return new PathConstraint(id, name, order, enabled, boneIds, targetId, positionMode, spacingMode, rotateMode,
                n == 0 ? v : position, n == 1 ? v : spacing, n == 2 ? v : rotation, n == 3 ? v : mixRotate,
                n == 4 ? v : mixX, n == 5 ? v : mixY);
    }
}
