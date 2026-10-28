package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Immutable preview plan, shared by desktop and MCP. No project or history edits. */
public final class AnimationSequence {
    public record Entry(EntityId animation, double start, double duration, int index) {}
    public record Position(EntityId animation, double time, int index) {}
    private final List<EntityId> ids;
    private final List<Entry> entries;
    private final double duration;

    public AnimationSequence(DocumentSnapshot document, List<EntityId> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 128)
            throw new IllegalArgumentException(m("sequence.invalid"));
        this.ids = List.copyOf(ids);
        var result = new ArrayList<Entry>();
        double end = 0;
        for (int i = 0; i < ids.size(); i++) {
            document.animation(ids.get(i));
            double length = PhysicsPlayer.duration(document, ids.get(i));
            // Empty actions have no interval to preview; do not stall the queue.
            if (length > 0) result.add(new Entry(ids.get(i), end, length, i));
            end += length;
        }
        if (!(end > 0) || !Double.isFinite(end))
            throw new IllegalArgumentException(m("sequence.empty"));
        entries = List.copyOf(result);
        duration = end;
    }

    public List<EntityId> ids() { return ids; }
    public double duration() { return duration; }

    public Position position(double time) {
        Keyframe.seconds(time);
        for (var entry : entries)
            if (time < entry.start() + entry.duration())
                return new Position(entry.animation(), Math.max(0, time - entry.start()), entry.index());
        var last = entries.get(entries.size() - 1);
        return new Position(last.animation(), last.duration(), last.index());
    }

    /** End events precede next-action zero events, even when a delayed tick spans actions. */
    public List<EventSampler.Fired> events(DocumentSnapshot document, double previous, double current,
            boolean loop, boolean includeStart) {
        Keyframe.seconds(previous); Keyframe.seconds(current);
        if (current < previous) throw new IllegalArgumentException(m("eventsampler.0001"));
        double firstCycle = loop ? Math.floor(previous / duration) : 0;
        double lastCycle = loop ? Math.floor(current / duration) : 0;
        if (lastCycle >= Long.MAX_VALUE - 1.0 || (lastCycle - firstCycle + 1) * entries.size() > 100000)
            throw new IllegalArgumentException(m("eventsampler.0003"));
        var fired = new ArrayList<EventSampler.Fired>();
        for (long cycle = (long) firstCycle; cycle <= (long) lastCycle; cycle++)
            for (var entry : entries) {
                double start = cycle * duration + entry.start(), end = start + entry.duration();
                if (current < start || previous > end) continue;
                boolean zero = start > previous || includeStart && start == previous;
                for (var event : EventSampler.between(document.animation(entry.animation()),
                        Math.max(0, previous - start), Math.min(entry.duration(), current - start), false, zero)) {
                    if (fired.size() >= 1000000) throw new IllegalArgumentException(m("eventsampler.0003"));
                    fired.add(new EventSampler.Fired(event.key(), cycle, start + event.absoluteTime()));
                }
            }
        return List.copyOf(fired);
    }
}
