package io.github.samlinb.openbone.viewport;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

public final class Picker {
    private Picker() {
    }

    public static Optional<EntityId> region(DocumentSnapshot d, Vec2 world, ImageAssets assets) {
        return region(d, d.evaluateWorld(), world, assets);
    }

    public static Optional<EntityId> region(DocumentSnapshot d, WorldPose pose, Vec2 world, ImageAssets assets) {
        var draws = VisibleGeometry.evaluate(d, pose);
        for (int i = draws.size() - 1; i >= 0; i--) {
            var draw = draws.get(i);
            var attachment = d.attachment(draw.attachmentId());
            // Preserve the legacy region half-open right/bottom policy. Geometry and
            // page UV still come from exactly the same trimmed triangles as GL.
            if (attachment instanceof RegionAttachment r) {
                var inverse = pose.regions().get(r.id()).matrix().tryInverse();
                if (inverse.isEmpty())
                    continue;
                var p = inverse.get().apply(world.x(), world.y());
                double left = -r.width() / 2, top = r.height() / 2, width = r.width(), height = r.height();
                var region = d.resources().atlas().get(r.id());
                if (region != null) {
                    left += r.width() * ((double) region.offsetX() / region.originalWidth());
                    top = -r.height() / 2
                            + r.height() * ((double) (region.offsetY() + region.height()) / region.originalHeight());
                    width *= ((double) region.width() / region.originalWidth());
                    height *= ((double) region.height() / region.originalHeight());
                }
                double u = (p.x() - left) / width, v = (top - p.y()) / height;
                if (u < 0 || u >= 1 || v < 0 || v >= 1)
                    continue;
            }
            var g = draw.geometry();
            var image = assets.get(draw.source()).pixels();
            for (int t = g.triangles().size() - 3; t >= 0; t -= 3) {
                int a = g.triangles().get(t), b = g.triangles().get(t + 1), c = g.triangles().get(t + 2);
                var uv = triangleUv(world, g.vertices().get(a), g.vertices().get(b), g.vertices().get(c),
                        g.uvs().get(a), g.uvs().get(b), g.uvs().get(c));
                if (uv.isPresent() && opaque(image, uv.get()))
                    return Optional.of(draw.attachmentId());
            }
        }
        return Optional.empty();
    }

    /**
     * GDX textures use ClampToEdge. A legal UV of exactly 1 samples the last texel,
     * including when an interior mesh point has constant boundary UVs.
     */
    private static boolean opaque(java.awt.image.BufferedImage image, Vec2 uv) {
        return (image.getRGB(Math.min(image.getWidth() - 1, (int) Math.floor(uv.x() * image.getWidth())),
                Math.min(image.getHeight() - 1, (int) Math.floor(uv.y() * image.getHeight()))) >>> 24) > 16;
    }

    /**
     * Same evaluated triangle as GL; zero-area triangles are not pickable.
     * Normalize before
     * differences/products so finite huge geometry never overflows barycentric
     * arithmetic.
     */
    public static Optional<Vec2> triangleUv(Vec2 p, Vec2 a, Vec2 b, Vec2 c, Vec2 u, Vec2 v, Vec2 w) {
        if (!MeshTopology.insideTriangle(p, a, b, c, true) || MeshTopology.orient(a, b, c) == 0)
            return Optional.empty();
        double scale = Math.max(
                Math.max(Math.max(Math.abs(a.x()), Math.abs(a.y())), Math.max(Math.abs(b.x()), Math.abs(b.y()))),
                Math.max(Math.abs(c.x()), Math.abs(c.y())));
        if (scale == 0)
            return Optional.empty();
        double bx = b.x() / scale - a.x() / scale, by = b.y() / scale - a.y() / scale,
                cx = c.x() / scale - a.x() / scale, cy = c.y() / scale - a.y() / scale,
                px = p.x() / scale - a.x() / scale, py = p.y() / scale - a.y() / scale;
        double det = bx * cy - by * cx;
        if (det == 0 || !Double.isFinite(det))
            return Optional.empty();
        double q = (px * cy - py * cx) / det, r = (bx * py - by * px) / det, s = 1 - q - r;
        double x = s * u.x() + q * v.x() + r * w.x(), y = s * u.y() + q * v.y() + r * w.y();
        return Double.isFinite(x) && Double.isFinite(y)
                ? Optional.of(new Vec2(Math.max(0, Math.min(1, x)), Math.max(0, Math.min(1, y))))
                : Optional.empty();
    }

    public static Optional<EntityId> bone(DocumentSnapshot d, Vec2 screen, Camera2D camera, int w, int h) {
        return bone(d, d.evaluateWorld(), screen, camera, w, h);
    }

    public static Optional<EntityId> bone(DocumentSnapshot d, WorldPose pose, Vec2 screen, Camera2D camera, int w,
            int h) {
        for (int i = d.bones().size() - 1; i >= 0; i--) {
            var b = d.bones().get(i);
            var v = pose.bones().get(b.id());
            Vec2 p = camera.screen(v.origin(), w, h), q = camera.screen(v.tip(), w, h);
            if (distance(screen, p, q) <= 7)
                return Optional.of(b.id());
        }
        return Optional.empty();
    }

    public static double distance(Vec2 p, Vec2 a, Vec2 b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / len));
        return Math.hypot(p.x() - a.x() - t * dx, p.y() - a.y() - t * dy);
    }
}
