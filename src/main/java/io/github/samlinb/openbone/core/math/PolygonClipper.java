package io.github.samlinb.openbone.core.math;

import java.math.*;
import java.util.*;

/**
 * Independent triangle/convex intersection with UV interpolation. A concave
 * clip is a
 * disjoint union of setup triangles; no stencil/GL state can leak across slots
 * or frames.
 */
public final class PolygonClipper {
    private PolygonClipper() {
    }

    public record Geometry(List<Vec2> vertices, List<Vec2> uvs, List<Integer> triangles) {
        public Geometry {
            vertices = List.copyOf(vertices);
            uvs = List.copyOf(uvs);
            triangles = List.copyOf(triangles);
            if (uvs.size() != vertices.size() || triangles.size() % 3 != 0)
                throw new IllegalArgumentException("Invalid textured geometry layout");
            for (int i : triangles)
                if (i < 0 || i >= vertices.size())
                    throw new IllegalArgumentException("Textured geometry index out of range");
        }
    }

    private record Vertex(Vec2 point, Vec2 uv) {
    }

    private static double interpolate(double a, double b, double t) {
        if (a == b)
            return a;
        double v = (1 - t) * a + t * b;
        if (Double.isFinite(v))
            return Math.max(Math.min(a, b), Math.min(Math.max(a, b), v));
        var fraction = BigDecimal.valueOf(t);
        return Finite.require(BigDecimal.valueOf(a).multiply(BigDecimal.ONE.subtract(fraction))
                .add(BigDecimal.valueOf(b).multiply(fraction)).doubleValue(), "clip interpolation");
    }

    private static Vertex intersection(Vertex a, Vertex b, Vec2 c, Vec2 d) {
        var x = MeshTopology.cross(c, d, a.point());
        var y = MeshTopology.cross(c, d, b.point());
        var ratio = x.divide(x.subtract(y), MathContext.DECIMAL128);
        double t = Math.max(0, Math.min(1, ratio.doubleValue()));
        double px = c.x() == d.x() ? c.x() : coordinate(a.point().x(), b.point().x(), x, y),
                py = c.y() == d.y() ? c.y() : coordinate(a.point().y(), b.point().y(), x, y);
        return new Vertex(new Vec2(px, py),
                new Vec2(interpolate(a.uv().x(), b.uv().x(), t), interpolate(a.uv().y(), b.uv().y(), t)));
    }

    private static double coordinate(double a, double b, BigDecimal distanceA, BigDecimal distanceB) {
        if (a == b)
            return a;
        double v = BigDecimal.valueOf(b).multiply(distanceA).subtract(BigDecimal.valueOf(a).multiply(distanceB))
                .divide(distanceA.subtract(distanceB), MathContext.DECIMAL128).doubleValue();
        return Finite.require(Math.max(Math.min(a, b), Math.min(Math.max(a, b), v)), "clip coordinate");
    }

    public static Geometry clip(Geometry subject, List<Vec2> boundary, List<Integer> clipTriangles) {
        if (clipTriangles.size() % 3 != 0)
            throw new IllegalArgumentException("Invalid clip triangle count");
        for (int index : clipTriangles)
            if (index < 0 || index >= boundary.size())
                throw new IllegalArgumentException("Clip triangle index out of range");
        List<Vec2> vertices = new ArrayList<>(), uvs = new ArrayList<>();
        List<Integer> triangles = new ArrayList<>();
        for (int i = 0; i < subject.triangles().size(); i += 3)
            for (int j = 0; j < clipTriangles.size(); j += 3) {
                Vec2 a = boundary.get(clipTriangles.get(j)), b = boundary.get(clipTriangles.get(j + 1)),
                        c = boundary.get(clipTriangles.get(j + 2));
                int sign = MeshTopology.orient(a, b, c);
                if (sign == 0)
                    continue;
                List<Vertex> polygon = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    int v = subject.triangles().get(i + k);
                    polygon.add(new Vertex(subject.vertices().get(v), subject.uvs().get(v)));
                }
                double minX = polygon.stream().mapToDouble(v -> v.point().x()).min().orElseThrow(),
                        maxX = polygon.stream().mapToDouble(v -> v.point().x()).max().orElseThrow(),
                        minY = polygon.stream().mapToDouble(v -> v.point().y()).min().orElseThrow(),
                        maxY = polygon.stream().mapToDouble(v -> v.point().y()).max().orElseThrow();
                if (Math.max(a.x(), Math.max(b.x(), c.x())) < minX || Math.min(a.x(), Math.min(b.x(), c.x())) > maxX
                        || Math.max(a.y(), Math.max(b.y(), c.y())) < minY
                        || Math.min(a.y(), Math.min(b.y(), c.y())) > maxY)
                    continue;
                var clip = List.of(a, b, c);
                for (int edge = 0; edge < 3 && !polygon.isEmpty(); edge++) {
                    var start = clip.get(edge);
                    var end = clip.get((edge + 1) % 3);
                    var output = new ArrayList<Vertex>();
                    var previous = polygon.get(polygon.size() - 1);
                    boolean previousInside = MeshTopology.orient(start, end, previous.point()) * sign >= 0;
                    for (var current : polygon) {
                        boolean inside = MeshTopology.orient(start, end, current.point()) * sign >= 0;
                        if (inside != previousInside)
                            output.add(intersection(previous, current, start, end));
                        if (inside)
                            output.add(current);
                        previous = current;
                        previousInside = inside;
                    }
                    polygon = output;
                }
                if (polygon.size() < 3)
                    continue;
                int base = vertices.size();
                for (var v : polygon) {
                    vertices.add(v.point());
                    uvs.add(v.uv());
                }
                for (int k = 1; k + 1 < polygon.size(); k++)
                    if (MeshTopology.orient(polygon.get(0).point(), polygon.get(k).point(),
                            polygon.get(k + 1).point()) != 0)
                        triangles.addAll(List.of(base, base + k, base + k + 1));
            }
        return new Geometry(vertices, uvs, triangles);
    }
}
