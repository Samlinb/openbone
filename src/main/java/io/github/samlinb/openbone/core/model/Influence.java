package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * A point in this influence bone's local space, not an inverse-bind matrix or
 * world point.
 */
public record Influence(EntityId boneId, Vec2 point, double weight) {
    public Influence {
        Objects.requireNonNull(boneId);
        Objects.requireNonNull(point);
        Finite.require(weight, "weight");
        if (weight < 0)
            throw new IllegalArgumentException(m("influence.0001"));
    }

    public static List<Influence> normalize(List<Influence> input) {
        if (input.isEmpty() || input.size() > 4)
            throw new IllegalArgumentException(m("influence.0002"));
        double max = input.stream().mapToDouble(Influence::weight).max().orElse(0);
        if (max == 0)
            throw new IllegalArgumentException(m("influence.0003"));
        double sum = 0;
        Set<EntityId> ids = new HashSet<>();
        for (var i : input) {
            if (!ids.add(i.boneId()))
                throw new IllegalArgumentException(m("influence.0004"));
            sum += i.weight() / max;
        }
        final double total = sum;
        return input.stream().map(i -> new Influence(i.boneId(), i.point(), (i.weight() / max) / total)).toList();
    }

    public static void validate(List<Influence> values) {
        if (values.isEmpty() || values.size() > 4)
            throw new IllegalArgumentException(m("influence.0002"));
        double sum = 0;
        Set<EntityId> ids = new HashSet<>();
        for (var i : values) {
            if (!ids.add(i.boneId()))
                throw new IllegalArgumentException(m("influence.0005"));
            sum += i.weight();
        }
        if (Math.abs(sum - 1) > 1e-12)
            throw new IllegalArgumentException(m("influence.0006"));
    }
}
