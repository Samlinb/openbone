package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;

/**
 * IO-only float interpolation audit using cubic polynomial forward differences.
 * This never supplies values to the product animation sampler or renderer.
 */
final class FloatTimelineValue {
    private FloatTimelineValue() {
    }

    private static float finite(float value) throws ProjectFormatException {
        if (!Float.isFinite(value))
            throw ProjectCodec.bad("physics.float-timeline", "Non-finite float interpolation intermediate");
        return value;
    }

    private static float line(float time, float x, float y, float nx, float ny) throws ProjectFormatException {
        return finite(y + (time - x) / (nx - x) * (ny - y));
    }

    static Float sample(List<Keyframe> keys, int component, float time) throws ProjectFormatException {
        if (time < (float) keys.get(0).time())
            return null;
        int lo = 0, hi = keys.size();
        while (lo + 1 < hi) {
            int middle = (lo + hi) / 2;
            if ((float) keys.get(middle).time() <= time)
                lo = middle;
            else
                hi = middle;
        }
        var a = keys.get(lo);
        float value = (float) (double) a.values().get(component);
        if (lo + 1 == keys.size() || a.curves().get(component).kind() == Curve.Kind.STEPPED)
            return value;
        var b = keys.get(lo + 1);
        float start = (float) a.time(), end = (float) b.time(), next = (float) (double) b.values().get(component);
        var curve = a.curves().get(component);
        if (curve.kind() == Curve.Kind.LINEAR)
            return line(time, start, value, end, next);
        double dt = b.time() - a.time(), dv = b.values().get(component) - a.values().get(component);
        float cx1 = (float) (a.time() + dt * curve.x1()), cx2 = (float) (a.time() + dt * curve.x2()),
                cy1 = (float) (a.values().get(component) + dv * curve.y1()),
                cy2 = (float) (a.values().get(component) + dv * curve.y2());
        // h=1/10: coefficients 3h, 3h², 6h³ determine the three differences.
        float qx = (start - cx1 * 2 + cx2) * (3f / 100), qy = (value - cy1 * 2 + cy2) * (3f / 100),
                dddx = ((cx1 - cx2) * 3 - start + end) * (6f / 1000),
                dddy = ((cy1 - cy2) * 3 - value + next) * (6f / 1000), ddx = qx * 2 + dddx, ddy = qy * 2 + dddy,
                dx = (cx1 - start) * (3f / 10) + qx + dddx * (1f / 6),
                dy = (cy1 - value) * (3f / 10) + qy + dddy * (1f / 6), x = start + dx, y = value + dy,
                previousX = start, previousY = value;
        for (int n = 1; n <= 9; n++) {
            finite(x);
            finite(y);
            if (n == 1 ? x > time : x >= time)
                return line(time, previousX, previousY, x, y);
            previousX = x;
            previousY = y;
            dx = finite(dx + ddx);
            dy = finite(dy + ddy);
            ddx = finite(ddx + dddx);
            ddy = finite(ddy + dddy);
            x = finite(x + dx);
            y = finite(y + dy);
        }
        return line(time, previousX, previousY, end, next);
    }
}
