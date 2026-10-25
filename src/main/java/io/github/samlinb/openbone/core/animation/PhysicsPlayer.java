package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Transient deterministic replay cache. A seek is a fixed-tick replay from
 * setup;
 * sub-tick display uses POSE and never integrates. No state enters a
 * document/history.
 */
public final class PhysicsPlayer {
    private DocumentSnapshot document;
    private EntityId clipId;
    private int frame = -1;
    private Map<EntityId, PhysicsSolver.State> states = Map.of();
    private long evaluations, resets;

    public static boolean active(DocumentSnapshot d) {
        return d.animations().isEmpty()
                ? d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint p && p.mix() != 0)
                : d.animations().stream().anyMatch(a -> PhysicsTraceProof.potential(d.constraints(), a));
    }

    private static boolean active(DocumentSnapshot d, EntityId clip) {
        return d.animations().isEmpty() ? active(d) : PhysicsTraceProof.potential(d.constraints(), d.animation(clip));
    }

    public static double duration(DocumentSnapshot d, EntityId clip) {
        double actual = d.animations().isEmpty() ? 0 : d.animation(clip).duration();
        return d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint) && actual == 0 ? 5 : actual;
    }

    public static void validateTime(DocumentSnapshot d, double time) {
        Keyframe.seconds(time);
        if (active(d) && time > PhysicsEnvelope.MAX_SECONDS)
            throw new IllegalArgumentException(m("physicsplayer.0001"));
    }

    public static void validateTime(DocumentSnapshot d, EntityId clip, double time) {
        Keyframe.seconds(time);
        if (active(d, clip) && time > PhysicsEnvelope.MAX_SECONDS)
            throw new IllegalArgumentException(m("physicsplayer.0001"));
    }

    public void reset() {
        document = null;
        clipId = null;
        frame = -1;
        states = Map.of();
    }

    public void invalidate(DocumentSnapshot d) {
        if (document != null && !document.equals(d))
            reset();
    }

    private static Map<EntityId, Transform> pose(DocumentSnapshot d, EntityId clip, double time) {
        return d.animations().isEmpty() ? Map.of() : AnimationSampler.pose(d, clip, time, false);
    }

    private static Map<EntityId, List<Double>> deforms(DocumentSnapshot d, EntityId clip, double time) {
        return d.animations().isEmpty() ? Map.of() : AnimationSampler.deforms(d, clip, time, false);
    }

    public Map<EntityId, PhysicsSolver.State> states() {
        return states;
    }

    public long evaluations() {
        return evaluations;
    }

    public long resets() {
        return resets;
    }

    public int frame() {
        return frame;
    }

    public WorldPose seek(DocumentSnapshot d, EntityId clip, double time, boolean loop) {
        Keyframe.seconds(time);
        double duration = duration(d, clip);
        boolean active = active(d, clip);
        if (duration > PhysicsEnvelope.MAX_SECONDS && active)
            throw new IllegalArgumentException(m("physicsplayer.0002"));
        if (!loop)
            validateTime(d, clip, time);
        double phase = AnimationSampler.sampleTime(time, duration, loop);
        if (d.animations().isEmpty() && !clip.equals(AnimationClip.DEFAULT_ID))
            throw new IllegalArgumentException(m("physicsplayer.0003"));
        if (!active)
            return d.animations().isEmpty() ? d.evaluateWorld() : AnimationSampler.world(d, clip, phase, false);
        int target = PhysicsEnvelope.tickAt(phase);
        if (target > PhysicsEnvelope.MAX_STEPS)
            throw new IllegalArgumentException(m("physicsplayer.0004"));
        if (!d.equals(document) || !Objects.equals(clip, clipId) || target < frame) {
            reset();
            document = d;
            clipId = clip;
            resets++;
        }
        try {
            if (frame < 0) {
                step(d, clip, 0, PhysicsSolver.Mode.RESET);
                frame = 0;
            }
            while (frame < target) {
                step(d, clip, frame + 1, PhysicsSolver.Mode.UPDATE);
                frame++;
            }
            return d.animations().isEmpty() ? d.evaluatePhysicsPose(Map.of(), Map.of(), states)
                    : d.evaluateAnimatedPhysicsPose(clip, phase, states);
        } catch (RuntimeException failure) {
            reset();
            throw failure;
        }
    }

    private void step(DocumentSnapshot d, EntityId clip, int frame, PhysicsSolver.Mode mode) {
        var transaction = new PhysicsFrame(mode, states, frame);
        ConstraintSolver.evaluate(d.bones(), pose(d, clip, frame / 60.0),
                d.animations().isEmpty() ? d.constraints() : AnimationSampler.constraints(d, clip, frame / 60.0, false),
                d.slots(), d.attachments(), transaction);
        states = transaction.snapshot();
        evaluations++;
    }
}
