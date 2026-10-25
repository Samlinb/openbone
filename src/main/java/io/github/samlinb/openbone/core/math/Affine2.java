package io.github.samlinb.openbone.core.math;

import java.util.Optional;

/** [a b tx; c d ty; 0 0 1]. Parent linear block times local block. */
public record Affine2(double a, double b, double c, double d, double tx, double ty) {
    public static final Affine2 IDENTITY = new Affine2(1, 0, 0, 1, 0, 0);
    public static final double INVERSE_EPSILON = 1e-12;

    public Affine2 {
        Finite.require(a, "a");
        Finite.require(b, "b");
        Finite.require(c, "c");
        Finite.require(d, "d");
        Finite.require(tx, "tx");
        Finite.require(ty, "ty");
    }

    public Affine2 multiply(Affine2 r) {
        return new Affine2(a * r.a + b * r.c, a * r.b + b * r.d, c * r.a + d * r.c, c * r.b + d * r.d,
                a * r.tx + b * r.ty + tx, c * r.tx + d * r.ty + ty);
    }

    public Vec2 apply(double x, double y) {
        Finite.require(x, "point.x");
        Finite.require(y, "point.y");
        return new Vec2(a * x + b * y + tx, c * x + d * y + ty);
    }

    public double determinant() {
        return a * d - b * c;
    }

    /**
     * abs(det) <= 1e-12 * max(1, sum(abs(linear)))^2 is non-invertible for editing.
     * Normalize first so the classification itself cannot overflow or underflow.
     */
    public Optional<Affine2> tryInverse() {
        double scale = Math.max(1, Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d))));
        double na = a / scale, nb = b / scale, nc = c / scale, nd = d / scale;
        double norm = Math.max(1 / scale, Math.abs(na) + Math.abs(nb) + Math.abs(nc) + Math.abs(nd));
        double det = na * nd - nb * nc;
        if (Math.abs(det) <= INVERSE_EPSILON * norm * norm)
            return Optional.empty();
        double ia = (nd / det) / scale, ib = (-nb / det) / scale, ic = (-nc / det) / scale, id = (na / det) / scale;
        try {
            return Optional.of(new Affine2(ia, ib, ic, id, -ia * tx - ib * ty, -ic * tx - id * ty));
        } catch (IllegalArgumentException overflow) {
            return Optional.empty();
        }
    }

    public Affine2 inverseOrThrow() {
        return tryInverse()
                .orElseThrow(() -> new IllegalArgumentException("Singular or ill-conditioned affine transform"));
    }

    /**
     * Used only by explicit keep-world reparent. Refuse shear; never approximate it
     * away.
     */
    public Transform toTrsOrThrow() {
        double sx = Math.hypot(a, c), sy = Math.hypot(b, d);
        if (!Double.isFinite(sx) || !Double.isFinite(sy))
            throw new IllegalArgumentException("TRS magnitude overflow");
        if (sx == 0 && sy == 0)
            return new Transform(tx, ty, 0, 0, 0);
        double rotation;
        if (sx == 0)
            rotation = Math.atan2(-b, d);
        else {
            if (sy != 0 && Math.abs((a / sx) * (b / sy) + (c / sx) * (d / sy)) > 1e-10)
                throw new IllegalArgumentException("Keep-world transform requires shear");
            rotation = Math.atan2(c, a);
            if ((a / sx) * (d / (sy == 0 ? 1 : sy)) - (b / (sy == 0 ? 1 : sy)) * (c / sx) < 0)
                sy = -sy;
        }
        Transform result = new Transform(tx, ty, Math.toDegrees(rotation), sx, sy);
        Affine2 rebuilt = result.matrix();
        double[] expected = { a, b, c, d }, actual = { rebuilt.a, rebuilt.b, rebuilt.c, rebuilt.d };
        for (int i = 0; i < 4; i++)
            if (Math.abs(expected[i] - actual[i]) > 1e-9 + 1e-10 * Math.abs(expected[i]))
                throw new IllegalArgumentException("Keep-world transform cannot be represented by TRS");
        return result;
    }
}
