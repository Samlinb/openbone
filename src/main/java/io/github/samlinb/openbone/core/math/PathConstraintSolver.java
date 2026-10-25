package io.github.samlinb.openbone.core.math;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Bernstein evaluation of the version's 4-chord / 10-chord length convention.
 * This deliberately does not replace the convention with a more accurate
 * integral.
 */
public final class PathConstraintSolver {
    private static final double EPS = 1e-5;

    private PathConstraintSolver() {
    }

    public record Position(Vec2 point, double tangent) {
    }

    /**
     * Conservative whole-animation envelope. No time samples participate in this
     * proof.
     */
    public static Map<EntityId, double[]> bounds(PathConstraint c, PathAttachment path, double targetNorm,
            double targetPosition, Map<EntityId, double[]> world, List<Bone> bones) {
        double local = path.vertices().stream().mapToDouble(v -> Math.hypot(v.x(), v.y())).max().orElseThrow(),
                control = Finite.require(targetPosition + targetNorm * local, "path control envelope"),
                total = path.constantSpeed() ? Finite.require(16 * control * path.curveCount(), "path length envelope")
                        : path.lengths().get(path.curveCount() - 1),
                space = 0;
        for (var id : c.boneIds()) {
            var b = bones.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            var m = world.get(id);
            double s = switch (c.spacingMode()) {
                case PERCENT, PROPORTIONAL -> Math.abs(c.spacing()) * total * (c.boneIds().size() + 1);
                case FIXED -> Math.abs(c.spacing()) * Math.max(1, m[0]);
                case LENGTH -> (b.length() + Math.abs(c.spacing())) * Math.max(1, m[0]);
            };
            space = Math.max(space, Finite.require(s, "path spacing envelope"));
        }
        double position = Finite.require(
                Math.abs(c.position()) * (c.positionMode() == PathConstraint.PositionMode.PERCENT ? total : 1),
                "path position envelope"),
                pointBound = Finite.require(control + position + (c.boneIds().size() + 1) * space,
                        "extended path envelope"),
                current = pointBound;
        Map<EntityId, double[]> out = new HashMap<>();
        for (var id : c.boneIds()) {
            var old = world.get(id);
            double norm = old[0], pos = Finite.require(old[1] + current, "path solved position envelope");
            if (c.rotateMode() == PathConstraint.RotateMode.CHAIN_SCALE)
                norm = Finite.require(norm * (2 + (pointBound + current) / EPS), "path chain scale envelope");
            out.put(id, new double[] { norm, pos });
            if (c.rotateMode() == PathConstraint.RotateMode.CHAIN && c.rotation() == 0) {
                var bone = bones.stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
                current = Finite.require(2 * pointBound + current + bone.length() * old[0],
                        "path tip adjustment envelope");
            } else
                current = pointBound;
        }
        return out;
    }

    private static Vec2 point(List<Vec2> p, int curve, double t) {
        int i = curve * 3;
        double u = 1 - t;
        return new Vec2(
                p.get(i).x() * u * u * u + 3 * p.get(i + 1).x() * u * u * t + 3 * p.get(i + 2).x() * u * t * t
                        + p.get(i + 3).x() * t * t * t,
                p.get(i).y() * u * u * u + 3 * p.get(i + 1).y() * u * u * t + 3 * p.get(i + 2).y() * u * t * t
                        + p.get(i + 3).y() * t * t * t);
    }

    private static double tangent(List<Vec2> p, int curve, double t) {
        int i = curve * 3;
        if (t < .001)
            return Math.atan2(p.get(i + 1).y() - p.get(i).y(), p.get(i + 1).x() - p.get(i).x());
        double u = 1 - t;
        var q = point(p, curve, t);
        return Math.atan2(q.y() - (p.get(i).y() * u * u + 2 * p.get(i + 1).y() * u * t + p.get(i + 2).y() * t * t),
                q.x() - (p.get(i).x() * u * u + 2 * p.get(i + 1).x() * u * t + p.get(i + 2).x() * t * t));
    }

    private static double[] lengths(List<Vec2> p, int curve, int segments) {
        double[] out = new double[segments];
        var last = p.get(curve * 3);
        double sum = 0;
        for (int n = 1; n <= segments; n++) {
            var next = point(p, curve, (double) n / segments);
            sum = Finite.require(sum + Math.hypot(next.x() - last.x(), next.y() - last.y()), "path chord cumulative");
            out[n - 1] = sum;
            last = next;
        }
        return out;
    }

    public static double pathLength(PathAttachment path, List<Vec2> world) {
        var p = path.chain(world);
        if (!path.constantSpeed())
            return path.lengths().get(path.curveCount() - 1);
        double total = 0;
        for (int n = 0; n < path.curveCount(); n++)
            total = Finite.require(total + lengths(p, n, 4)[3], "path total length");
        return total;
    }

    public static Position position(PathAttachment path, List<Vec2> world, double distance) {
        var p = path.chain(world);
        int count = path.curveCount();
        double[] cumulative = new double[count];
        double total = 0;
        for (int n = 0; n < count; n++) {
            total = path.constantSpeed() ? Finite.require(total + lengths(p, n, 4)[3], "path cumulative length")
                    : path.lengths().get(n);
            cumulative[n] = total;
        }
        if (total == 0)
            return new Position(p.get(0), 0);
        double d = distance;
        if (path.closed()) {
            d %= total;
            if (d < 0)
                d += total;
        }
        if (d < 0 || d > total) {
            int i = d < 0 ? 0 : p.size() - 2;
            var start = p.get(d < 0 ? 0 : p.size() - 1);
            var a = p.get(i);
            var b = p.get(i + 1);
            double angle = Math.atan2(b.y() - a.y(), b.x() - a.x()), offset = d < 0 ? d : d - total;
            return new Position(new Vec2(start.x() + offset * Math.cos(angle), start.y() + offset * Math.sin(angle)),
                    angle);
        }
        int curve = 0;
        while (curve < count - 1 && d > cumulative[curve])
            curve++;
        double prior = curve == 0 ? 0 : cumulative[curve - 1], span = cumulative[curve] - prior,
                t = span == 0 ? 0 : (d - prior) / span;
        if (path.constantSpeed()) {
            var segments = lengths(p, curve, 10);
            double at = t * segments[9];
            int segment = 0;
            while (segment < 9 && at > segments[segment])
                segment++;
            double before = segment == 0 ? 0 : segments[segment - 1], length = segments[segment] - before;
            t = length == 0 ? segment * .1 : (segment + (at - before) / length) * .1;
        }
        if (t < EPS)
            return new Position(p.get(curve * 3), tangent(p, curve, 0));
        return new Position(point(p, curve, t), tangent(p, curve, t));
    }

    static void apply(PathConstraint c, List<Bone> bones, List<Slot> slots, List<Attachment> attachments,
            Map<EntityId, Affine2> world, Map<EntityId, Affine2> locked) {
        if (c.inactive())
            return;
        var slot = slots.stream().filter(s -> s.id().equals(c.targetId())).findFirst().orElseThrow();
        var attachment = attachments.stream().filter(a -> a.id().equals(slot.activeAttachmentId())).findFirst()
                .orElse(null);
        if (!(attachment instanceof PathAttachment path))
            return;
        var target = world.get(slot.boneId());
        var controls = path.vertices().stream().map(v -> target.apply(v.x(), v.y())).toList();
        double total = pathLength(path, controls);
        boolean tangents = c.rotateMode() == PathConstraint.RotateMode.TANGENT,
                scale = c.rotateMode() == PathConstraint.RotateMode.CHAIN_SCALE;
        int count = c.boneIds().size(), samples = tangents ? count : count + 1;
        double[] spaces = new double[samples], boneLengths = new double[count];
        Map<EntityId, Bone> byBone = new HashMap<>();
        bones.forEach(b -> byBone.put(b.id(), b));
        double sum = 0;
        for (int n = 1; n < samples; n++) {
            var id = c.boneIds().get(n - 1);
            var m = world.get(id);
            double setup = byBone.get(id).length(), len = Math.hypot(setup * m.a(), setup * m.c());
            boneLengths[n - 1] = len;
            spaces[n] = switch (c.spacingMode()) {
                case PERCENT -> c.spacing();
                case PROPORTIONAL -> setup < EPS ? c.spacing() : len;
                case FIXED,
                        LENGTH ->
                    setup < EPS ? c.spacing()
                            : ((c.spacingMode() == PathConstraint.SpacingMode.LENGTH ? setup : 0) + c.spacing()) * len
                                    / setup;
            };
            if (c.spacingMode() == PathConstraint.SpacingMode.PROPORTIONAL && setup >= EPS)
                sum += len;
        }
        if (c.spacingMode() == PathConstraint.SpacingMode.PROPORTIONAL && sum > 0)
            for (int n = 1; n < samples; n++)
                spaces[n] *= samples / sum * c.spacing();
        double multiplier = switch (c.spacingMode()) {
            case PERCENT -> total;
            case PROPORTIONAL -> total / samples;
            default -> 1;
        };
        Position[] positions = new Position[samples];
        double at = c.position() * (c.positionMode() == PathConstraint.PositionMode.PERCENT ? total : 1);
        for (int n = 0; n < samples; n++) {
            at = Finite.require(at + spaces[n] * multiplier, "path spacing position");
            positions[n] = position(path, controls, at);
        }
        var current = positions[0].point();
        double offset = Math.toRadians(c.rotation()) * (target.determinant() > 0 ? 1 : -1);
        boolean tip = c.rotation() == 0 && c.rotateMode() == PathConstraint.RotateMode.CHAIN;
        for (int n = 0; n < count; n++) {
            var id = c.boneIds().get(n);
            var m = world.get(id);
            double a = m.a(), b = m.b(), cc = m.c(), d = m.d(), x = m.tx() + (current.x() - m.tx()) * c.mixX(),
                    y = m.ty() + (current.y() - m.ty()) * c.mixY();
            var next = n + 1 < samples ? positions[n + 1].point() : new Vec2(0, 0);
            double dx = next.x() - current.x(), dy = next.y() - current.y();
            if (scale && boneLengths[n] >= EPS) {
                double s = 1 + (Math.hypot(dx, dy) / boneLengths[n] - 1) * c.mixRotate();
                a *= s;
                cc *= s;
            }
            if (c.mixRotate() > 0) {
                double angle = tangents ? positions[n].tangent()
                        : spaces[n + 1] < EPS ? positions[n + 1].tangent() : Math.atan2(dy, dx);
                double r = angle - Math.atan2(cc, a);
                if (tip) {
                    double co = Math.cos(r), si = Math.sin(r), length = byBone.get(id).length();
                    next = new Vec2(next.x() + (length * (co * a - si * cc) - dx) * c.mixRotate(),
                            next.y() + (length * (si * a + co * cc) - dy) * c.mixRotate());
                } else
                    r += offset;
                if (r > Math.PI)
                    r -= 2 * Math.PI;
                else if (r < -Math.PI)
                    r += 2 * Math.PI;
                r *= c.mixRotate();
                double co = Math.cos(r), si = Math.sin(r), na = co * a - si * cc, nb = co * b - si * d;
                cc = si * a + co * cc;
                d = si * b + co * d;
                a = na;
                b = nb;
            }
            locked.put(id, new Affine2(a, b, cc, d, x, y));
            current = next;
        }
    }
}
