package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.EntityId;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

public record BoneTimeline(EntityId boneId, Channel channel, List<Keyframe> keys) {
    public BoneTimeline {
        Objects.requireNonNull(boneId);
        Objects.requireNonNull(channel);
        keys = List.copyOf(keys);
        if (keys.isEmpty())
            throw new IllegalArgumentException(m("bonetimeline.0001"));
        double previous = -1;
        Set<EntityId> ids = new HashSet<>();
        for (var k : keys) {
            if (k.time() <= previous)
                throw new IllegalArgumentException(m("bonetimeline.0002"));
            previous = k.time();
            if (!ids.add(k.id()))
                throw new IllegalArgumentException("Duplicate key ID");
            if (k.values().size() != channel.components())
                throw new IllegalArgumentException(m("bonetimeline.0003"));
        }
    }

    /** Before first key -> no override; at/after last -> exact last value. */
    public Optional<List<Double>> sample(double time) {
        Keyframe.seconds(time);
        if (time < keys.get(0).time())
            return Optional.empty();
        int low = 0, high = keys.size();
        while (low + 1 < high) {
            int m = (low + high) / 2;
            if (keys.get(m).time() <= time)
                low = m;
            else
                high = m;
        }
        var a = keys.get(low);
        if (low == keys.size() - 1 || a.time() == time)
            return Optional.of(a.values());
        var b = keys.get(low + 1);
        double p = (time - a.time()) / (b.time() - a.time());
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < channel.components(); i++) {
            double v = a.values().get(i), w = b.values().get(i), q = a.curves().get(i).progress(p);
            // Weighted sum avoids overflow of (w-v) for opposite finite endpoints.
            double value = v == w ? v
                    : (q < 0 || q > 1) && Double.isFinite(w - v) ? v + (w - v) * q : (1 - q) * v + q * w;
            values.add(Finite.require(value, "sample value"));
        }
        return Optional.of(List.copyOf(values));
    }
}
