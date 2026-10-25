package io.github.samlinb.openbone.core.animation;

import java.util.Objects;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Segment-local time/value fractions. P5 converts handles to absolute
 * seconds/relative values.
 * Runtime 4.2 evaluates ten straight chords of the cubic (u=.1 ... .9). We
 * evaluate
 * that same geometry in double, independently of the runtime's forward
 * differences.
 */
public record Curve(Kind kind, double x1, double y1, double x2, double y2) {
    public enum Kind {
        LINEAR, STEPPED, BEZIER
    }

    public static final Curve LINEAR = new Curve(Kind.LINEAR, 0, 0, 1, 1);
    public static final Curve STEPPED = new Curve(Kind.STEPPED, 0, 0, 1, 1);

    public Curve {
        Objects.requireNonNull(kind);
        Finite.require(x1, "curve x1");
        Finite.require(y1, "curve y1");
        Finite.require(x2, "curve x2");
        Finite.require(y2, "curve y2");
        if (x1 < 0 || x2 > 1 || x1 > x2)
            throw new IllegalArgumentException(m("curve.0001"));
        if (kind != Kind.BEZIER && (x1 != 0 || y1 != 0 || x2 != 1 || y2 != 1))
            throw new IllegalArgumentException(m("curve.0002"));
    }

    public double progress(double time) {
        Finite.require(time, "curve time");
        if (time <= 0)
            return 0;
        if (time >= 1)
            return 1;
        if (kind == Kind.LINEAR)
            return time;
        if (kind == Kind.STEPPED)
            return 0;
        double px = 0, py = 0;
        for (int i = 1; i <= 10; i++) {
            double u = i / 10.0, v = 1 - u;
            double x = i == 10 ? 1 : 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u;
            double y = i == 10 ? 1 : 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u;
            if (time <= x)
                return Finite.require(py + (y - py) * ((time - px) / (x - px)), "curve result");
            px = x;
            py = y;
        }
        return 1;
    }
}
