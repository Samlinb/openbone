package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Slot tracks: discrete attachments, draw order and events, plus continuous color
 * timelines. Absence of an attachment key is distinct from a null value.
 */
public record DiscreteTracks(List<AttachmentKey> attachments, List<OrderKey> drawOrder, List<EventKey> events,
        List<SlotColorTimeline> colors) {
    public DiscreteTracks(List<AttachmentKey> attachments, List<OrderKey> drawOrder, List<EventKey> events) {
        this(attachments, drawOrder, events, List.of());
    }
    public static final DiscreteTracks EMPTY = new DiscreteTracks(List.of(), List.of(), List.of());

    public interface Timed {
        EntityId id();

        double time();
    }

    public record AttachmentKey(EntityId id, double time, EntityId slotId, String name) implements Timed {
        public AttachmentKey {
            Objects.requireNonNull(id);
            Objects.requireNonNull(slotId);
            time = Keyframe.seconds(time);
            if (name != null && (name.isBlank() || !name.equals(name.trim()) || name.length() > 256))
                throw new IllegalArgumentException(m("discretetracks.0001"));
        }
    }

    public record OrderKey(EntityId id, double time, List<EntityId> slots) implements Timed {
        public OrderKey {
            Objects.requireNonNull(id);
            time = Keyframe.seconds(time);
            slots = List.copyOf(slots);
            if (new HashSet<>(slots).size() != slots.size())
                throw new IllegalArgumentException(m("discretetracks.0002"));
        }
    }

    public record EventKey(EntityId id, double time, EntityId eventId, int intValue, double floatValue,
            String stringValue, double volume, double balance) implements Timed {
        public EventKey {
            Objects.requireNonNull(id);
            Objects.requireNonNull(eventId);
            time = Keyframe.seconds(time);
            Finite.require(floatValue, "event.float");
            Objects.requireNonNull(stringValue);
            Finite.require(volume, "event.volume");
            Finite.require(balance, "event.balance");
            if (volume < 0 || volume > 1 || balance < -1 || balance > 1)
                throw new IllegalArgumentException(m("discretetracks.0003"));
        }
    }

    public DiscreteTracks {
        attachments = sorted(attachments);
        drawOrder = sorted(drawOrder);
        events = sorted(events);
        colors = List.copyOf(colors);
        var components = new HashSet<String>();
        for (var t : colors) {
            int start = t.channel() == SlotColorTimeline.Channel.ALPHA ? 3 : 0;
            int end = t.channel() == SlotColorTimeline.Channel.RGB ? 3 : 4;
            for (int c = start; c < end; c++) if (!components.add(t.slotId() + ":" + c))
                throw new IllegalArgumentException("Overlapping slot color timelines");
        }
        // Record constructors canonicalize -0.0 to +0.0 before sorting or typed time
        // lookup.
        var attachmentTimes = new HashMap<EntityId, Set<Double>>();
        for (var k : attachments)
            if (!attachmentTimes.computeIfAbsent(k.slotId(), ignored -> new HashSet<>()).add(k.time()))
                throw new IllegalArgumentException(m("discretetracks.0004"));
        var orderTimes = new HashSet<Double>();
        for (var k : drawOrder)
            if (!orderTimes.add(k.time()))
                throw new IllegalArgumentException(m("discretetracks.0005"));
        var ids = new HashSet<EntityId>();
        for (var k : all(attachments, drawOrder, events))
            if (!ids.add(k.id()))
                throw new IllegalArgumentException(m("discretetracks.0006"));
        for (var t : colors) for (var k : t.keys()) if (!ids.add(k.id()))
            throw new IllegalArgumentException("Duplicate color key ID");
    }

    private static <T extends Timed> List<T> sorted(List<T> values) {
        return values.stream().sorted(Comparator.comparingDouble(Timed::time)).toList();
    }

    private static List<Timed> all(List<AttachmentKey> a, List<OrderKey> d, List<EventKey> e) {
        var all = new ArrayList<Timed>();
        all.addAll(a);
        all.addAll(d);
        all.addAll(e);
        return List.copyOf(all);
    }

    public List<Timed> keys() {
        var keys = new ArrayList<>(all(attachments, drawOrder, events));
        colors.forEach(t -> keys.addAll(t.keys()));
        return List.copyOf(keys);
    }

    public double duration() {
        return keys().stream().mapToDouble(Timed::time).max().orElse(0);
    }

    public DiscreteTracks retain(Set<EntityId> removed) {
        return new DiscreteTracks(
                attachments.stream().filter(
                        k -> !removed.contains(k.slotId())).toList(),
                drawOrder.stream().map(k -> new OrderKey(k.id(), k.time(),
                        k.slots().stream().filter(id -> !removed.contains(id)).toList())).toList(),
                events, colors.stream().filter(t -> !removed.contains(t.slotId())).toList());
    }

    public DiscreteTracks duplicate() {
        return new DiscreteTracks(
                attachments.stream().map(k -> new AttachmentKey(EntityId.create(), k.time(), k.slotId(), k.name()))
                        .toList(),
                drawOrder.stream().map(k -> new OrderKey(EntityId.create(), k.time(), k.slots())).toList(),
                events.stream().map(k -> new EventKey(EntityId.create(), k.time(), k.eventId(), k.intValue(),
                        k.floatValue(), k.stringValue(), k.volume(), k.balance())).toList(),
                colors.stream().map(SlotColorTimeline::duplicate).toList());
    }
}
