package io.github.samlinb.openbone.core.math;

import io.github.samlinb.openbone.core.model.Bone;

/** Bone origins always follow the full parent; inheritance changes only the linear block. */
public final class BoneInheritance {
    private BoneInheritance() {}

    private static Affine2 world(Bone.Inherit mode, Affine2 parent, Affine2 local) {
        var origin = parent.apply(local.tx(), local.ty());
        Affine2 linear;
        switch (mode) {
            case normal -> { return parent.multiply(local); }
            case onlyTranslation -> linear = local;
            case noRotationOrReflection -> {
                double length = Math.hypot(parent.a(), parent.c());
                if (length * length > .0001) {
                    // Stretch along the parent's world axes, with rotation and reflection removed.
                    double ux = parent.a() / length, uy = parent.c() / length;
                    double other = Math.abs(parent.determinant()) / length;
                    double cross = (length - other) * ux * uy;
                    linear = new Affine2(length * ux * ux + other * uy * uy, cross,
                            cross, length * uy * uy + other * ux * ux, 0, 0).multiply(local);
                } else {
                    double angle = Math.atan2(parent.b(), parent.d());
                    var rotation = new Transform(0, 0, -Math.toDegrees(angle), 1, 1).matrix();
                    linear = new Affine2(0, parent.b(), 0, parent.d(), 0, 0).multiply(rotation).multiply(local);
                }
            }
            default -> throw new IllegalArgumentException("Unknown bone inheritance");
        }
        return new Affine2(linear.a(), linear.b(), linear.c(), linear.d(), origin.x(), origin.y());
    }

    public static Affine2 world(Bone.Inherit mode, Affine2 parent, Transform local, Affine2 matrix) {
        if (mode != Bone.Inherit.noScale && mode != Bone.Inherit.noScaleOrReflection)
            return world(mode, parent, matrix);
        var linear = noScale(mode, parent, matrix, Math.toRadians(local.rotation() % 360));
        var origin = parent.apply(local.x(), local.y());
        return new Affine2(linear.a(), linear.b(), linear.c(), linear.d(), origin.x(), origin.y());
    }

    /** Inverse for explicit world edits. Refuses singular parents and unrepresentable shear. */
    public static Transform local(Bone.Inherit mode, Affine2 parent, Affine2 desired) {
        var inverse = parent.inverseOrThrow();
        var origin = inverse.apply(desired.tx(), desired.ty());
        Affine2 basis;
        if (mode == Bone.Inherit.noScale || mode == Bone.Inherit.noScaleOrReflection) {
            double x = desired.a(), y = desired.c();
            if (Math.hypot(x, y) < 1e-12) { x = desired.d(); y = -desired.b(); }
            double angle = Math.atan2(inverse.c() * x + inverse.d() * y, inverse.a() * x + inverse.b() * y);
            var rotation = new Transform(0, 0, Math.toDegrees(angle), 1, 1);
            var worldRotation = world(mode, parent, rotation, rotation.matrix());
            var linear = new Affine2(worldRotation.a(), worldRotation.b(), worldRotation.c(), worldRotation.d(), 0, 0);
            basis = linear.multiply(new Transform(0, 0, -Math.toDegrees(angle), 1, 1).matrix());
        } else {
            var inherited = world(mode, parent, Affine2.IDENTITY);
            basis = new Affine2(inherited.a(), inherited.b(), inherited.c(), inherited.d(), 0, 0);
        }
        var linear = basis.inverseOrThrow().multiply(new Affine2(desired.a(), desired.b(), desired.c(), desired.d(), 0, 0));
        return new Affine2(linear.a(), linear.b(), linear.c(), linear.d(), origin.x(), origin.y()).toTransformOrThrow();
    }

    private static Affine2 noScale(Bone.Inherit mode, Affine2 parent, Affine2 local, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double x = parent.a() * cos + parent.b() * sin, y = parent.c() * cos + parent.d() * sin;
        double length = Math.hypot(x, y), factor = length > .00001 ? 1 / length : length;
        x *= factor; y *= factor;
        double sign = mode == Bone.Inherit.noScale && parent.determinant() < 0 ? -1 : 1;
        var basis = new Affine2(x, -y * sign, y, x * sign, 0, 0);
        // Undo local rotation before applying the parent's normalized rotated basis.
        return basis.multiply(new Affine2(cos, sin, -sin, cos, 0, 0)).multiply(local);
    }
}
