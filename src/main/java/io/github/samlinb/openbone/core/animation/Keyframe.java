package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import io.github.samlinb.openbone.core.model.EntityId;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Relative translation/rotation offsets, scale factors. Curves belong to the
 * outgoing segment.
 */
public record Keyframe(EntityId id, double time, List<Double> values, List<Curve> curves) {
    public Keyframe {
        Objects.requireNonNull(id);
        time = seconds(time);
        values = List.copyOf(values);
        curves = List.copyOf(curves);
        if (values.isEmpty() || values.size() != curves.size())
            throw new IllegalArgumentException(m("keyframe.0001"));
        values.forEach(v -> Finite.require(v, "key value"));
    }

    public static double seconds(double t) {
        Finite.require(t, "time");
        if (t < 0)
            throw new IllegalArgumentException(m("keyframe.0002"));
        return t == 0 ? 0 : t;
    }

    public Keyframe at(double t) {
        return new Keyframe(id, t, values, curves);
    }

    public Keyframe withCurves(List<Curve> c) {
        return new Keyframe(id, time, values, c);
    }
}
