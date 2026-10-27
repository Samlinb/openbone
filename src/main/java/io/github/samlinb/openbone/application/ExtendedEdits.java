package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * P8 operations are complete immutable transactions, shared by Swing and test
 * clients.
 */
public final class ExtendedEdits {
    private ExtendedEdits() {
    }

    /**
     * Recording after deleting the last clip preserves explicit skins and all
     * resource IDs.
     */
    public static DocumentSnapshot ensureAnimation(DocumentSnapshot d) {
        if (!d.animations().isEmpty())
            return d;
        EntityId id = EntityId.create();
        while (d.contains(id))
            id = EntityId.create();
        return createAnimation(d, id, m("extendededits.0001"));
    }

    public static DocumentSnapshot createAnimation(DocumentSnapshot d, EntityId id, String name) {
        var all = new ArrayList<>(d.animations());
        all.add(new AnimationClip(id, name, List.of()));
        return d.withAnimations(all);
    }

    public static DocumentSnapshot renameAnimation(DocumentSnapshot d, EntityId id, String name) {
        return AnimationEdits.clip(d, id,
                a -> new AnimationClip(a.id(), name, a.timelines(), a.deforms(), a.constraintTracks(), a.discrete()));
    }

    public static DocumentSnapshot deleteAnimation(DocumentSnapshot d, EntityId id) {
        d.animation(id);
        return d.withAnimations(d.animations().stream().filter(a -> !a.id().equals(id)).toList());
    }

    public static DocumentSnapshot duplicateAnimation(DocumentSnapshot d, EntityId id, EntityId next, String name) {
        var a = d.animation(id);
        var b = new AnimationClip(next, name,
                a.timelines().stream().map(t -> new BoneTimeline(t.boneId(), t.channel(), copy(t.keys()))).toList(),
                a.deforms().stream().map(t -> new DeformTimeline(t.attachmentId(), copy(t.keys()))).toList(),
                a.constraintTracks().stream()
                        .map(t -> new ConstraintTimeline(t.constraintId(), t.channel(), copy(t.keys()))).toList(),
                a.discrete().duplicate());
        var all = new ArrayList<>(d.animations());
        all.add(b);
        return d.withAnimations(all);
    }

    private static List<Keyframe> copy(List<Keyframe> keys) {
        return keys.stream().map(k -> new Keyframe(EntityId.create(), k.time(), k.values(), k.curves())).toList();
    }

    public static DocumentSnapshot skin(DocumentSnapshot d, String name) {
        return d.withResources(d.resources().skin(name));
    }

    public static DocumentSnapshot addSkin(DocumentSnapshot d, String name) {
        var skins = new ArrayList<>(d.skins());
        skins.add(new Skin(name, Map.of()));
        return d.withResources(
                new Resources(skins, d.resources().activeSkin(), d.resources().events(), d.resources().atlas(), d.resources().metadata(), d.resources().activation()));
    }

    public static DocumentSnapshot bind(DocumentSnapshot d, String skin, Attachment a, AtlasRegion region) {
        var all = new ArrayList<>(d.attachments());
        if (all.stream().noneMatch(old -> old.id().equals(a.id())))
            all.add(a);
        var skins = new ArrayList<Skin>();
        boolean found = false;
        for (var s : d.skins()) {
            if (!s.name().equals(skin)) {
                skins.add(s);
                continue;
            }
            found = true;
            var rows = new LinkedHashMap<>(s.attachments());
            var row = new LinkedHashMap<>(rows.getOrDefault(a.slotId(), Map.of()));
            if (row.putIfAbsent(a.name(), a.id()) != null)
                throw new IllegalArgumentException(m("extendededits.0002"));
            rows.put(a.slotId(), row);
            skins.add(new Skin(skin, rows));
        }
        if (!found)
            throw new IllegalArgumentException(m("extendededits.0003"));
        var atlas = new HashMap<>(d.resources().atlas());
        if (region != null)
            atlas.put(a.id(), region);
        return new DocumentSnapshot(d.bones(), d.slots(), all, d.animations(), d.constraints(),
                new Resources(skins, d.resources().activeSkin(), d.resources().events(), atlas, d.resources().metadata(), d.resources().activation()));
    }

    public static DocumentSnapshot attachment(DocumentSnapshot d, EntityId clip, EntityId slot, double time,
            String name) {
        return AnimationEdits.clip(d, clip, a -> {
            var old = a.discrete();
            var keys = new ArrayList<>(old.attachments());
            var key = keys.stream().filter(k -> k.slotId().equals(slot) && k.time() == time).findFirst().orElse(null);
            keys.removeIf(k -> k.slotId().equals(slot) && k.time() == time);
            keys.add(new DiscreteTracks.AttachmentKey(key == null ? EntityId.create() : key.id(), time, slot, name));
            return discrete(a, new DiscreteTracks(keys, old.drawOrder(), old.events(),old.colors()));
        });
    }

    public static DocumentSnapshot drawOrder(DocumentSnapshot d, EntityId clip, double time, List<EntityId> slots) {
        return AnimationEdits.clip(d, clip, a -> {
            var old = a.discrete();
            var keys = new ArrayList<>(old.drawOrder());
            var key = keys.stream().filter(k -> k.time() == time).findFirst().orElse(null);
            keys.removeIf(k -> k.time() == time);
            keys.add(new DiscreteTracks.OrderKey(key == null ? EntityId.create() : key.id(), time, slots));
            return discrete(a, new DiscreteTracks(old.attachments(), keys, old.events(),old.colors()));
        });
    }

    public static DocumentSnapshot eventDefinition(DocumentSnapshot d, EventDefinition e) {
        var events = new ArrayList<>(d.resources().events());
        events.removeIf(old -> old.id().equals(e.id()));
        events.add(e);
        return d.withResources(
                new Resources(d.resources().skins(), d.resources().activeSkin(), events, d.resources().atlas(), d.resources().metadata(), d.resources().activation()));
    }

    public static DocumentSnapshot event(DocumentSnapshot d, EntityId clip, DiscreteTracks.EventKey e) {
        return AnimationEdits.clip(d, clip, a -> {
            var old = a.discrete();
            var keys = new ArrayList<>(old.events());
            keys.removeIf(k -> k.id().equals(e.id()));
            keys.add(e);
            return discrete(a, new DiscreteTracks(old.attachments(), old.drawOrder(), keys,old.colors()));
        });
    }

    public static DocumentSnapshot removeDiscrete(DocumentSnapshot d, EntityId clip, EntityId id) {
        return AnimationEdits.clip(d, clip, a -> {
            var old = a.discrete();
            return discrete(a,
                    new DiscreteTracks(old.attachments().stream().filter(k -> !k.id().equals(id)).toList(),
                            old.drawOrder().stream().filter(k -> !k.id().equals(id)).toList(),
                            old.events().stream().filter(k -> !k.id().equals(id)).toList(),
                            old.colors().stream().filter(t -> t.keys().stream().anyMatch(k -> !k.id().equals(id)))
                                    .map(t -> new io.github.samlinb.openbone.core.animation.SlotColorTimeline(t.slotId(),t.channel(),
                                            t.keys().stream().filter(k -> !k.id().equals(id)).toList())).toList()));
        });
    }

    public static AnimationClip discrete(AnimationClip a, DiscreteTracks value) {
        return new AnimationClip(a.id(), a.name(), a.timelines(), a.deforms(), a.constraintTracks(), value);
    }
}
