package io.github.samlinb.openbone.core.math;

public record Vec2(double x, double y) {
    public Vec2 { Finite.require(x, "x"); Finite.require(y, "y"); }
}
