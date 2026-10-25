package io.github.samlinb.openbone.core.model;

import java.util.*;

/** Straight-alpha color components in the texture's sRGB byte space. */
public record Rgba(double r, double g, double b, double a) {
    public static final Rgba WHITE = new Rgba(1, 1, 1, 1);
    public Rgba {
        for (double v : new double[]{r,g,b,a}) if (!Double.isFinite(v) || v < 0 || v > 1)
            throw new IllegalArgumentException("Color components must be finite and within [0,1]");
    }
    public static Rgba parse(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Expected RRGGBBAA color");
        return new Rgba(Integer.parseInt(hex.substring(0,2),16)/255., Integer.parseInt(hex.substring(2,4),16)/255.,
                Integer.parseInt(hex.substring(4,6),16)/255., Integer.parseInt(hex.substring(6,8),16)/255.);
    }
    public String hex() { return String.format(Locale.ROOT,"%02x%02x%02x%02x", Math.round(r*255),Math.round(g*255),Math.round(b*255),Math.round(a*255)); }
    public List<Double> values() { return List.of(r,g,b,a); }
    public static double clamp(double v) { return Math.max(0,Math.min(1,v)); }
}
