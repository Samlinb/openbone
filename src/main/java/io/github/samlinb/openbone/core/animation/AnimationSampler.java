package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;

/**
 * Stateless seek: always starts from Setup. No history, renderer or mutable
 * bone state.
 */
public final class AnimationSampler {
    private AnimationSampler() {
    }

    public static List<Constraint> constraints(DocumentSnapshot d, EntityId clip, double time, boolean loop) {
        var a = d.animation(clip);
        return constraints(d.constraints(), a, sampleTime(time, a.duration(), loop));
    }

    public static List<Constraint> constraints(List<Constraint> setup, AnimationClip clip, double time) {
        time = sampleTime(time, clip.duration(), false);
        var values = new LinkedHashMap<EntityId, Constraint>();
        var physics = new LinkedHashMap<EntityId, List<Double>>();
        for (var c : setup)
            values.put(c.id(), c);
        for (var track : clip.constraintTracks()) {
            var v = track.sample(time);
            if (v.isEmpty())
                continue;
            if (track.channel().physicsParameter() >= 0) {
                var parameters = physics.computeIfAbsent(track.constraintId(),
                        id -> new ArrayList<>(((PhysicsConstraint) values.get(id)).parameters()));
                parameters.set(track.channel().physicsParameter(), v.get().get(0));
            } else
                values.put(track.constraintId(), track.channel().apply(values.get(track.constraintId()), v.get()));
        }
        physics.forEach((id, p) -> {
            var c = (PhysicsConstraint) values.get(id);
            values.put(id, new PhysicsConstraint(c.id(), c.name(), c.order(), c.enabled(), c.boneId(), c.influence(),
                    c.limit(), p));
        });
        return List.copyOf(values.values());
    }

    public static WorldPose world(DocumentSnapshot d, EntityId clip, double time, boolean loop) {
        return d.evaluateAnimatedWorld(clip, time, loop);
    }

    public static Map<EntityId, List<Double>> deforms(DocumentSnapshot d, EntityId clip, double time, boolean loop) {
        var a = d.animation(clip);
        time = sampleTime(time, a.duration(), loop);
        Map<EntityId, List<Double>> result = new LinkedHashMap<>();
        for (var t : a.deforms()) {
            var v = t.sample(time);
            if (v.isPresent())
                result.put(t.attachmentId(), v.get());
        }
        return Collections.unmodifiableMap(result);
    }

    public static double sampleTime(double time, double duration, boolean loop) {
        Keyframe.seconds(time);
        Keyframe.seconds(duration);
        return loop && duration > 0 ? time % duration : Math.min(time, duration);
    }

    public static Map<EntityId, Transform> pose(DocumentSnapshot d, EntityId clip, double time, boolean loop) {
        var a = d.animation(clip);
        return pose(d.bones(), a, sampleTime(time, a.duration(), loop));
    }

    public static Map<EntityId, Transform> pose(List<Bone> setup, AnimationClip a, double time) {
        time = sampleTime(time, a.duration(), false);
        var byBone = new HashMap<EntityId, Bone>();
        setup.forEach(b -> byBone.put(b.id(), b));
        var result = new LinkedHashMap<EntityId, Transform>();
        for (var track : a.timelines()) {
            var value = track.sample(time);
            if (value.isEmpty())
                continue;
            var v = value.get();
            var b = byBone.get(track.boneId());
            var t = result.getOrDefault(b.id(), b.local());
            var s = b.local();
            result.put(b.id(), switch (track.channel()) {
                case TRANSLATE ->
                    new Transform(s.x() + v.get(0), s.y() + v.get(1), t.rotation(), t.scaleX(), t.scaleY(), t.shearX(), t.shearY());
                case ROTATE -> new Transform(t.x(), t.y(), s.rotation() + v.get(0), t.scaleX(), t.scaleY(), t.shearX(), t.shearY());
                case SCALE -> new Transform(t.x(), t.y(), t.rotation(), s.scaleX() * v.get(0), s.scaleY() * v.get(1), t.shearX(), t.shearY());
                case SHEAR -> new Transform(t.x(), t.y(), t.rotation(), t.scaleX(), t.scaleY(), s.shearX() + v.get(0), s.shearY() + v.get(1));
            });
        }
        return Collections.unmodifiableMap(result);
    }
}
