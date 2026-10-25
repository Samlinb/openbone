package io.github.samlinb.openbone.core.math;

/** X right, Y up; degrees CCW; column vectors T*R*S. Angles retain multiple turns. */
public record Transform(double x, double y, double rotation, double scaleX, double scaleY) {
    public static final Transform IDENTITY = new Transform(0, 0, 0, 1, 1);
    public Transform {
        Finite.require(x, "x"); Finite.require(y, "y"); Finite.require(rotation, "rotation");
        Finite.require(scaleX, "scaleX"); Finite.require(scaleY, "scaleY");
    }
    public Affine2 matrix() {
        // Modulo before radians prevents overflow for finite, very large saved angles.
        double angle = Math.toRadians(rotation % 360), c = Math.cos(angle), s = Math.sin(angle);
        return new Affine2(c * scaleX, -s * scaleY, s * scaleX, c * scaleY, x, y);
    }
}
