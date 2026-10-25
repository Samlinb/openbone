package io.github.samlinb.openbone.core.animation;

import java.util.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Playback interval in absolute seconds. Seeking does not call this API. At a
 * loop
 * boundary the previous tail precedes the next zero. Work is bounded before
 * allocation.
 */
public final class EventSampler {
    private EventSampler() {
    }

    public record Fired(DiscreteTracks.EventKey key, long cycle, double absoluteTime) {
    }

    public static List<Fired> between(AnimationClip clip, double previous, double current, boolean loop,
            boolean includeStart) {
        Keyframe.seconds(previous);
        Keyframe.seconds(current);
        if (current < previous)
            throw new IllegalArgumentException(m("eventsampler.0001"));
        var keys = clip.discrete().events();
        if (keys.isEmpty())
            return List.of();
        double duration = clip.duration();
        if (loop && duration > 0
                && (!Double.isFinite(current / duration) || current / duration >= Long.MAX_VALUE - 1.0))
            throw new IllegalArgumentException(m("eventsampler.0002"));
        long first = loop && duration > 0 ? (long) Math.max(0, Math.floor(previous / duration) - 1) : 0,
                last = loop && duration > 0 ? (long) Math.floor(current / duration) : 0;
        if (last - first > 100000 || ((double) last - first + 1) * keys.size() > 1000000)
            throw new IllegalArgumentException(m("eventsampler.0003"));
        var fired = new ArrayList<Fired>();
        for (long cycle = first; cycle <= last; cycle++)
            for (var key : keys) {
                double t = cycle * duration + key.time();
                if ((t > previous || includeStart && t == previous) && t <= current)
                    fired.add(new Fired(key, cycle, t));
            }
        return List.copyOf(fired);
    }
}
