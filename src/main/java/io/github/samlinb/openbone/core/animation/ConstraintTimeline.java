package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * A typed parameter group with stable constraint/key identities and outgoing
 * curves.
 */
public record ConstraintTimeline(EntityId constraintId, ConstraintChannel channel, List<Keyframe> keys) {
    public ConstraintTimeline {
        Objects.requireNonNull(constraintId);
        Objects.requireNonNull(channel);
        keys = List.copyOf(keys);
        if (keys.isEmpty())
            throw new IllegalArgumentException(m("constrainttimeline.0001"));
        double previous = -1;
        Set<EntityId> ids = new HashSet<>();
        for (var k : keys) {
            if (k.time() <= previous)
                throw new IllegalArgumentException(m("constrainttimeline.0002"));
            previous = k.time();
            if (!ids.add(k.id()))
                throw new IllegalArgumentException("Duplicate constraint key ID");
            if (k.values().size() != channel.components())
                throw new IllegalArgumentException(m("constrainttimeline.0003"));
            for (int i = 0; i < channel.components(); i++)
                if (channel.discrete(i) && (k.values().get(i) != 0 && k.values().get(i) != 1
                        || k.curves().get(i).kind() != Curve.Kind.STEPPED))
                    throw new IllegalArgumentException(m("constrainttimeline.0004"));
        }
    }

    public Optional<List<Double>> sample(double time) {
        return TimelineSample.sample(keys, time);
    }

    /**
     * Includes Setup and every cubic control value. This certifies the complete
     * time domain.
     */
    public double[][] bounds(Constraint setup) {
        var value = channel.values(setup);
        double[][] range = new double[channel.components()][2];
        for (int c = 0; c < range.length; c++)
            range[c][0] = range[c][1] = value.get(c);
        for (int n = 0; n < keys.size(); n++)
            for (int c = 0; c < range.length; c++) {
                var k = keys.get(n);
                include(range[c], k.values().get(c));
                if (n + 1 < keys.size() && k.curves().get(c).kind() == Curve.Kind.BEZIER) {
                    double delta = Finite.require(keys.get(n + 1).values().get(c) - k.values().get(c),
                            "constraint curve difference");
                    for (double y : List.of(k.curves().get(c).y1(), k.curves().get(c).y2()))
                        include(range[c], Finite.require(k.values().get(c) + delta * y, "constraint curve control"));
                }
            }
        return range;
    }

    private static void include(double[] range, double v) {
        range[0] = Math.min(range[0], v);
        range[1] = Math.max(range[1], v);
    }

    public void validate(Constraint setup) {
        if (!channel.supports(setup))
            throw new IllegalArgumentException(m("constrainttimeline.0005"));
        for (var k : keys)
            channel.apply(setup, k.values());
        var range = bounds(setup);
        for (boolean upper : List.of(false, true)) {
            List<Double> values = new ArrayList<>();
            for (var r : range)
                values.add(r[upper ? 1 : 0]);
            channel.apply(setup, values);
        }
    }

    /**
     * Deliberately conservative absolute envelopes, never used as rendered
     * parameter values.
     */
    public static Constraint envelope(AnimationClip clip, Constraint setup) {
        Constraint result = setup;
        for (var t : clip.constraintTracks())
            if (t.constraintId.equals(setup.id())) {
                var range = t.bounds(setup);
                List<Double> values = new ArrayList<>();
                for (var r : range)
                    values.add(
                            t.channel == ConstraintChannel.PATH_POSITION || t.channel == ConstraintChannel.PATH_SPACING
                                    ? Math.max(Math.abs(r[0]), Math.abs(r[1]))
                                    : r[1]);
                result = t.channel.apply(result, values);
            }
        return result;
    }
}
