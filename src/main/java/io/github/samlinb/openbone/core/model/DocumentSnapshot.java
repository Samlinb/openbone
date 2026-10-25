package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Validated immutable setup data. Empty document and multiple independent roots
 * are supported.
 */
public record DocumentSnapshot(List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
        List<AnimationClip> animations, List<Constraint> constraints, Resources resources) {
    public DocumentSnapshot(List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
            List<AnimationClip> animations, List<Constraint> constraints) {
        this(bones, slots, attachments, animations, constraints, Resources.EMPTY);
    }

    public DocumentSnapshot(List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
            List<AnimationClip> animations) {
        this(bones, slots, attachments, animations, List.of());
    }

    /**
     * Schema-1 migration and existing Setup callers receive one empty default clip.
     */
    public DocumentSnapshot(List<Bone> bones, List<Slot> slots, List<Attachment> attachments) {
        this(bones, slots, attachments, migratedAnimation(bones, slots, attachments));
    }

    private static List<AnimationClip> migratedAnimation(List<Bone> bones, List<Slot> slots,
            List<Attachment> attachments) {
        Set<EntityId> ids = new HashSet<>();
        bones.forEach(b -> ids.add(b.id()));
        slots.forEach(s -> ids.add(s.id()));
        attachments.forEach(a -> ids.add(a.id()));
        EntityId clip = AnimationClip.DEFAULT_ID;
        int n = 0;
        while (ids.contains(clip))
            clip = new EntityId(java.util.UUID
                    .nameUUIDFromBytes(
                            ("schema1-default-animation-" + n++).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString());
        return List.of(new AnimationClip(clip, m("extendededits.0001"), List.of()));
    }

    public static final DocumentSnapshot EMPTY = new DocumentSnapshot(List.of(), List.of(), List.of());

    public DocumentSnapshot {
        Objects.requireNonNull(resources);
        animations = List.copyOf(animations);
        constraints = List.copyOf(constraints);
        bones = List.copyOf(bones);
        slots = List.copyOf(slots);
        attachments = List.copyOf(attachments);
        Set<EntityId> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Map<EntityId, Bone> byBone = new LinkedHashMap<>();
        for (Bone bone : bones) {
            unique(ids, bone.id(), "ID");
            unique(names, bone.name(), "bone name");
            byBone.put(bone.id(), bone);
        }
        // Iterative parent-first traversal avoids stack overflow on deep imported
        // hierarchies.
        Map<EntityId, List<Bone>> children = new HashMap<>();
        ArrayDeque<Bone> ready = new ArrayDeque<>();
        for (Bone bone : bones) {
            if (bone.parentId() == null)
                ready.add(bone);
            else {
                if (!byBone.containsKey(bone.parentId()))
                    throw new IllegalArgumentException("Missing parent for " + bone.name());
                if (bone.id().equals(bone.parentId()))
                    throw new IllegalArgumentException("Bone cannot parent itself: " + bone.name());
                children.computeIfAbsent(bone.parentId(), ignored -> new ArrayList<>()).add(bone);
            }
        }
        List<Bone> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            Bone bone = ready.remove();
            ordered.add(bone);
            ready.addAll(children.getOrDefault(bone.id(), List.of()));
        }
        if (ordered.size() != bones.size())
            throw new IllegalArgumentException("Bone parent cycle");
        bones = List.copyOf(ordered);
        Map<EntityId, Slot> bySlot = new HashMap<>();
        names.clear();
        for (Slot slot : slots) {
            unique(ids, slot.id(), "ID");
            unique(names, slot.name(), "slot name");
            if (!byBone.containsKey(slot.boneId()))
                throw new IllegalArgumentException("Missing slot bone: " + slot.name());
            bySlot.put(slot.id(), slot);
        }
        Map<EntityId, Attachment> byAttachment = new HashMap<>();
        Map<EntityId, Set<String>> attachmentNames = new HashMap<>();
        for (Attachment attachment : attachments) {
            unique(ids, attachment.id(), "ID");
            if (!bySlot.containsKey(attachment.slotId()))
                throw new IllegalArgumentException("Missing attachment slot: " + attachment.name());
            if (resources.skins().isEmpty())
                unique(attachmentNames.computeIfAbsent(attachment.slotId(), ignored -> new HashSet<>()),
                        attachment.name(), "attachment name within slot");
            byAttachment.put(attachment.id(), attachment);
            if (attachment instanceof MeshAttachment m)
                for (var influences : m.weights())
                    for (var i : influences)
                        if (!byBone.containsKey(i.boneId()))
                            throw new IllegalArgumentException(m("documentsnapshot.0001") + i.boneId());
        }
        for (Slot slot : slots)
            if (slot.activeAttachmentId() != null) {
                Attachment active = byAttachment.get(slot.activeAttachmentId());
                if (active == null || !active.slotId().equals(slot.id()))
                    throw new IllegalArgumentException("Invalid active attachment for slot: " + slot.name());
            }
        for (var a : attachments)
            if (a instanceof ClippingAttachment c && c.endSlotId() != null) {
                var end = bySlot.get(c.endSlotId());
                if (end == null)
                    throw new IllegalArgumentException(m("documentsnapshot.0002"));
                if (slots.indexOf(end) <= slots.indexOf(bySlot.get(c.slotId())))
                    throw new IllegalArgumentException(m("documentsnapshot.0003"));
                if (byAttachment.get(end.activeAttachmentId()) instanceof ClippingAttachment)
                    throw new IllegalArgumentException(m("documentsnapshot.0004"));
            }
        Set<String> skinNames = new HashSet<>();
        Set<EntityId> bound = new HashSet<>();
        for (var skin : resources.skins()) {
            unique(skinNames, skin.name(), "skin name");
            for (var row : skin.attachments().entrySet()) {
                if (!bySlot.containsKey(row.getKey()))
                    throw new IllegalArgumentException(m("documentsnapshot.0005"));
                for (var entry : row.getValue().entrySet()) {
                    var a = byAttachment.get(entry.getValue());
                    if (a == null || !a.slotId().equals(row.getKey()) || !a.name().equals(entry.getKey()))
                        throw new IllegalArgumentException(m("documentsnapshot.0006"));
                    if (!bound.add(a.id()))
                        throw new IllegalArgumentException(m("documentsnapshot.0007"));
                }
            }
        }
        if (!resources.skins().isEmpty() && (!skinNames.contains("default")
                || !skinNames.contains(resources.activeSkin()) || bound.size() != attachments.size()))
            throw new IllegalArgumentException(m("documentsnapshot.0008"));
        if (resources.skins().isEmpty() && !resources.activeSkin().equals("default"))
            throw new IllegalArgumentException(m("documentsnapshot.0009"));
        Set<EntityId> eventIds = new HashSet<>();
        Set<String> eventNames = new HashSet<>();
        for (var event : resources.events()) {
            unique(ids, event.id(), "ID");
            unique(eventIds, event.id(), "event ID");
            unique(eventNames, event.name(), "event name");
        }
        for (var entry : resources.atlas().entrySet()) {
            var a = byAttachment.get(entry.getKey());
            if (a == null || a.source() == null)
                throw new IllegalArgumentException(m("documentsnapshot.0010"));
        }
        Set<EntityId> slotIds = bySlot.keySet();
        for (var clip : animations) {
            for (var key : clip.discrete().keys())
                unique(ids, key.id(), "ID");
            for (var k : clip.discrete().attachments()) {
                if (!slotIds.contains(k.slotId()))
                    throw new IllegalArgumentException(m("documentsnapshot.0011"));
                if (k.name() != null && attachments.stream()
                        .noneMatch(a -> a.slotId().equals(k.slotId()) && a.name().equals(k.name())))
                    throw new IllegalArgumentException(m("documentsnapshot.0012"));
            }
            for (var k : clip.discrete().drawOrder())
                if (k.slots().size() != slots.size() || !new HashSet<>(k.slots()).equals(slotIds))
                    throw new IllegalArgumentException(m("documentsnapshot.0013"));
            for (var k : clip.discrete().events())
                if (!eventIds.contains(k.eventId()))
                    throw new IllegalArgumentException(m("documentsnapshot.0014"));
            for (var t : clip.discrete().colors()) if (!slotIds.contains(t.slotId()))
                throw new IllegalArgumentException("Unknown color timeline slot");
        }
        Set<String> constraintNames = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (var c : constraints) {
            unique(ids, c.id(), "ID");
            unique(constraintNames, c.name(), "constraint name");
            unique(orders, c.order(), "constraint order");
            EntityId target = c.targetBone(slots);
            if (!byBone.containsKey(target))
                throw new IllegalArgumentException(m("documentsnapshot.0015"));
            for (var id : c.boneIds()) {
                if (!byBone.containsKey(id))
                    throw new IllegalArgumentException(m("documentsnapshot.0016"));
                if (!(c instanceof PhysicsConstraint) && descends(byBone, target, id))
                    throw new IllegalArgumentException(m("documentsnapshot.0017"));
            }
            if (c instanceof IkConstraint ik && ik.boneIds().size() == 2
                    && !Objects.equals(byBone.get(ik.boneIds().get(1)).parentId(), ik.boneIds().get(0)))
                throw new IllegalArgumentException(m("documentsnapshot.0018"));
            if (c instanceof PhysicsConstraint && byBone.get(c.boneIds().get(0)).length() > 1e6)
                throw new IllegalArgumentException(m("documentsnapshot.0019"));
            if (c instanceof TransformConstraint t && t.local())
                for (int i = 0; i < c.boneIds().size(); i++)
                    for (int j = i + 1; j < c.boneIds().size(); j++)
                        if (descends(byBone, c.boneIds().get(i), c.boneIds().get(j)))
                            throw new IllegalArgumentException(m("documentsnapshot.0020"));
        }
        constraints = constraints.stream().sorted(Comparator.comparingInt(Constraint::order)).toList();
        Set<EntityId> writers = new HashSet<>();
        for (var c : constraints)
            for (var id : c.boneIds())
                if (!writers.add(id))
                    throw new IllegalArgumentException(m("documentsnapshot.0021"));
        for (var reader : constraints)
            for (var writer : constraints)
                if (reader != writer && writer.order() > reader.order())
                    for (var id : writer.boneIds())
                        if (descends(byBone, reader.targetBone(slots), id) || reader.boneIds().stream()
                                .anyMatch(prior -> !prior.equals(id) && descends(byBone, prior, id)))
                            throw new IllegalArgumentException(m("documentsnapshot.0022"));
        for (var reader : constraints)
            if (reader instanceof TransformConstraint t && t.local())
                for (var writer : constraints)
                    if ((writer instanceof TransformConstraint w && !w.local() || writer instanceof PathConstraint
                            || writer instanceof PhysicsConstraint) && writer.boneIds().contains(reader.targetId()))
                        throw new IllegalArgumentException(m("documentsnapshot.0023"));
        Set<String> animationNames = new HashSet<>();
        for (var clip : animations) {
            unique(ids, clip.id(), "ID");
            unique(animationNames, clip.name(), "animation name");
            if (io.github.samlinb.openbone.core.math.PhysicsTraceProof.potential(constraints, clip)
                    && clip.duration() > io.github.samlinb.openbone.core.math.PhysicsEnvelope.MAX_SECONDS)
                throw new IllegalArgumentException(m("documentsnapshot.0024"));
            for (var track : clip.timelines()) {
                if (!byBone.containsKey(track.boneId()))
                    throw new IllegalArgumentException(m("documentsnapshot.0025"));
                for (var key : track.keys())
                    unique(ids, key.id(), "ID");
            }
            for (var track : clip.deforms()) {
                var a = byAttachment.get(track.attachmentId());
                if (!(a instanceof MeshAttachment m))
                    throw new IllegalArgumentException(m("documentsnapshot.0026"));
                for (var key : track.keys()) {
                    unique(ids, key.id(), "ID");
                    if (key.values().size() != m.deformSize())
                        throw new IllegalArgumentException(m("documentsnapshot.0027"));
                }
            }
            for (var track : clip.constraintTracks()) {
                var setup = constraints.stream().filter(c -> c.id().equals(track.constraintId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(m("documentsnapshot.0028")));
                track.validate(setup);
                for (var key : track.keys())
                    unique(ids, key.id(), "ID");
            }
            for (var c : constraints)
                if (c instanceof PhysicsConstraint p)
                    io.github.samlinb.openbone.core.math.PhysicsTraceProof.validateParameters(p, clip);
        }
        // Finite local data can still overflow when composed. Reject before entering
        // editable state.
        WorldPose.evaluate(bones, slots, attachments, Map.of(), Map.of(), constraints);
        validateAnimationBounds(bones, slots, attachments,
                animations.isEmpty() ? migratedAnimation(bones, slots, attachments) : animations, constraints);
    }

    /**
     * A cubic stays inside its four control values. Conservative Euclidean norm
     * bounds
     * certify every seek (including overshoot) before an animation enters the
     * document.
     * Rotation is unwrapped but its matrix norm is 1; no exponential factor for
     * unit chains.
     */
    private static boolean descends(Map<EntityId, Bone> bones, EntityId id, EntityId ancestor) {
        while (id != null) {
            if (id.equals(ancestor))
                return true;
            id = bones.get(id).parentId();
        }
        return false;
    }

    private static void validateAnimationBounds(List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
            List<AnimationClip> animations, List<Constraint> constraints) {
        long physicsWork = 0;
        for (var clip : animations) {
            if (clip.timelines().isEmpty() && clip.deforms().isEmpty() && constraints.isEmpty())
                continue;
            Map<EntityId, double[]> limits = new HashMap<>();
            for (var b : bones) {
                var s = b.local();
                limits.put(b.id(), new double[] { Math.abs(s.x()), Math.abs(s.y()), Math.abs(s.rotation()),
                        Math.abs(s.scaleX()), Math.abs(s.scaleY()) });
            }
            Map<EntityId, Bone> byBone = new HashMap<>();
            bones.forEach(b -> byBone.put(b.id(), b));
            for (var track : clip.timelines()) {
                var s = byBone.get(track.boneId()).local();
                var bound = limits.get(track.boneId());
                int offset = switch (track.channel()) {
                    case TRANSLATE -> 0;
                    case ROTATE -> 2;
                    case SCALE -> 3;
                };
                double[] setup = { s.x(), s.y(), s.rotation(), s.scaleX(), s.scaleY() };
                for (int n = 0; n < track.keys().size(); n++)
                    for (int c = 0; c < track.channel().components(); c++) {
                        var key = track.keys().get(n);
                        double v = key.values().get(c);
                        List<Double> values = new ArrayList<>();
                        values.add(v);
                        if (n + 1 < track.keys().size() && key.curves().get(c).kind() == Curve.Kind.BEZIER) {
                            var curve = key.curves().get(c);
                            double next = track.keys().get(n + 1).values().get(c);
                            values.add(io.github.samlinb.openbone.core.math.Finite.require(v + (next - v) * curve.y1(),
                                    "animation control value"));
                            values.add(io.github.samlinb.openbone.core.math.Finite.require(v + (next - v) * curve.y2(),
                                    "animation control value"));
                        }
                        for (double value : values) {
                            double absolute = track.channel() == Channel.SCALE ? setup[offset + c] * value
                                    : setup[offset + c] + value;
                            bound[offset + c] = Math.max(bound[offset + c], Math.abs(
                                    io.github.samlinb.openbone.core.math.Finite.require(absolute, "animation pose")));
                        }
                    }
            }
            Map<EntityId, double[]> world = new HashMap<>();
            Map<EntityId, double[]> inputLimits = new HashMap<>();
            limits.forEach((id, values) -> inputLimits.put(id, values.clone()));
            Map<EntityId, double[]> worldOverrides = new HashMap<>();
            Set<EntityId> shearedLocal = new HashSet<>();
            var physicsProof = io.github.samlinb.openbone.core.math.PhysicsTraceProof.trace(bones, slots, attachments,
                    constraints, clip, io.github.samlinb.openbone.core.math.PhysicsTraceProof.MAX_WORK - physicsWork);
            physicsWork += physicsProof.work();
            // Each solved IK may rotate a parent, so rebuild descendant envelopes after
            // every explicit order. Stretch is bounded by target distance / bone length.
            // This covers all animation control hulls, including unsampled times.
            List<Constraint> stages = new ArrayList<>();
            stages.add(null);
            stages.addAll(constraints.stream().map(c -> ConstraintTimeline.envelope(clip, c)).toList());
            for (var stage : stages) {
                if (stage instanceof IkConstraint ik && ik.enabled() && ik.mix() != 0) {
                    var b = byBone.get(ik.boneIds().get(0));
                    var v = limits.get(b.id());
                    var p = b.parentId() == null ? new double[] { 1, 0 } : world.get(b.parentId());
                    var target = world.get(ik.targetId());
                    var f = m("documentsnapshot.0029");
                    double distance = io.github.samlinb.openbone.core.math.Finite
                            .require(2 * p[0] * (target[1] + p[1]) / 0.0001 + Math.hypot(v[0], v[1]), f);
                    io.github.samlinb.openbone.core.math.Finite.require(2 * p[0] * p[0], f);
                    if (ik.stretch() && b.length() > 0) {
                        double upper = io.github.samlinb.openbone.core.math.Finite.require(distance / b.length(), f);
                        double factor = v[3] == 0 ? 1
                                : io.github.samlinb.openbone.core.math.Finite.require(1 + distance / 0.0001, f);
                        v[3] = Math.max(v[3], upper);
                        if (ik.uniform())
                            v[4] = io.github.samlinb.openbone.core.math.Finite.require(v[4] * factor, f);
                    }
                    if (ik.boneIds().size() == 2) {
                        var child = byBone.get(ik.boneIds().get(1));
                        var childLimits = limits.get(child.id());
                        // Polynomial/softness operations have degree up to eight. Limit the
                        // new IK2 solver's independent double domain conservatively; this is
                        // separate from the pre-existing unrestricted TRS project domain.
                        double extent = Math.max(1,
                                Math.max(distance, Math.max(ik.softness(), Math.max(b.length(), child.length()))));
                        for (double a : v)
                            extent = Math.max(extent, a);
                        for (double a : childLimits)
                            extent = Math.max(extent, a);
                        if (extent > 1e12)
                            throw new IllegalArgumentException(m("documentsnapshot.0030"));
                        for (var id : ik.boneIds()) {
                            var scale = inputLimits.get(id);
                            if (scale[3] > 1e6 || scale[4] > 1e6)
                                throw new IllegalArgumentException(m("documentsnapshot.0031"));
                        }
                        if (child.length() != 0 && (child.length() < 1e-6 || child.length() > 1e6))
                            throw new IllegalArgumentException(m("documentsnapshot.0032"));
                        // Uniform stretch uses the first segment l1>=1e-4; otherwise its
                        // explicit single-bone fallback has the same signed reach gate.
                        if (ik.stretch()) {
                            double factor = io.github.samlinb.openbone.core.math.Finite.require(1 + distance / 0.0001,
                                    f);
                            v[3] = io.github.samlinb.openbone.core.math.Finite.require(v[3] * factor, f);
                            if (ik.uniform())
                                v[4] = io.github.samlinb.openbone.core.math.Finite.require(v[4] * factor, f);
                        }
                    }
                }
                if (stage instanceof TransformConstraint tc && !tc.inactive()) {
                    var target = limits.get(tc.targetId());
                    var targetWorld = world.get(tc.targetId());
                    if (tc.local())
                        for (var id : tc.boneIds()) {
                            var v = limits.get(id);
                            for (int component : new int[] { 0, 1, 2 })
                                v[component] = io.github.samlinb.openbone.core.math.Finite.require(
                                        v[component] + target[component]
                                                + Math.abs(tc.offset().get(component == 2 ? 0 : component + 1)),
                                        "local transform animation range");
                            for (int n = 3; n <= 4; n++)
                                if (tc.mix().get(n) != 0) {
                                    double off = Math.abs(tc.offset().get(n));
                                    v[n] = io.github.samlinb.openbone.core.math.Finite.require(
                                            tc.relative() ? v[n] * (2 + target[n] + off)
                                                    : (2 * v[n] + target[n] + off) / 1e-12,
                                            "local constrained scale range");
                                }
                            shearedLocal.add(id);
                        }
                    else {
                        io.github.samlinb.openbone.core.math.Finite.require(2 * targetWorld[0] * targetWorld[0],
                                "transform target determinant range");
                        double targetPosition = positionNormBound(targetWorld[0], targetWorld[1], tc.offset().get(1),
                                tc.offset().get(2), "transform offset position range");
                        for (var id : tc.boneIds()) {
                            var old = world.get(id);
                            double scaleOffset = Math.max(Math.abs(tc.offset().get(3)), Math.abs(tc.offset().get(4)));
                            double norm = io.github.samlinb.openbone.core.math.Finite.require(
                                    tc.relative() ? 2 * old[0] * (2 + targetWorld[0] + scaleOffset)
                                            : 2 * (old[0] + targetWorld[0] + scaleOffset),
                                    "constrained world matrix range");
                            double position = io.github.samlinb.openbone.core.math.Finite
                                    .require(old[1] + targetPosition, "constrained world position range");
                            // Scale factors divide by a nondegenerate column length before
                            // multiplication; explicitly certify that intermediate as well.
                            io.github.samlinb.openbone.core.math.Finite.require(
                                    (2 * old[0] + targetWorld[0] + scaleOffset) / 1e-12, "world scale factor range");
                            worldOverrides.put(id, new double[] { norm, position });
                        }
                    }
                }
                if (stage instanceof PathConstraint pc && !pc.inactive()) {
                    var targetSlot = slots.stream().filter(s -> s.id().equals(pc.targetId())).findFirst().orElseThrow();
                    var path = attachments.stream().filter(a -> a.id().equals(targetSlot.activeAttachmentId()))
                            .findFirst().orElse(null);
                    if (path instanceof PathAttachment p) {
                        var target = world.get(targetSlot.boneId());
                        worldOverrides.putAll(io.github.samlinb.openbone.core.math.PathConstraintSolver.bounds(pc, p,
                                target[0], target[1], world, bones));
                    }
                }
                if (stage instanceof PhysicsConstraint pc && pc.mix() != 0) {
                    var old = world.get(pc.boneId());
                    var stateBound = physicsProof.states().get(pc.id());
                    var bound = stateBound == null
                            ? io.github.samlinb.openbone.core.math.PhysicsEnvelope.whole(pc, old[0], old[1],
                                    byBone.get(pc.boneId()).length())
                            : io.github.samlinb.openbone.core.math.PhysicsEnvelope.fromTrace(pc, old[0], old[1],
                                    stateBound);
                    worldOverrides.put(pc.boneId(), new double[] { bound.matrixNorm(), bound.positionNorm() });
                }
                for (var b : bones) {
                    var v = limits.get(b.id());
                    var p = b.parentId() == null ? new double[] { 1, 0 } : world.get(b.parentId());
                    if (worldOverrides.containsKey(b.id())) {
                        world.put(b.id(), worldOverrides.get(b.id()));
                        continue;
                    }
                    double norm = linearNormBound(p[0],
                            Math.max(v[3], v[4]) * (shearedLocal.contains(b.id()) ? Math.sqrt(2) : 1),
                            "animation world scale bound");
                    double position = positionNormBound(p[0], p[1], v[0], v[1], "animation world position bound");
                    io.github.samlinb.openbone.core.math.Finite.require(position + norm * b.length(),
                            "animation bone tip bound");
                    world.put(b.id(), new double[] { norm, position });
                }
            }
            Map<EntityId, Slot> bySlot = new HashMap<>();
            slots.forEach(s -> bySlot.put(s.id(), s));
            for (var a : attachments) {
                if (a instanceof PathAttachment path) {
                    var p = world.get(bySlot.get(path.slotId()).boneId());
                    for (var v : path.vertices())
                        positionNormBound(p[0], p[1], v.x(), v.y(), "animation path control bound");
                    continue;
                }
                if (a instanceof ClippingAttachment c) {
                    var p = world.get(bySlot.get(c.slotId()).boneId());
                    for (var v : c.vertices())
                        positionNormBound(p[0], p[1], v.x(), v.y(), "animation clip vertex bound");
                    continue;
                }
                if (a instanceof MeshAttachment m) {
                    double[] offsets = DeformTimeline.bounds(clip, m.id(), m.deformSize());
                    int index = 0;
                    if (m.weighted()) {
                        for (var influences : m.weights()) {
                            double sum = 0;
                            for (var i : influences) {
                                var w = world.get(i.boneId());
                                double x = io.github.samlinb.openbone.core.math.Finite
                                        .require(Math.abs(i.point().x()) + offsets[index++], "skin setup+deform x"),
                                        y = io.github.samlinb.openbone.core.math.Finite.require(
                                                Math.abs(i.point().y()) + offsets[index++], "skin setup+deform y");
                                double bound = positionNormBound(w[0], w[1], x, y,
                                        "animation influence intermediate bound");
                                sum = io.github.samlinb.openbone.core.math.Finite.require(sum + bound * i.weight(),
                                        "animation skin sum bound");
                            }
                        }
                        continue;
                    }
                    var p = world.get(bySlot.get(m.slotId()).boneId());
                    for (var v : m.vertices())
                        io.github.samlinb.openbone.core.math.Finite.require(positionNormBound(p[0], p[1],
                                io.github.samlinb.openbone.core.math.Finite.require(Math.abs(v.x()) + offsets[index++],
                                        "mesh setup+deform x"),
                                io.github.samlinb.openbone.core.math.Finite.require(Math.abs(v.y()) + offsets[index++],
                                        "mesh setup+deform y"),
                                "animation mesh vertex bound"), "animation mesh bound");
                    continue;
                }
                var r = (RegionAttachment) a;
                var p = world.get(bySlot.get(r.slotId()).boneId());
                var t = r.local();
                // WorldPose publishes the complete attachment matrix even for tiny/zero-area
                // geometry and inactive attachments. Dimensions cannot certify that matrix:
                // 1e200 bone scale * 1e200 attachment scale overflows despite 1e-200 size.
                double linear = linearNormBound(p[0], Math.max(Math.abs(t.scaleX()), Math.abs(t.scaleY())),
                        "animation region matrix bound");
                double position = positionNormBound(p[0], p[1], t.x(), t.y(), "animation region translation bound");
                double radius = io.github.samlinb.openbone.core.math.Finite.require(
                        linear * upperHypot(r.width() / 2, r.height() / 2), "animation region vertex radius bound");
                io.github.samlinb.openbone.core.math.Finite.require(position + radius, "animation region bound");
            }
        }
    }

    private static double linearNormBound(double parent, double localScale, String field) {
        if (parent == 0 || localScale == 0)
            return 0;
        // Relative padding alone disappears for subnormals: e.g. a min-value scale at
        // 45 degrees rounds BOTH sin*scale and cos*scale to MIN_VALUE. That local
        // matrix
        // error can later be amplified by a large parent, so add its absolute rounding
        // allowance BEFORE composition, then cover the 2x2 dot products separately.
        double product = io.github.samlinb.openbone.core.math.Finite.require(parent * localScale, field);
        double bound = (product * (1 + 1e-14) + parent * (4 * Double.MIN_VALUE)) * (1 + 1e-14) + 8 * Double.MIN_VALUE;
        return io.github.samlinb.openbone.core.math.Finite.require(Math.nextUp(bound), field);
    }

    private static double upperHypot(double x, double y) {
        return x == 0 && y == 0 ? 0 : Math.nextUp(Math.hypot(x, y));
    }

    private static double positionNormBound(double parent, double position, double x, double y, String field) {
        if (position == 0 && (parent == 0 || x == 0 && y == 0))
            return 0;
        double offset = parent == 0 ? 0 : parent * upperHypot(x, y);
        return io.github.samlinb.openbone.core.math.Finite
                .require(Math.nextUp((position + offset) * (1 + 1e-14) + 8 * Double.MIN_VALUE), field);
    }

    private static <T> void unique(Set<T> seen, T value, String kind) {
        if (!seen.add(value))
            throw new IllegalArgumentException("Duplicate " + kind + ": " + value);
    }

    public AnimationClip animation(EntityId id) {
        return animations.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown animation: " + id));
    }

    public DocumentSnapshot withAnimations(List<AnimationClip> value) {
        return new DocumentSnapshot(bones, slots, attachments, value, constraints, resources);
    }

    public DocumentSnapshot withConstraints(List<Constraint> value) {
        return new DocumentSnapshot(bones, slots, attachments, animations, value, resources);
    }

    public Constraint constraint(EntityId id) {
        return constraints.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown constraint: " + id));
    }

    public Bone bone(EntityId id) {
        return bones.stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown bone: " + id));
    }

    public boolean contains(EntityId id) {
        return bones.stream().anyMatch(b -> b.id().equals(id)) || slots.stream().anyMatch(s -> s.id().equals(id))
                || attachments.stream().anyMatch(a -> a.id().equals(id))
                || constraints.stream().anyMatch(c -> c.id().equals(id));
    }

    public DocumentSnapshot withResources(Resources value) {
        return new DocumentSnapshot(bones, slots, attachments, animations, constraints, value);
    }

    public Attachment attachment(EntityId id) {
        return attachments.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(m("documentsnapshot.0033")));
    }

    public List<Skin> skins() {
        if (!resources.skins().isEmpty())
            return resources.skins();
        var rows = new LinkedHashMap<EntityId, Map<String, EntityId>>();
        for (var a : attachments)
            rows.computeIfAbsent(a.slotId(), id -> new LinkedHashMap<>()).put(a.name(), a.id());
        return List.of(new Skin("default", rows));
    }

    public EntityId resolveAttachment(EntityId slot, String name) {
        if (name == null)
            return null;
        var skins = skins();
        var current = skins.stream().filter(s -> s.name().equals(resources.activeSkin())).findFirst().orElseThrow();
        var id = current.find(slot, name);
        return id != null ? id
                : skins.stream().filter(s -> s.name().equals("default")).findFirst().orElseThrow().find(slot, name);
    }

    public List<Slot> resolvedSlots(EntityId clip, double time, boolean loop) {
        Map<EntityId, Attachment> attachmentsById = new HashMap<>();
        for (var a : attachments)
            attachmentsById.put(a.id(), a);
        Map<EntityId, String> names = new HashMap<>();
        for (var s : slots)
            names.put(s.id(),
                    s.activeAttachmentId() == null ? null : attachmentsById.get(s.activeAttachmentId()).name());
        List<EntityId> order = slots.stream().map(Slot::id).toList();
        if (clip != null) {
            var a = animation(clip);
            double phase = AnimationSampler.sampleTime(time, a.duration(), loop);
            for (var k : a.discrete().attachments())
                if (k.time() <= phase)
                    names.put(k.slotId(), k.name());
            for (var k : a.discrete().drawOrder())
                if (k.time() <= phase)
                    order = k.slots();
        }
        var skins = skins();
        var current = skins.stream().filter(s -> s.name().equals(resources.activeSkin())).findFirst().orElseThrow();
        var fallback = skins.stream().filter(s -> s.name().equals("default")).findFirst().orElseThrow();
        Map<EntityId, Slot> byId = new HashMap<>();
        Map<EntityId, Rgba> colors = new HashMap<>();
        for (var s : slots) colors.put(s.id(), s.color());
        if (clip != null) {
            var animation = animation(clip);
            double phase = AnimationSampler.sampleTime(time, animation.duration(), loop);
            for (var t : animation.discrete().colors()) colors.put(t.slotId(), t.sample(colors.get(t.slotId()), phase));
        }
        for (var s : slots) {
            String name = names.get(s.id());
            EntityId id = name == null ? null : current.find(s.id(), name);
            if (id == null && name != null)
                id = fallback.find(s.id(), name);
            byId.put(s.id(), s.withActiveAttachment(id).withColor(colors.get(s.id())));
        }
        return order.stream().map(byId::get).toList();
    }

    private WorldPose decorate(WorldPose pose, List<Slot> evaluated) {
        var regions = new LinkedHashMap<>(pose.regions());
        for (var a : attachments)
            if (a instanceof RegionAttachment r && resources.atlas().containsKey(a.id())) {
                var t = resources.atlas().get(a.id());
                var m = regions.get(a.id()).matrix();
                double x = -r.width() / 2 + r.width() * ((double) t.offsetX() / t.originalWidth()),
                        y = -r.height() / 2 + r.height() * ((double) t.offsetY() / t.originalHeight()),
                        w = r.width() * ((double) t.width() / t.originalWidth()),
                        h = r.height() * ((double) t.height() / t.originalHeight());
                regions.put(a.id(), new WorldPose.RegionWorld(m,
                        List.of(m.apply(x, y), m.apply(x, y + h), m.apply(x + w, y + h), m.apply(x + w, y))));
            }
        return new WorldPose(pose.bones(), regions, pose.meshes(), pose.clips(), pose.paths(), evaluated);
    }

    public WorldPose evaluateWorld() {
        var evaluated = resolvedSlots(null, 0, false);
        return decorate(WorldPose.evaluate(bones, evaluated, attachments, Map.of(), Map.of(), constraints), evaluated);
    }

    /** Pose overrides remain separate from setup; unknown bone IDs are rejected. */
    public WorldPose evaluateWorld(Map<EntityId, io.github.samlinb.openbone.core.math.Transform> pose) {
        var evaluated = resolvedSlots(null, 0, false);
        return decorate(WorldPose.evaluate(bones, evaluated, attachments, Map.copyOf(pose), Map.of(), constraints),
                evaluated);
    }

    public WorldPose evaluateWorld(Map<EntityId, io.github.samlinb.openbone.core.math.Transform> pose,
            Map<EntityId, List<Double>> deforms) {
        var evaluated = resolvedSlots(null, 0, false);
        return decorate(
                WorldPose.evaluate(bones, evaluated, attachments, Map.copyOf(pose), Map.copyOf(deforms), constraints),
                evaluated);
    }

    public WorldPose evaluateAnimatedWorld(EntityId clip, double time, boolean loop) {
        var evaluated = resolvedSlots(clip, time, loop);
        return decorate(WorldPose.evaluate(bones, evaluated, attachments, AnimationSampler.pose(this, clip, time, loop),
                AnimationSampler.deforms(this, clip, time, loop), AnimationSampler.constraints(this, clip, time, loop)),
                evaluated);
    }

    public WorldPose evaluateAnimatedPhysicsPose(EntityId clip, double time,
            Map<EntityId, io.github.samlinb.openbone.core.math.PhysicsSolver.State> states) {
        var evaluated = resolvedSlots(clip, time, false);
        return decorate(
                WorldPose
                        .evaluate(bones, evaluated, attachments, AnimationSampler.pose(this, clip, time, false),
                                AnimationSampler.deforms(this, clip, time, false),
                                AnimationSampler.constraints(this, clip, time, false),
                                new io.github.samlinb.openbone.core.math.PhysicsFrame(
                                        io.github.samlinb.openbone.core.math.PhysicsSolver.Mode.POSE, states)),
                evaluated);
    }

    public WorldPose evaluatePhysicsPose(Map<EntityId, io.github.samlinb.openbone.core.math.Transform> pose,
            Map<EntityId, List<Double>> deforms,
            Map<EntityId, io.github.samlinb.openbone.core.math.PhysicsSolver.State> states) {
        var evaluated = resolvedSlots(null, 0, false);
        return decorate(
                WorldPose
                        .evaluate(bones, evaluated, attachments, Map.copyOf(pose), Map.copyOf(deforms), constraints,
                                new io.github.samlinb.openbone.core.math.PhysicsFrame(
                                        io.github.samlinb.openbone.core.math.PhysicsSolver.Mode.POSE, states)),
                evaluated);
    }
}
