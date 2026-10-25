package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;

/**
 * Shared double sampling, preserving P4's exact-key and pre-first-key behavior.
 */
public final class TimelineSample {
    private TimelineSample() {
    }

    public static Optional<List<Double>> sample(List<Keyframe> keys, double time) {
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
        for (int i = 0; i < a.values().size(); i++) {
            double v = a.values().get(i), w = b.values().get(i), q = a.curves().get(i).progress(p);
            double value = v == w ? v
                    : (q < 0 || q > 1) && Double.isFinite(w - v) ? v + (w - v) * q : (1 - q) * v + q * w;
            values.add(Finite.require(value, "sample value"));
        }
        return Optional.of(List.copyOf(values));
    }
}
