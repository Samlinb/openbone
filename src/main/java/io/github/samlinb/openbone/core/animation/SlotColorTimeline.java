package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

/** Absolute slot color channels. RGB and alpha may animate independently. */
public record SlotColorTimeline(EntityId slotId, Channel channel, List<Keyframe> keys) {
    public enum Channel { RGBA, RGB, ALPHA;
        public int components() { return this == RGBA ? 4 : this == RGB ? 3 : 1; }
    }
    public SlotColorTimeline {
        Objects.requireNonNull(slotId); Objects.requireNonNull(channel); keys = List.copyOf(keys);
        if (keys.isEmpty()) throw new IllegalArgumentException("Color timeline requires keys");
        double previous = -1;
        for (var key : keys) {
            if (key.time() <= previous || key.values().size() != channel.components()) throw new IllegalArgumentException("Invalid color keys");
            for (double v : key.values()) if (v < 0 || v > 1) throw new IllegalArgumentException("Color key outside [0,1]");
            previous = key.time();
        }
    }
    public Rgba sample(Rgba setup, double time) {
        var sampled = TimelineSample.sample(keys,time);
        if (sampled.isEmpty()) return setup;
        var v = sampled.get().stream().map(Rgba::clamp).toList();
        return channel == Channel.ALPHA ? new Rgba(setup.r(),setup.g(),setup.b(),v.get(0))
                : new Rgba(v.get(0),v.get(1),v.get(2),channel == Channel.RGBA ? v.get(3) : setup.a());
    }
    public SlotColorTimeline duplicate() {
        return new SlotColorTimeline(slotId,channel,keys.stream().map(k -> new Keyframe(EntityId.create(),k.time(),k.values(),k.curves())).toList());
    }
}
