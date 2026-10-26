package io.github.samlinb.openbone.core.math;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Independent affine operations. World output stays a matrix, with no lossy TRS
 * round trip.
 */
final class TransformConstraintSolver {
    private TransformConstraintSolver() {
    }

    static void apply(TransformConstraint c, Map<EntityId, Transform> local, Map<EntityId, Affine2> world,
            Map<EntityId, Double> shear, Map<EntityId, Affine2> locked) {
        if (c.inactive())
            return;
        if (c.local()) {
            var target = local.get(c.targetId());
            double targetShear = shear.getOrDefault(c.targetId(), 0.0);
            double[] to = { target.rotation(), target.x(), target.y(), target.scaleX(), target.scaleY(), targetShear };
            for (var id : c.boneIds()) {
                var p = local.get(id);
                double[] from = { p.rotation(), p.x(), p.y(), p.scaleX(), p.scaleY(), shear.getOrDefault(id, 0.0) },
                        out = from.clone();
                for (int n = 0; n < 6; n++) {
                    double mix = c.mix().get(n), offset = c.offset().get(n);
                    if (mix == 0)
                        continue;
                    if (n == 3 || n == 4) {
                        if (c.relative())
                            out[n] = Finite.require(from[n] * (1 + (to[n] - 1 + offset) * mix), "relative local scale");
                        // Target 4.2 absolute-local divides the interpolated value by the
                        // old scale. Tiny nonzero input uses the documented double-only
                        // degenerate axis; float IO must certify/reject it separately.
                        else
                            out[n] = Math.abs(from[n]) < 1e-12 ? 0
                                    : Finite.require((from[n] + (to[n] - from[n] + offset) * mix) / from[n],
                                            "absolute local scale");
                    } else
                        out[n] = Finite.require(from[n] + (to[n] - (c.relative() ? 0 : from[n]) + offset) * mix,
                                "local transform component");
                }
                local.put(id, new Transform(out[1], out[2], out[0], out[3], out[4], p.shearX(), out[5]));
                shear.put(id, out[5]);
            }
            return;
        }
        var target = world.get(c.targetId());
        double reflect = Finite.require(target.determinant(), "transform target determinant") > 0 ? 1 : -1;
        double targetAngle = Math.atan2(target.c(), target.a()), targetY = Math.atan2(target.d(), target.b());
        var position = target.apply(c.offset().get(1), c.offset().get(2));
        for (var id : c.boneIds()) {
            var original = world.get(id);
            double a = original.a(), b = original.b(), x = original.tx(), y = original.ty(), cc = original.c(),
                    d = original.d();
            if (c.mix().get(0) != 0) {
                double r = wrap(targetAngle - (c.relative() ? 0 : Math.atan2(cc, a))
                        + Math.toRadians(c.offset().get(0)) * reflect) * c.mix().get(0);
                double cos = Math.cos(r), sin = Math.sin(r), na = cos * a - sin * cc, nb = cos * b - sin * d;
                cc = sin * a + cos * cc;
                d = sin * b + cos * d;
                a = na;
                b = nb;
            }
            x = Finite.require(x + (position.x() - (c.relative() ? 0 : x)) * c.mix().get(1), "world transform X");
            y = Finite.require(y + (position.y() - (c.relative() ? 0 : y)) * c.mix().get(2), "world transform Y");
            if (c.mix().get(3) != 0) {
                double length = Math.hypot(a, cc), targetLength = Math.hypot(target.a(), target.c());
                double factor = c.relative() ? 1 + (targetLength - 1 + c.offset().get(3)) * c.mix().get(3)
                        : length < 1e-12 ? 0
                                : (length + (targetLength - length + c.offset().get(3)) * c.mix().get(3)) / length;
                a *= factor;
                cc *= factor;
            }
            if (c.mix().get(4) != 0) {
                double length = Math.hypot(b, d), targetLength = Math.hypot(target.b(), target.d());
                double factor = c.relative() ? 1 + (targetLength - 1 + c.offset().get(4)) * c.mix().get(4)
                        : length < 1e-12 ? 0
                                : (length + (targetLength - length + c.offset().get(4)) * c.mix().get(4)) / length;
                b *= factor;
                d *= factor;
            }
            if (c.mix().get(5) > 0) {
                double by = Math.atan2(d, b), angle;
                if (c.relative())
                    angle = by
                            + (wrap(targetY - targetAngle) - Math.PI / 2 + Math.toRadians(c.offset().get(5)) * reflect)
                                    * c.mix().get(5);
                else
                    angle = by + (wrap(targetY - targetAngle - (by - Math.atan2(cc, a)))
                            + Math.toRadians(c.offset().get(5)) * reflect) * c.mix().get(5);
                double length = Math.hypot(b, d);
                b = Math.cos(angle) * length;
                d = Math.sin(angle) * length;
            }
            locked.put(id, new Affine2(a, b, cc, d, x, y));
        }
    }

    private static double wrap(double a) {
        if (a > Math.PI)
            a -= 2 * Math.PI;
        else if (a < -Math.PI)
            a += 2 * Math.PI;
        return a;
    }
}
