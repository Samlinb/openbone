package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.EntityId;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Flattened XY offsets: one pair per unweighted vertex, or per weighted
 * influence.
 * The 4.2 format uses one common interpolation percentage curve for all these
 * components.
 */
public record DeformTimeline(EntityId attachmentId, List<Keyframe> keys) {
    public DeformTimeline {
        Objects.requireNonNull(attachmentId);
        keys = List.copyOf(keys);
        if (keys.isEmpty())
            throw new IllegalArgumentException(m("deformtimeline.0001"));
        int size = keys.get(0).values().size();
        if (size % 2 != 0)
            throw new IllegalArgumentException(m("deformtimeline.0002"));
        double previous = -1;
        Set<EntityId> ids = new HashSet<>();
        for (var k : keys) {
            if (k.time() <= previous)
                throw new IllegalArgumentException(m("deformtimeline.0003"));
            previous = k.time();
            if (!ids.add(k.id()))
                throw new IllegalArgumentException("Duplicate deform key ID");
            if (k.values().size() != size)
                throw new IllegalArgumentException(m("deformtimeline.0004"));
            if (k.curves().stream().anyMatch(c -> !c.equals(k.curves().get(0))))
                throw new IllegalArgumentException(m("deformtimeline.0005"));
        }
    }

    public Optional<List<Double>> sample(double time) {
        return TimelineSample.sample(keys, time);
    }

    public static double[] bounds(AnimationClip clip, EntityId attachment, int size) {
        double[] max = new double[size];
        for (var t : clip.deforms())
            if (t.attachmentId().equals(attachment))
                for (int i = 0; i < t.keys().size(); i++) {
                    var key = t.keys().get(i);
                    for (int c = 0; c < size; c++) {
                        double v = key.values().get(c);
                        max[c] = Math.max(max[c], Math.abs(v));
                        if (i + 1 < t.keys().size() && key.curves().get(c).kind() == Curve.Kind.BEZIER) {
                            double delta = Finite.require(t.keys().get(i + 1).values().get(c) - v, "deform difference");
                            for (double y : List.of(key.curves().get(c).y1(), key.curves().get(c).y2()))
                                max[c] = Math.max(max[c], Math.abs(Finite.require(v + delta * y, "deform control")));
                        }
                    }
                }
        return max;
    }
}
