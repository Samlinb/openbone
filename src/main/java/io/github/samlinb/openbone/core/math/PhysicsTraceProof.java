package io.github.samlinb.openbone.core.math;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Complete finite-horizon state enumeration for changing spring coefficients.
 * Every reachable fixed tick (including all pending catch-up steps) is
 * evaluated.
 * Only offset bounds escape; these states are not a renderer cache or document
 * data.
 */
public final class PhysicsTraceProof {
    public static final long MAX_WORK = 2_000_000;

    private PhysicsTraceProof() {
    }

    public record Offsets(double xy, double rotation, double scale, double velocity) {
        public static final Offsets ZERO = new Offsets(0, 0, 0, 0);

        Offsets include(PhysicsSolver.State s) {
            return new Offsets(Math.max(xy, Math.max(Math.abs(s.xOffset()), Math.abs(s.yOffset()))),
                    Math.max(rotation, Math.abs(s.rotationOffset())), Math.max(scale, Math.abs(s.scaleOffset())),
                    Math.max(velocity, Math.max(Math.max(Math.abs(s.xVelocity()), Math.abs(s.yVelocity())),
                            Math.max(Math.abs(s.rotationVelocity()), Math.abs(s.scaleVelocity())))));
        }
    }

    public record Proof(Map<EntityId, Offsets> states, long work) {
        public Proof {
            states = Map.copyOf(states);
        }
    }

    public static boolean potential(List<Constraint> constraints, AnimationClip clip) {
        return constraints.stream()
                .anyMatch(c -> c instanceof PhysicsConstraint p && p.enabled()
                        && (p.mix() != 0 || clip.constraintTracks().stream()
                                .anyMatch(t -> t.constraintId().equals(p.id())
                                        && t.channel() == ConstraintChannel.PHYSICS_MIX
                                        && t.keys().stream().anyMatch(k -> k.values().get(0) > 0))));
    }

    public static void validateParameters(PhysicsConstraint p, AnimationClip clip) {
        double[] low = p.parameters().stream().mapToDouble(Double::doubleValue).toArray(), high = low.clone();
        for (var t : clip.constraintTracks())
            if (t.constraintId().equals(p.id()) && t.channel().physicsParameter() >= 0) {
                var r = t.bounds(p)[0];
                int n = t.channel().physicsParameter();
                low[n] = Math.min(low[n], r[0]);
                high[n] = Math.max(high[n], r[1]);
            }
        // Independent convex hulls intentionally exclude unproved correlated parameter
        // cancellations. Setup remains part of the before-first-key domain.
        if (high[1] / low[3] * PhysicsConstraint.STEP * PhysicsConstraint.STEP > 1)
            throw new IllegalArgumentException(m("physicstraceproof.0001"));
    }

    public static Proof trace(List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
            List<Constraint> constraints, AnimationClip clip, long budget) {
        boolean dynamic = clip.constraintTracks().stream().anyMatch(t -> t.channel().physicsParameter() >= 0
                && constraints.stream().anyMatch(c -> c.id().equals(t.constraintId()) && c.enabled()));
        if (!dynamic || !potential(constraints, clip))
            return new Proof(Map.of(), 0);
        double duration = clip.duration() == 0 ? 5 : clip.duration();
        if (duration > PhysicsEnvelope.MAX_SECONDS)
            throw new IllegalArgumentException(m("physicsplayer.0002"));
        int count = PhysicsEnvelope.tickAt(duration);
        long work = Math.multiplyExact(Math.multiplyExact((long) count + 1, bones.size()),
                Math.max(1, constraints.size()));
        if (work > budget)
            throw new IllegalArgumentException(m("physicstraceproof.0002"));
        Map<EntityId, PhysicsSolver.State> states = Map.of();
        Map<EntityId, Offsets> bounds = new LinkedHashMap<>();
        for (var c : constraints)
            if (c instanceof PhysicsConstraint)
                bounds.put(c.id(), Offsets.ZERO);
        for (int tick = 0; tick <= count; tick++) {
            double time = tick / 60.;
            var parameters = AnimationSampler.constraints(constraints, clip, time);
            var frame = new PhysicsFrame(tick == 0 ? PhysicsSolver.Mode.RESET : PhysicsSolver.Mode.UPDATE, states,
                    tick);
            ConstraintSolver.evaluate(bones, AnimationSampler.pose(bones, clip, time), parameters, slots, attachments,
                    frame);
            states = frame.snapshot();
            for (var entry : states.entrySet())
                bounds.put(entry.getKey(), bounds.getOrDefault(entry.getKey(), Offsets.ZERO).include(entry.getValue()));
        }
        return new Proof(bounds, work);
    }
}
