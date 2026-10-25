package io.github.samlinb.openbone.core.math;

import java.math.BigDecimal;
import java.util.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Simple boundary (first hull vertices) plus optional strictly interior points.
 * Exact predicates use the decimal representation of finite doubles: neither
 * tiny geometry
 * nor huge coordinates may silently turn an orientation into zero/Infinity. No
 * GDX types.
 * There are no holes. Invalid edits throw before a snapshot/history can be
 * published.
 */
public final class MeshTopology {
    public static final int MAX_VERTICES = 512;

    private MeshTopology() {
    }

    private static BigDecimal b(double v) {
        return BigDecimal.valueOf(v);
    }

    public static BigDecimal cross(Vec2 a, Vec2 c, Vec2 d) {
        return b(c.x()).subtract(b(a.x())).multiply(b(d.y()).subtract(b(a.y())))
                .subtract(b(c.y()).subtract(b(a.y())).multiply(b(d.x()).subtract(b(a.x()))));
    }

    public static int orient(Vec2 a, Vec2 b, Vec2 c) {
        return cross(a, b, c).signum();
    }

    private static boolean between(double p, double a, double b) {
        return p >= Math.min(a, b) && p <= Math.max(a, b);
    }

    public static boolean onSegment(Vec2 p, Vec2 a, Vec2 b) {
        return orient(a, b, p) == 0 && between(p.x(), a.x(), b.x()) && between(p.y(), a.y(), b.y());
    }

    private static boolean intersects(Vec2 a, Vec2 b, Vec2 c, Vec2 d) {
        int x = orient(a, b, c), y = orient(a, b, d), z = orient(c, d, a), w = orient(c, d, b);
        return x * y < 0 && z * w < 0 || x == 0 && onSegment(c, a, b) || y == 0 && onSegment(d, a, b)
                || z == 0 && onSegment(a, c, d) || w == 0 && onSegment(b, c, d);
    }

    public static boolean insideTriangle(Vec2 p, Vec2 a, Vec2 b, Vec2 c, boolean inclusive) {
        int x = orient(a, b, p), y = orient(b, c, p), z = orient(c, a, p);
        return inclusive ? (x >= 0 && y >= 0 && z >= 0 || x <= 0 && y <= 0 && z <= 0)
                : (x > 0 && y > 0 && z > 0 || x < 0 && y < 0 && z < 0);
    }

    /** Boundary-inclusive winding parity, independent of orientation. */
    public static boolean contains(List<Vec2> v, int hull, Vec2 p, boolean inclusive) {
        boolean in = false;
        for (int i = 0, j = hull - 1; i < hull; j = i++) {
            var a = v.get(j);
            var c = v.get(i);
            if (onSegment(p, a, c))
                return inclusive;
            if ((a.y() > p.y()) != (c.y() > p.y())) {
                int side = orient(a, c, p);
                if ((c.y() > a.y() && side > 0) || (c.y() < a.y() && side < 0))
                    in = !in;
            }
        }
        return in;
    }

    private static BigDecimal area(List<Vec2> v, int hull) {
        BigDecimal a = BigDecimal.ZERO;
        for (int i = 0; i < hull; i++) {
            var p = v.get(i);
            var q = v.get((i + 1) % hull);
            a = a.add(b(p.x()).multiply(b(q.y())).subtract(b(p.y()).multiply(b(q.x()))));
        }
        return a;
    }

    public static void boundary(List<Vec2> v, int hull) {
        if (v.size() < 3 || v.size() > MAX_VERTICES || hull < 3 || hull > v.size())
            throw new IllegalArgumentException(m("meshtopology.0001"));
        for (int i = 0; i < v.size(); i++)
            for (int j = 0; j < i; j++)
                if (v.get(i).x() == v.get(j).x() && v.get(i).y() == v.get(j).y())
                    throw new IllegalArgumentException(m("meshtopology.0002") + i + ", " + j);
        for (int i = 0; i < hull; i++) {
            if (orient(v.get((i + hull - 1) % hull), v.get(i), v.get((i + 1) % hull)) == 0)
                throw new IllegalArgumentException(m("meshtopology.0003") + i);
            for (int j = i + 1; j < hull; j++) {
                if (j == i + 1 || i == 0 && j == hull - 1)
                    continue;
                if (intersects(v.get(i), v.get((i + 1) % hull), v.get(j), v.get((j + 1) % hull)))
                    throw new IllegalArgumentException(m("meshtopology.0004"));
            }
        }
        if (area(v, hull).signum() == 0)
            throw new IllegalArgumentException(m("meshtopology.0005"));
        for (int i = hull; i < v.size(); i++)
            if (!contains(v, hull, v.get(i), false))
                throw new IllegalArgumentException(m("meshtopology.0006") + i);
    }

    public static List<Integer> triangulate(List<Vec2> v, int hull) {
        boundary(v, hull);
        int sign = area(v, hull).signum();
        List<Integer> ring = new ArrayList<>(), out = new ArrayList<>();
        for (int i = 0; i < hull; i++)
            ring.add(i);
        while (ring.size() > 3) {
            boolean found = false;
            for (int i = 0; i < ring.size(); i++) {
                int a = ring.get((i + ring.size() - 1) % ring.size()), c = ring.get(i),
                        d = ring.get((i + 1) % ring.size());
                if (orient(v.get(a), v.get(c), v.get(d)) != sign)
                    continue;
                boolean blocked = false;
                for (int p : ring)
                    if (p != a && p != c && p != d && insideTriangle(v.get(p), v.get(a), v.get(c), v.get(d), true)) {
                        blocked = true;
                        break;
                    }
                if (blocked)
                    continue;
                out.addAll(List.of(a, c, d));
                ring.remove(i);
                found = true;
                break;
            }
            if (!found)
                throw new IllegalArgumentException(m("meshtopology.0007"));
        }
        out.addAll(ring);
        // Insert interior points by splitting every incident triangle, including on
        // diagonals.
        for (int p = hull; p < v.size(); p++) {
            List<Integer> next = new ArrayList<>();
            boolean found = false;
            for (int i = 0; i < out.size(); i += 3) {
                int a = out.get(i), c = out.get(i + 1), d = out.get(i + 2);
                if (!insideTriangle(v.get(p), v.get(a), v.get(c), v.get(d), true)) {
                    next.addAll(List.of(a, c, d));
                    continue;
                }
                found = true;
                for (var e : List.of(List.of(a, c), List.of(c, d), List.of(d, a)))
                    if (orient(v.get(e.get(0)), v.get(e.get(1)), v.get(p)) != 0)
                        next.addAll(List.of(e.get(0), e.get(1), p));
            }
            if (!found)
                throw new IllegalArgumentException(m("meshtopology.0008"));
            out = next;
        }
        validate(v, hull, out);
        return List.copyOf(out);
    }

    private record Edge(int a, int b) {
    }

    /**
     * Recover an editor-only planar certificate for a weighted runtime mesh. The
     * runtime
     * stores only bone-local influences, so a collapsed Setup skin cannot recover a
     * rest
     * polygon by inversion. Fix the boundary on a clockwise circle and solve the
     * interior
     * graph Laplacian. This certificate NEVER supplies rendered or skinned
     * positions.
     */
    public static List<Vec2> certificate(int count, int hull, List<Integer> triangles) {
        if (count < 3 || count > MAX_VERTICES || hull < 3 || hull > count
                || triangles.size() != 3 * (2 * count - hull - 2))
            throw new IllegalArgumentException(m("meshtopology.0009"));
        List<Set<Integer>> adjacent = new ArrayList<>();
        for (int i = 0; i < count; i++)
            adjacent.add(new HashSet<>());
        for (int p : triangles)
            if (p < 0 || p >= count)
                throw new IllegalArgumentException(m("meshtopology.0010"));
        for (int i = 0; i < triangles.size(); i += 3)
            for (int j = 0; j < 3; j++) {
                int a = triangles.get(i + j), b = triangles.get(i + (j + 1) % 3);
                adjacent.get(a).add(b);
                adjacent.get(b).add(a);
            }
        List<Vec2> result = new ArrayList<>();
        for (int i = 0; i < hull; i++)
            result.add(new Vec2(100 * Math.cos(-2 * Math.PI * i / hull), 100 * Math.sin(-2 * Math.PI * i / hull)));
        int n = count - hull;
        double[][] matrix = new double[n][n + 2];
        for (int i = 0; i < n; i++) {
            var neighbors = adjacent.get(hull + i);
            matrix[i][i] = neighbors.size();
            for (int j : neighbors)
                if (j < hull) {
                    matrix[i][n] += result.get(j).x();
                    matrix[i][n + 1] += result.get(j).y();
                } else
                    matrix[i][j - hull] -= 1;
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++)
                if (Math.abs(matrix[r][c]) > Math.abs(matrix[pivot][c]))
                    pivot = r;
            if (Math.abs(matrix[pivot][c]) < 1e-12)
                throw new IllegalArgumentException(m("meshtopology.0011"));
            var swap = matrix[c];
            matrix[c] = matrix[pivot];
            matrix[pivot] = swap;
            double scale = matrix[c][c];
            for (int k = c; k < n + 2; k++)
                matrix[c][k] /= scale;
            for (int r = 0; r < n; r++)
                if (r != c) {
                    double factor = matrix[r][c];
                    for (int k = c; k < n + 2; k++)
                        matrix[r][k] -= factor * matrix[c][k];
                }
        }
        for (int i = 0; i < n; i++)
            result.add(new Vec2(matrix[i][n], matrix[i][n + 1]));
        validate(result, hull, triangles);
        return List.copyOf(result);
    }

    public static void validate(List<Vec2> v, int hull, List<Integer> triangles) {
        boundary(v, hull);
        if (triangles.size() != 3 * (2 * v.size() - hull - 2))
            throw new IllegalArgumentException(m("meshtopology.0012"));
        int sign = area(v, hull).signum();
        BigDecimal sum = BigDecimal.ZERO;
        Map<Edge, Integer> edges = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < triangles.size(); i += 3) {
            int[] t = { triangles.get(i), triangles.get(i + 1), triangles.get(i + 2) };
            for (int p : t) {
                if (p < 0 || p >= v.size())
                    throw new IllegalArgumentException(m("meshtopology.0013") + p);
                used.add(p);
            }
            var a = cross(v.get(t[0]), v.get(t[1]), v.get(t[2]));
            if (a.signum() != sign)
                throw new IllegalArgumentException(m("meshtopology.0014"));
            sum = sum.add(a);
            for (int j = 0; j < 3; j++) {
                var e = new Edge(t[j], t[(j + 1) % 3]);
                if (edges.merge(e, 1, Integer::sum) > 1)
                    throw new IllegalArgumentException(m("meshtopology.0015"));
            }
        }
        if (used.size() != v.size() || sum.compareTo(area(v, hull)) != 0)
            throw new IllegalArgumentException(m("meshtopology.0016"));
        for (var e : edges.keySet()) {
            boolean border = e.a < hull && e.b == (e.a + 1) % hull;
            if (border) {
                if (edges.containsKey(new Edge(e.b, e.a)))
                    throw new IllegalArgumentException(m("meshtopology.0017"));
            } else if (!edges.containsKey(new Edge(e.b, e.a)))
                throw new IllegalArgumentException(m("meshtopology.0018"));
        }
        for (int i = 0; i < hull; i++)
            if (!edges.containsKey(new Edge(i, (i + 1) % hull)))
                throw new IllegalArgumentException(m("meshtopology.0019"));
        var es = new ArrayList<>(edges.keySet());
        for (var e : es)
            for (int i = 0; i < v.size(); i++)
                if (i != e.a && i != e.b && onSegment(v.get(i), v.get(e.a), v.get(e.b)))
                    throw new IllegalArgumentException(m("meshtopology.0020"));
        for (int i = 0; i < es.size(); i++)
            for (int j = 0; j < i; j++) {
                var e = es.get(i);
                var f = es.get(j);
                if (e.a == f.a || e.a == f.b || e.b == f.a || e.b == f.b)
                    continue;
                if (intersects(v.get(e.a), v.get(e.b), v.get(f.a), v.get(f.b)))
                    throw new IllegalArgumentException(m("meshtopology.0021"));
            }
    }
}
