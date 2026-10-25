package io.github.samlinb.openbone.core.model;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Pure operations: validation completes before the caller publishes a new
 * document.
 */
public final class ModelEdits {
    public enum ReparentPolicy {
        KEEP_LOCAL, KEEP_WORLD
    }

    private ModelEdits() {
    }

    public static DocumentSnapshot addBone(DocumentSnapshot d, Bone bone) {
        List<Bone> bones = new ArrayList<>(d.bones());
        bones.add(bone);
        return new DocumentSnapshot(bones, d.slots(), d.attachments(), d.animations(), d.constraints(), d.resources());
    }

    public static DocumentSnapshot editBone(DocumentSnapshot d, EntityId id, UnaryOperator<Bone> operation) {
        d.bone(id);
        List<Bone> bones = d.bones().stream().map(b -> b.id().equals(id) ? operation.apply(b) : b).toList();
        // A rename/transform must not replace identity. Creation has its own explicit
        // API.
        if (bones.stream().noneMatch(b -> b.id().equals(id)))
            throw new IllegalArgumentException("Bone edit cannot change ID");
        return new DocumentSnapshot(bones, d.slots(), d.attachments(), d.animations(), d.constraints(), d.resources());
    }

    public static DocumentSnapshot addSlot(DocumentSnapshot d, Slot slot) {
        return addSlot(d, slot, d.slots().size());
    }

    /** Insert before the same setup successor in every existing draw-order key. */
    public static DocumentSnapshot addSlot(DocumentSnapshot d, Slot slot, int index) {
        List<Slot> slots = new ArrayList<>(d.slots());
        slots.add(index, slot);
        var successor = index < d.slots().size() ? d.slots().get(index).id() : null;
        var animations = d.animations().stream().map(a -> {
            var tracks = a.discrete();
            var order = tracks.drawOrder().stream().map(k -> {
                var ids = new ArrayList<>(k.slots());
                ids.add(successor == null ? ids.size() : ids.indexOf(successor), slot.id());
                return new io.github.samlinb.openbone.core.animation.DiscreteTracks.OrderKey(k.id(), k.time(), ids);
            }).toList();
            return new io.github.samlinb.openbone.core.animation.AnimationClip(a.id(), a.name(), a.timelines(),
                    a.deforms(), a.constraintTracks(), new io.github.samlinb.openbone.core.animation.DiscreteTracks(
                            tracks.attachments(), order, tracks.events()));
        }).toList();
        return new DocumentSnapshot(d.bones(), slots, d.attachments(), animations, d.constraints(), d.resources());
    }

    public static DocumentSnapshot editSlot(DocumentSnapshot d, EntityId id, UnaryOperator<Slot> operation) {
        if (d.slots().stream().noneMatch(s -> s.id().equals(id)))
            throw new IllegalArgumentException("Unknown slot: " + id);
        List<Slot> slots = d.slots().stream().map(s -> s.id().equals(id) ? operation.apply(s) : s).toList();
        if (slots.stream().noneMatch(s -> s.id().equals(id)))
            throw new IllegalArgumentException("Slot edit cannot change ID");
        return new DocumentSnapshot(d.bones(), slots, d.attachments(), d.animations(), d.constraints(), d.resources());
    }

    public static DocumentSnapshot addAttachment(DocumentSnapshot d, Attachment attachment, boolean makeActive) {
        if (!d.resources().skins().isEmpty()) {
            var skins = new ArrayList<Skin>();
            for (var skin : d.skins()) {
                if (!skin.name().equals(d.resources().activeSkin())) {
                    skins.add(skin);
                    continue;
                }
                var rows = new LinkedHashMap<>(skin.attachments());
                var row = new LinkedHashMap<>(rows.getOrDefault(attachment.slotId(), Map.of()));
                if (row.putIfAbsent(attachment.name(), attachment.id()) != null)
                    throw new IllegalArgumentException(m("modeledits.0001"));
                rows.put(attachment.slotId(), row);
                skins.add(new Skin(skin.name(), rows));
            }
            var attachments = new ArrayList<>(d.attachments());
            attachments.add(attachment);
            var next = new DocumentSnapshot(d.bones(), d.slots(), attachments, d.animations(), d.constraints(),
                    new Resources(skins, d.resources().activeSkin(), d.resources().events(), d.resources().atlas()));
            return makeActive ? editSlot(next, attachment.slotId(), s -> s.withActiveAttachment(attachment.id()))
                    : next;
        }
        List<Attachment> attachments = new ArrayList<>(d.attachments());
        attachments.add(attachment);
        List<Slot> slots = d.slots().stream().map(
                s -> makeActive && s.id().equals(attachment.slotId()) ? s.withActiveAttachment(attachment.id()) : s)
                .toList();
        return new DocumentSnapshot(d.bones(), slots, attachments, d.animations(), d.constraints(), d.resources());
    }

    public static DocumentSnapshot editRegion(DocumentSnapshot d, EntityId id,
            UnaryOperator<RegionAttachment> operation) {
        if (d.attachments().stream().noneMatch(a -> a.id().equals(id) && a instanceof RegionAttachment))
            throw new IllegalArgumentException("Unknown region: " + id);
        List<Attachment> attachments = d.attachments().stream()
                .map(a -> a.id().equals(id) ? operation.apply((RegionAttachment) a) : a).toList();
        if (attachments.stream().noneMatch(a -> a.id().equals(id)))
            throw new IllegalArgumentException("Region edit cannot change ID");
        var old = d.attachment(id);
        var next = attachments.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        if (!old.name().equals(next.name()) && (!d.resources().skins().isEmpty()
                || d.animations().stream().anyMatch(a -> a.discrete().attachments().stream()
                        .anyMatch(k -> k.slotId().equals(old.slotId()) && Objects.equals(k.name(), old.name())))))
            throw new IllegalArgumentException(m("modeledits.0002"));
        return new DocumentSnapshot(d.bones(), d.slots(), attachments, d.animations(), d.constraints(), d.resources());
    }

    /**
     * World-space drag/numeric translation shares this inverse policy. Local edits
     * remain valid at zero scale.
     */
    public static DocumentSnapshot moveBoneWorld(DocumentSnapshot d, EntityId id, Vec2 target) {
        Bone bone = d.bone(id);
        Affine2 parent = bone.parentId() == null ? Affine2.IDENTITY
                : d.evaluateWorld().bones().get(bone.parentId()).matrix();
        Vec2 local = parent.inverseOrThrow().apply(target.x(), target.y());
        Transform t = bone.local();
        return editBone(d, id,
                b -> b.withLocal(new Transform(local.x(), local.y(), t.rotation(), t.scaleX(), t.scaleY())));
    }

    public static DocumentSnapshot reparent(DocumentSnapshot d, EntityId id, EntityId parent, ReparentPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Bone bone = d.bone(id);
        if (parent != null)
            d.bone(parent);
        // Validate topology before performing any inverse or decomposition.
        DocumentSnapshot topology = editBone(d, id, b -> b.withParent(parent, b.local()));
        if (policy == ReparentPolicy.KEEP_LOCAL || Objects.equals(bone.parentId(), parent))
            return topology;
        var world = d.evaluateWorld().bones();
        Affine2 parentWorld = parent == null ? Affine2.IDENTITY : world.get(parent).matrix();
        Transform local = parentWorld.inverseOrThrow().multiply(world.get(id).matrix()).toTrsOrThrow();
        return editBone(d, id, b -> b.withParent(parent, local));
    }

    private static io.github.samlinb.openbone.core.animation.DiscreteTracks nullMissing(
            io.github.samlinb.openbone.core.animation.DiscreteTracks tracks, DocumentSnapshot d,
            Set<EntityId> removed) {
        var available = new HashSet<String>();
        for (var a : d.attachments())
            if (!removed.contains(a.id()))
                available.add(a.slotId() + "\0" + a.name());
        return new io.github.samlinb.openbone.core.animation.DiscreteTracks(tracks.attachments().stream()
                .map(k -> k.name() != null && !available.contains(k.slotId() + "\0" + k.name())
                        ? new io.github.samlinb.openbone.core.animation.DiscreteTracks.AttachmentKey(k.id(), k.time(),
                                k.slotId(), null)
                        : k)
                .toList(), tracks.drawOrder(), tracks.events());
    }

    /**
     * Cascade bone descendants -> slots -> attachments. Active attachment
     * references are cleared.
     */
    public static DocumentSnapshot delete(DocumentSnapshot d, EntityId id) {
        if (!d.contains(id))
            throw new IllegalArgumentException("Unknown deletion ID: " + id);
        Set<EntityId> removed = new HashSet<>();
        removed.add(id);
        // Parent-first means one pass reaches every descendant.
        for (Bone bone : d.bones())
            if (removed.contains(bone.parentId()))
                removed.add(bone.id());
        for (Slot slot : d.slots())
            if (removed.contains(slot.boneId()))
                removed.add(slot.id());
        for (Attachment attachment : d.attachments())
            if (removed.contains(attachment.slotId()))
                removed.add(attachment.id());
        // Delete the entire dependent skin rather than silently dropping/renormalizing
        // influences.
        for (var a : d.attachments())
            if (a instanceof MeshAttachment m
                    && m.weights().stream().flatMap(List::stream).anyMatch(i -> removed.contains(i.boneId())))
                removed.add(m.id());
        for (var a : d.attachments())
            if (a instanceof ClippingAttachment c && removed.contains(c.endSlotId()))
                removed.add(c.id());
        for (var c : d.constraints())
            if (c.boneIds().stream().anyMatch(removed::contains) || removed.contains(c.targetId()))
                removed.add(c.id());
        return new DocumentSnapshot(d.bones().stream().filter(b -> !removed.contains(b.id())).toList(),
                d.slots().stream().filter(s -> !removed.contains(s.id()))
                        .map(s -> removed.contains(s.activeAttachmentId()) ? s.withActiveAttachment(null) : s).toList(),
                d.attachments().stream().filter(a -> !removed.contains(a.id())).toList(),
                d.animations().stream()
                        .map(a -> new io.github.samlinb.openbone.core.animation.AnimationClip(a.id(), a.name(),
                                a.timelines().stream().filter(t -> !removed.contains(t.boneId())).toList(),
                                a.deforms().stream().filter(t -> !removed.contains(t.attachmentId())).toList(),
                                a.constraintTracks().stream().filter(t -> !removed.contains(t.constraintId())).toList(),
                                nullMissing(a.discrete().retain(removed), d, removed)))
                        .toList(),
                d.constraints().stream().filter(c -> !removed.contains(c.id())).toList(),
                d.resources().retain(removed));
    }
}
