package io.github.samlinb.openbone.core.math;

/** X right, Y up; degrees CCW; column vectors T*R*S. Angles retain multiple turns. */
public record Transform(double x, double y, double rotation, double scaleX, double scaleY, double shearX, double shearY) {
    public Transform(double x, double y, double rotation, double scaleX, double scaleY) {
        this(x, y, rotation, scaleX, scaleY, 0, 0);
    }
    public static final Transform IDENTITY = new Transform(0, 0, 0, 1, 1);
    public Transform {
        Finite.require(x, "x"); Finite.require(y, "y"); Finite.require(rotation, "rotation");
        Finite.require(scaleX, "scaleX"); Finite.require(scaleY, "scaleY");
        Finite.require(shearX, "shearX"); Finite.require(shearY, "shearY");
    }
    public Affine2 matrix() {
        // Modulo before radians prevents overflow for finite, very large saved angles.
        double angle = Math.toRadians((rotation % 360 + shearX % 360) % 360), c = Math.cos(angle), s = Math.sin(angle);
        double angleY = Math.toRadians((rotation % 360 + shearY % 360) % 360);
        return new Affine2(c * scaleX, -Math.sin(angleY) * scaleY, s * scaleX, Math.cos(angleY) * scaleY, x, y);
    }
}
