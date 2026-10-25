package io.github.samlinb.openbone.application;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Atomic pure edits. Recording replaces the exact timestamp and preserves its
 * stable key ID;
 * move/paste reject collisions with unselected keys. No epsilon or implicit
 * frame rounding.
 */
public final class AnimationEdits {
    private AnimationEdits() {
    }

    public static DocumentSnapshot clip(DocumentSnapshot d, EntityId clip, UnaryOperator<AnimationClip> edit) {
        d.animation(clip);
        return d.withAnimations(d.animations().stream().map(a -> a.id().equals(clip) ? edit.apply(a) : a).toList());
    }

    public static DocumentSnapshot put(DocumentSnapshot d, EntityId clip, EntityId bone, Channel channel, double time,
            List<Double> values) {
        d.bone(bone);
        Keyframe.seconds(time);
        return clip(d, clip, a -> {
            var tracks = new ArrayList<>(a.timelines());
            BoneTimeline old = tracks.stream().filter(t -> t.boneId().equals(bone) && t.channel() == channel)
                    .findFirst().orElse(null);
            var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
            var existing = keys.stream().filter(k -> k.time() == time).findFirst().orElse(null);
            keys.removeIf(k -> k.time() == time);
            keys.add(new Keyframe(existing == null ? EntityId.create() : existing.id(), time, values,
                    existing == null ? Collections.nCopies(channel.components(), Curve.LINEAR) : existing.curves()));
            keys.sort(Comparator.comparingDouble(Keyframe::time));
            var next = new BoneTimeline(bone, channel, keys);
            if (old == null)
                tracks.add(next);
            else
                tracks.set(tracks.indexOf(old), next);
            return new AnimationClip(a.id(), a.name(), tracks, a.deforms(), a.constraintTracks(), a.discrete());
        });
    }

    public static DocumentSnapshot putConstraint(DocumentSnapshot d, EntityId clip, EntityId constraint,
            ConstraintChannel channel, double time, List<Double> values) {
        channel.apply(d.constraint(constraint), values);
        Keyframe.seconds(time);
        return clip(d, clip, a -> {
            var tracks = new ArrayList<>(a.constraintTracks());
            var old = tracks.stream().filter(t -> t.constraintId().equals(constraint) && t.channel() == channel)
                    .findFirst().orElse(null);
            var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
            var existing = keys.stream().filter(k -> k.time() == time).findFirst().orElse(null);
            keys.removeIf(k -> k.time() == time);
            var curves = new ArrayList<Curve>();
            for (int i = 0; i < channel.components(); i++)
                curves.add(channel.discrete(i) ? Curve.STEPPED
                        : existing == null ? Curve.LINEAR : existing.curves().get(i));
            keys.add(new Keyframe(existing == null ? EntityId.create() : existing.id(), time, values, curves));
            keys.sort(Comparator.comparingDouble(Keyframe::time));
            var next = new ConstraintTimeline(constraint, channel, keys);
            if (old == null)
                tracks.add(next);
            else
                tracks.set(tracks.indexOf(old), next);
            return new AnimationClip(a.id(), a.name(), a.timelines(), a.deforms(), tracks, a.discrete());
        });
    }

    public static DocumentSnapshot record(DocumentSnapshot d, EntityId clip, EntityId bone, double time,
            Transform absolute, Set<Channel> channels) {
        var s = d.bone(bone).local();
        for (var c : Channel.values())
            if (channels.contains(c))
                d = put(d, clip, bone, c, time, switch (c) {
                    case TRANSLATE -> List.of(absolute.x() - s.x(), absolute.y() - s.y());
                    case ROTATE -> List.of(absolute.rotation() - s.rotation());
                    case SCALE -> List.of(factor(absolute.scaleX(), s.scaleX()), factor(absolute.scaleY(), s.scaleY()));
                });
        return d;
    }

    private static double factor(double value, double setup) {
        if (setup == 0) {
            if (value != 0)
                throw new IllegalArgumentException(m("animationedits.0001"));
            return 1;
        }
        return Finite.require(value / setup, "scale factor");
    }

    public static DocumentSnapshot remove(DocumentSnapshot d, EntityId clip, Set<EntityId> ids) {
        return map(d, clip, ids, k -> null);
    }

    public static DocumentSnapshot curves(DocumentSnapshot d, EntityId clip, Set<EntityId> ids, List<Curve> curves) {
        Set<EntityId> deformIds = new HashSet<>();
        d.animation(clip).deforms().forEach(t -> t.keys().forEach(k -> deformIds.add(k.id())));
        Map<EntityId, ConstraintChannel> constraintKeys = new HashMap<>();
        d.animation(clip).constraintTracks()
                .forEach(t -> t.keys().forEach(k -> constraintKeys.put(k.id(), t.channel())));
        return map(d, clip, ids, k -> {
            if (deformIds.contains(k.id()) && curves.size() == 1)
                return k.withCurves(Collections.nCopies(k.values().size(), curves.get(0)));
            if (constraintKeys.containsKey(k.id())) {
                var channel = constraintKeys.get(k.id());
                if (curves.size() != 1 && curves.size() != channel.components())
                    throw new IllegalArgumentException(m("animationedits.0002"));
                var next = new ArrayList<Curve>();
                for (int i = 0; i < channel.components(); i++)
                    next.add(channel.discrete(i) ? Curve.STEPPED : curves.get(curves.size() == 1 ? 0 : i));
                return k.withCurves(next);
            }
            if (curves.size() != k.values().size())
                throw new IllegalArgumentException(m("animationedits.0003"));
            return k.withCurves(curves);
        });
    }

    public static DocumentSnapshot move(DocumentSnapshot d, EntityId clip, Set<EntityId> ids, double delta) {
        Finite.require(delta, "delta time");
        return map(d, clip, ids, k -> k.at(k.time() + delta));
    }

    private static DocumentSnapshot map(DocumentSnapshot d, EntityId clip, Set<EntityId> ids,
            UnaryOperator<Keyframe> edit) {
        return clip(d, clip, a -> {
            Set<EntityId> remaining = new HashSet<>(ids);
            List<BoneTimeline> tracks = new ArrayList<>();
            for (var t : a.timelines()) {
                var keys = new ArrayList<Keyframe>();
                for (var k : t.keys()) {
                    if (remaining.remove(k.id())) {
                        var next = edit.apply(k);
                        if (next != null)
                            keys.add(next);
                    } else
                        keys.add(k);
                }
                keys.sort(Comparator.comparingDouble(Keyframe::time));
                if (!keys.isEmpty())
                    tracks.add(new BoneTimeline(t.boneId(), t.channel(), keys));
            }
            List<DeformTimeline> deforms = new ArrayList<>();
            for (var t : a.deforms()) {
                List<Keyframe> keys = new ArrayList<>();
                for (var k : t.keys()) {
                    if (remaining.remove(k.id())) {
                        var next = edit.apply(k);
                        if (next != null)
                            keys.add(next);
                    } else
                        keys.add(k);
                }
                keys.sort(Comparator.comparingDouble(Keyframe::time));
                if (!keys.isEmpty())
                    deforms.add(new DeformTimeline(t.attachmentId(), keys));
            }
            List<ConstraintTimeline> constraints = new ArrayList<>();
            for (var t : a.constraintTracks()) {
                List<Keyframe> keys = new ArrayList<>();
                for (var k : t.keys()) {
                    if (remaining.remove(k.id())) {
                        var next = edit.apply(k);
                        if (next != null)
                            keys.add(next);
                    } else
                        keys.add(k);
                }
                keys.sort(Comparator.comparingDouble(Keyframe::time));
                if (!keys.isEmpty())
                    constraints.add(new ConstraintTimeline(t.constraintId(), t.channel(), keys));
            }
            if (!remaining.isEmpty())
                throw new IllegalArgumentException(m("animationedits.0004"));
            return new AnimationClip(a.id(), a.name(), tracks, deforms, constraints, a.discrete());
        });
    }

    public record CopiedKey(EntityId bone, Channel channel, Keyframe key, String layout,
            ConstraintChannel constraintChannel) {
        public CopiedKey(EntityId bone, Channel channel, Keyframe key) {
            this(bone, channel, key, "", null);
        }

        public CopiedKey(EntityId bone, Channel channel, Keyframe key, String layout) {
            this(bone, channel, key, layout, null);
        }
    }

    public record Clipboard(List<CopiedKey> keys, double origin) {
        public Clipboard {
            keys = List.copyOf(keys);
            Keyframe.seconds(origin);
        }
    }

    public static Clipboard copy(DocumentSnapshot d, EntityId clip, Set<EntityId> ids) {
        var copied = new ArrayList<CopiedKey>();
        for (var t : d.animation(clip).timelines())
            for (var k : t.keys())
                if (ids.contains(k.id()))
                    copied.add(new CopiedKey(t.boneId(), t.channel(), k));
        for (var t : d.animation(clip).deforms())
            for (var k : t.keys())
                if (ids.contains(k.id()))
                    copied.add(new CopiedKey(t.attachmentId(), null, k,
                            DeformEdits.layout(MeshEdits.mesh(d, t.attachmentId()))));
        for (var t : d.animation(clip).constraintTracks())
            for (var k : t.keys())
                if (ids.contains(k.id()))
                    copied.add(new CopiedKey(t.constraintId(), null, k, "", t.channel()));
        if (copied.size() != ids.size() || copied.isEmpty())
            throw new IllegalArgumentException(m("animationedits.0005"));
        return new Clipboard(copied, copied.stream().mapToDouble(c -> c.key().time()).min().orElseThrow());
    }

    public static DocumentSnapshot paste(DocumentSnapshot d, EntityId clip, Clipboard clipboard, double time) {
        Objects.requireNonNull(clipboard, m("animationedits.0006"));
        Keyframe.seconds(time);
        return clip(d, clip, a -> {
            var tracks = new ArrayList<>(a.timelines());
            var deforms = new ArrayList<>(a.deforms());
            var constraints = new ArrayList<>(a.constraintTracks());
            for (var c : clipboard.keys()) {
                if (c.constraintChannel() != null) {
                    var channel = c.constraintChannel();
                    channel.apply(d.constraint(c.bone()), c.key().values());
                    var old = constraints.stream()
                            .filter(t -> t.constraintId().equals(c.bone()) && t.channel() == channel).findFirst()
                            .orElse(null);
                    var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
                    double target = Keyframe.seconds(time + c.key().time() - clipboard.origin());
                    if (keys.stream().anyMatch(k -> k.time() == target))
                        throw new IllegalArgumentException(m("animationedits.0007"));
                    keys.add(new Keyframe(EntityId.create(), target, c.key().values(), c.key().curves()));
                    keys.sort(Comparator.comparingDouble(Keyframe::time));
                    var next = new ConstraintTimeline(c.bone(), channel, keys);
                    if (old == null)
                        constraints.add(next);
                    else
                        constraints.set(constraints.indexOf(old), next);
                    continue;
                }
                if (c.channel() == null) {
                    var m = MeshEdits.mesh(d, c.bone());
                    if (!DeformEdits.layout(m).equals(c.layout()))
                        throw new IllegalArgumentException(m("animationedits.0008"));
                    var old = deforms.stream().filter(t -> t.attachmentId().equals(c.bone())).findFirst().orElse(null);
                    var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
                    double target = Keyframe.seconds(time + c.key().time() - clipboard.origin());
                    if (keys.stream().anyMatch(k -> k.time() == target))
                        throw new IllegalArgumentException(m("animationedits.0009"));
                    keys.add(new Keyframe(EntityId.create(), target, c.key().values(), c.key().curves()));
                    keys.sort(Comparator.comparingDouble(Keyframe::time));
                    var next = new DeformTimeline(c.bone(), keys);
                    if (old == null)
                        deforms.add(next);
                    else
                        deforms.set(deforms.indexOf(old), next);
                    continue;
                }
                if (d.bones().stream().noneMatch(b -> b.id().equals(c.bone())))
                    throw new IllegalArgumentException(m("animationedits.0010"));
                var old = tracks.stream().filter(t -> t.boneId().equals(c.bone()) && t.channel() == c.channel())
                        .findFirst().orElse(null);
                var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
                double target = Keyframe.seconds(time + (c.key().time() - clipboard.origin()));
                if (keys.stream().anyMatch(k -> k.time() == target))
                    throw new IllegalArgumentException(m("animationedits.0011"));
                keys.add(new Keyframe(EntityId.create(), target, c.key().values(), c.key().curves()));
                keys.sort(Comparator.comparingDouble(Keyframe::time));
                var next = new BoneTimeline(c.bone(), c.channel(), keys);
                if (old == null)
                    tracks.add(next);
                else
                    tracks.set(tracks.indexOf(old), next);
            }
            return new AnimationClip(a.id(), a.name(), tracks, deforms, constraints, a.discrete());
        });
    }

    /**
     * An ephemeral immutable view for existing TRS math only. Never published to
     * history.
     */
    public static DocumentSnapshot posed(DocumentSnapshot d, EntityId clip, double time) {
        var pose = AnimationSampler.pose(d, clip, time, false);
        return new DocumentSnapshot(
                d.bones().stream().map(b -> b.withLocal(pose.getOrDefault(b.id(), b.local()))).toList(),
                d.resolvedSlots(clip, time, false), d.attachments(), List.of(),
                AnimationSampler.constraints(d, clip, time, false), d.resources());
    }

    public static DocumentSnapshot transform(DocumentSnapshot d, EntityId clip, double time, EntityId selected,
            UnaryOperator<DocumentSnapshot> operation, Set<Channel> channels) {
        EntityId bone = EditorEdits.transformId(d, selected);
        d.bone(bone);
        var view = posed(d, clip, time);
        var edited = operation.apply(view);
        return record(d, clip, bone, time, edited.bone(bone).local(), channels);
    }
}
