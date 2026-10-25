package io.github.samlinb.openbone.core.math;

public final class Finite {
    private Finite() {}
    public static double require(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }
}
