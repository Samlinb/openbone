package io.github.samlinb.openbone.viewport;

import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Coordinates are AWT logical pixels. Device scale belongs only to the GL
 * viewport.
 */
public final class Camera2D {
    private double x, y, zoom = 1;

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double zoom() {
        return zoom;
    }

    public Vec2 screen(Vec2 p, int width, int height) {
        // Legal finite model coordinates can overflow only after camera multiplication.
        // Keep
        // those far-offscreen points outside the viewport instead of crashing the GL
        // loop.
        return new Vec2(offscreenBound(width / 2.0 + zoom * (p.x() - x)),
                offscreenBound(height / 2.0 - zoom * (p.y() - y)));
    }

    public Vec2 world(double sx, double sy, int width, int height) {
        return new Vec2(x + (sx - width / 2.0) / zoom, y - (sy - height / 2.0) / zoom);
    }

    public void zoomAt(double value, double sx, double sy, int width, int height) {
        Finite.require(value, "zoom");
        Vec2 anchor = world(sx, sy, width, height);
        double next = Math.max(.05, Math.min(20, value));
        double nx = anchor.x() - (sx - width / 2.0) / next, ny = anchor.y() + (sy - height / 2.0) / next;
        Finite.require(nx, "camera.x");
        Finite.require(ny, "camera.y");
        zoom = next;
        x = nx;
        y = ny;
    }

    /** Explicit framing may zoom farther out than interactive wheel zoom. */
    public void fit(double minX,double minY,double maxX,double maxY,int width,int height,double occupancy) {
        for(double value:new double[]{minX,minY,maxX,maxY,occupancy})Finite.require(value,"camera bounds");
        if(minX>maxX||minY>maxY||width<1||height<1||occupancy<=0||occupancy>1)
            throw new IllegalArgumentException(m("camera.invalidFitBounds"));
        // Half extents avoid max-min overflow for finite, widely separated points.
        double cx=minX/2+maxX/2,cy=minY/2+maxY/2;
        double halfWidth=Math.max(.5,Math.max(Math.abs(minX-cx),Math.abs(maxX-cx)));
        double halfHeight=Math.max(.5,Math.max(Math.abs(minY-cy),Math.abs(maxY-cy)));
        double fitted=Math.min((width*.5*occupancy)/halfWidth,(height*.5*occupancy)/halfHeight);
        Finite.require(fitted,"camera fit zoom");
        if(fitted<=0)throw new IllegalArgumentException(m("camera.fitTooSmall"));
        x=cx;y=cy;zoom=Math.min(20,fitted);
    }

    public void pan(double dx, double dy) {
        double nx = x - dx / zoom, ny = y + dy / zoom;
        Finite.require(nx, "camera.x");
        Finite.require(ny, "camera.y");
        x = nx;
        y = ny;
    }

    public void reset() {
        x = 0;
        y = 0;
        zoom = 1;
    }

    private static double offscreenBound(double value) {
        return Math.max(-1e100, Math.min(1e100, value));
    }
}
