package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.EntityId;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Clip IDs permit future multi-animation UI without changing bone/key
 * identities.
 */
public record AnimationClip(EntityId id, String name, List<BoneTimeline> timelines, List<DeformTimeline> deforms,
        List<ConstraintTimeline> constraintTracks, DiscreteTracks discrete) {
    public AnimationClip(EntityId id, String name, List<BoneTimeline> timelines, List<DeformTimeline> deforms,
            List<ConstraintTimeline> constraintTracks) {
        this(id, name, timelines, deforms, constraintTracks, DiscreteTracks.EMPTY);
    }

    public AnimationClip(EntityId id, String name, List<BoneTimeline> timelines, List<DeformTimeline> deforms) {
        this(id, name, timelines, deforms, List.of());
    }

    public AnimationClip(EntityId id, String name, List<BoneTimeline> timelines) {
        this(id, name, timelines, List.of());
    }

    public static final EntityId DEFAULT_ID = new EntityId("ffffffff-ffff-4fff-8fff-ffffffffffff");
    public static final AnimationClip DEFAULT = new AnimationClip(DEFAULT_ID, m("extendededits.0001"), List.of());

    public AnimationClip {
        Objects.requireNonNull(discrete);
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        timelines = List.copyOf(timelines);
        deforms = List.copyOf(deforms);
        constraintTracks = List.copyOf(constraintTracks);
        if (name.isBlank() || !name.equals(name.trim()) || name.length() > 256)
            throw new IllegalArgumentException(m("animationclip.0001"));
        Set<String> tracks = new HashSet<>();
        Set<EntityId> keys = new HashSet<>();
        for (var t : timelines) {
            if (!tracks.add(t.boneId() + ":" + t.channel().name()))
                throw new IllegalArgumentException("Duplicate timeline");
            for (var k : t.keys())
                if (!keys.add(k.id()))
                    throw new IllegalArgumentException("Duplicate key ID");
        }
        Set<EntityId> attachments = new HashSet<>();
        for (var t : deforms) {
            if (!attachments.add(t.attachmentId()))
                throw new IllegalArgumentException("Duplicate deform timeline");
            for (var k : t.keys())
                if (!keys.add(k.id()))
                    throw new IllegalArgumentException("Duplicate key ID");
        }
        for (var t : constraintTracks) {
            if (!tracks.add(t.constraintId() + ":" + t.channel().name()))
                throw new IllegalArgumentException("Duplicate constraint timeline");
            for (var k : t.keys())
                if (!keys.add(k.id()))
                    throw new IllegalArgumentException("Duplicate key ID");
        }
    }

    public List<Keyframe> keys() {
        return java.util.stream.Stream.of(timelines.stream().flatMap(t -> t.keys().stream()),
                deforms.stream().flatMap(t -> t.keys().stream()),
                constraintTracks.stream().flatMap(t -> t.keys().stream())).flatMap(s -> s).toList();
    }

    public double duration() {
        double end = discrete.duration();
        for (var t : timelines)
            end = Math.max(end, t.keys().get(t.keys().size() - 1).time());
        for (var t : deforms)
            end = Math.max(end, t.keys().get(t.keys().size() - 1).time());
        for (var t : constraintTracks)
            end = Math.max(end, t.keys().get(t.keys().size() - 1).time());
        return end;
    }
}
