package io.github.samlinb.openbone.core.math;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Independent double solver. Constraint output is evaluated before attachment
 * skinning.
 */
public final class ConstraintSolver {
    private ConstraintSolver() {
    }

    public static Map<EntityId, Affine2> evaluate(List<Bone> bones, Map<EntityId, Transform> pose,
            List<Constraint> constraints) {
        return evaluate(bones, pose, constraints, List.of(), List.of());
    }

    public static Map<EntityId, Affine2> evaluate(List<Bone> bones, Map<EntityId, Transform> pose,
            List<Constraint> constraints, List<Slot> slots, List<Attachment> attachments) {
        return evaluate(bones, pose, constraints, slots, attachments,
                new PhysicsFrame(PhysicsSolver.Mode.NONE, Map.of()));
    }

    public static Map<EntityId, Affine2> evaluate(List<Bone> bones, Map<EntityId, Transform> pose,
            List<Constraint> constraints, List<Slot> slots, List<Attachment> attachments, PhysicsFrame physics) {
        var local = new LinkedHashMap<EntityId, Transform>();
        Map<EntityId, Double> shear = new HashMap<>();
        Map<EntityId, Affine2> locked = new HashMap<>();
        for (var b : bones)
            local.put(b.id(), pose.getOrDefault(b.id(), b.local()));
        var world = new LinkedHashMap<EntityId, Affine2>();
        rebuild(bones, local, world, shear, locked);
        for (var c : constraints) {
            if (!c.enabled())
                continue;
            if (c instanceof IkConstraint ik) {
                if (ik.mix() == 0)
                    continue;
                var b = bones.stream().filter(v -> v.id().equals(ik.boneIds().get(0))).findFirst().orElseThrow();
                if (ik.boneIds().size() == 1)
                    single(b, local, world, ik);
                else
                    two(b, bones.stream().filter(v -> v.id().equals(ik.boneIds().get(1))).findFirst().orElseThrow(),
                            local, world, ik);
                if (ik.boneIds().size() == 2)
                    shear.put(b.id(), 0.0);
                rebuild(bones, local, world, shear, locked);
            } else if (c instanceof TransformConstraint transform) {
                TransformConstraintSolver.apply(transform, local, world, shear, locked);
                rebuild(bones, local, world, shear, locked);
            } else if (c instanceof PathConstraint path) {
                PathConstraintSolver.apply(path, bones, slots, attachments, world, locked);
                rebuild(bones, local, world, shear, locked);
            } else if (c instanceof PhysicsConstraint p && physics.mode() != PhysicsSolver.Mode.NONE) {
                var bone = bones.stream().filter(b -> b.id().equals(p.boneId())).findFirst().orElseThrow();
                var solved = PhysicsSolver.apply(p, world.get(p.boneId()), bone.length(), physics.state(p.id()),
                        physics.mode(), physics.tick());
                physics.put(p.id(), solved.state());
                locked.put(p.boneId(), solved.matrix());
                rebuild(bones, local, world, shear, locked);
            }
        }
        return world;
    }

    private record Angles(double parent, double child) {
    }

    /**
     * Solve the ellipse (L + ax*cos(theta), ay*sin(theta)) against a target radius.
     * The quadratic is in the endpoint X coordinate. Stable roots avoid
     * cancellation;
     * the minimum-magnitude admissible root selects the target bend branch.
     */
    private static Angles ellipse(double length, double ax, double ay, double tx, double ty, int bend) {
        double radius2 = Finite.require(tx * tx + ty * ty, "IK2 target radius square");
        double aa = Finite.require(ax * ax, "IK2 ellipse X square"),
                bb = Finite.require(ay * ay, "IK2 ellipse Y square");
        if (aa == bb)
            throw new IllegalArgumentException(m("constraintsolver.0001"));
        double q2 = bb - aa, q1 = -2 * bb * length,
                q0 = Finite.require(bb * length * length + aa * radius2 - aa * bb, "IK2 ellipse quadratic");
        double discriminant = Finite.require(q1 * q1 - 4 * q2 * q0, "IK2 ellipse discriminant");
        double direction = Math.atan2(ty, tx);
        if (discriminant >= 0 && q2 != 0) {
            double q = -.5 * (q1 + Math.copySign(Math.sqrt(discriminant), q1));
            double root0 = Finite.require(q / q2, "IK2 ellipse first root"), rx = root0;
            if (q != 0) {
                double root1 = Finite.require(q0 / q, "IK2 ellipse second root");
                if (Math.abs(root1) < Math.abs(root0))
                    rx = root1;
            }
            double y2 = radius2 - rx * rx;
            if (y2 >= 0) {
                double y = Math.sqrt(y2) * bend;
                return new Angles(direction - Math.atan2(y, rx), Math.atan2(y / ay, (rx - length) / ax));
            }
        }
        // Unreachable target: the extrema of the squared radial distance occur at
        // theta=0, pi, or cos(theta)=-ax*L/(ax^2-ay^2).
        double minAngle = Math.PI, minX = length - ax, minY = 0, minR = minX * minX;
        double maxAngle = 0, maxX = length + ax, maxY = 0, maxR = maxX * maxX;
        double extremum = -ax * length / (aa - bb);
        if (extremum >= -1 && extremum <= 1) {
            double theta = Math.acos(extremum), x = length + ax * Math.cos(theta), y = ay * Math.sin(theta),
                    r = x * x + y * y;
            if (r < minR) {
                minR = r;
                minAngle = theta;
                minX = x;
                minY = y;
            }
            if (r > maxR) {
                maxR = r;
                maxAngle = theta;
                maxX = x;
                maxY = y;
            }
        }
        return radius2 <= (minR + maxR) / 2
                ? new Angles(direction - Math.atan2(minY * bend, minX), minAngle * bend)
                : new Angles(direction - Math.atan2(maxY * bend, maxX), maxAngle * bend);
    }

    private static void two(Bone parent, Bone child, Map<EntityId, Transform> local, Map<EntityId, Affine2> world,
            IkConstraint ik) {
        var p = local.get(parent.id());
        var c = local.get(child.id());
        double psx = Math.abs(p.scaleX()), psy = Math.abs(p.scaleY()), csx = Math.abs(c.scaleX());
        int orientation = (p.scaleX() < 0 ? -1 : 1) * (p.scaleY() < 0 ? -1 : 1), bend = ik.bendPositive() ? 1 : -1;
        boolean uniform = Math.abs(psx - psy) <= .0001;
        double cy = !uniform || ik.stretch() ? 0 : c.y();
        var upstream = parent.parentId() == null ? Affine2.IDENTITY : world.get(parent.parentId());
        var target = world.get(ik.targetId());
        double det = Finite.require(upstream.determinant(), "IK2 upstream determinant");
        double tx = -p.x(), ty = -p.y();
        if (Math.abs(det) > .0001) {
            double x = target.tx() - upstream.tx(), y = target.ty() - upstream.ty();
            tx = Finite.require((x * upstream.d() - y * upstream.b()) / det - p.x(), "IK2 target X");
            ty = Finite.require((y * upstream.a() - x * upstream.c()) / det - p.y(), "IK2 target Y");
        }
        double length = Finite.require(
                Math.abs(det) <= .0001 ? Math.hypot(p.x(), p.y()) : Math.hypot(psx * c.x(), psy * cy),
                "IK2 first length"), second = Finite.require(child.length() * csx, "IK2 second length");
        if (length < .0001 || second < 1e-12 || psx < 1e-12 || psy < 1e-12) {
            // Engineering extension for target divisions by zero. Export proof rejects
            // the zero second segment/axis case rather than claiming runtime equality.
            single(parent, local, world, new IkConstraint(ik.id(), ik.name(), ik.order(), true, List.of(parent.id()),
                    ik.targetId(), ik.mix(), 0, ik.bendPositive(), false, ik.stretch(), false));
            local.put(child.id(), new Transform(c.x(), cy, 0, c.scaleX(), c.scaleY()));
            return;
        }
        double radius = Finite.require(Math.hypot(tx, ty), "IK2 target radius");
        if (ik.softness() != 0) {
            double soft = Finite.require(ik.softness() * psx * (csx + 1) / 2, "IK2 softness scale"),
                    over = radius - length - second * psx + soft;
            if (soft > 0 && over > 0 && radius >= 1e-12) {
                double u = over >= 2 * soft ? 0 : over / (2 * soft) - 1, factor = (over - soft * (1 - u * u)) / radius;
                tx -= factor * tx;
                ty -= factor * ty;
                radius = Finite.require(Math.hypot(tx, ty), "IK2 softened radius");
            }
        }
        double scaleX = p.scaleX(), scaleY = p.scaleY();
        Angles angles;
        if (uniform) {
            double reach = Finite.require(second * psx, "IK2 reach");
            double cos = Finite.require((radius * radius - length * length - reach * reach) / (2 * length * reach),
                    "IK2 cosine ratio");
            double theta;
            if (cos < -1) {
                cos = -1;
                theta = Math.PI * bend;
            } else if (cos > 1) {
                cos = 1;
                theta = 0;
                if (ik.stretch()) {
                    double factor = Finite.require(1 + (radius / (length + reach) - 1) * ik.mix(), "IK2 stretch");
                    scaleX *= factor;
                    if (ik.uniform())
                        scaleY *= factor;
                }
            } else
                theta = Math.acos(cos) * bend;
            double x = length + reach * cos, y = reach * Math.sin(theta);
            angles = new Angles(Math.atan2(ty * x - tx * y, tx * x + ty * y), theta);
        } else
            angles = ellipse(length, psx * second, psy * second, tx, ty, bend);
        double offset = Math.atan2(cy, c.x()) * orientation;
        double parentAngle = Math.toDegrees(angles.parent() - offset) + (p.scaleX() < 0 ? 180 : 0);
        double childAngle = Math.toDegrees(angles.child() + offset) * orientation + (c.scaleX() < 0 ? 180 : 0);
        local.put(parent.id(), new Transform(p.x(), p.y(), p.rotation() + delta(parentAngle, p.rotation()) * ik.mix(),
                scaleX, scaleY));
        local.put(child.id(), new Transform(c.x(), cy, c.rotation() + delta(childAngle, c.rotation()) * ik.mix(),
                c.scaleX(), c.scaleY()));
    }

    private static void rebuild(List<Bone> bones, Map<EntityId, Transform> local, Map<EntityId, Affine2> world,
            Map<EntityId, Double> shear, Map<EntityId, Affine2> locked) {
        for (var b : bones) {
            if (locked.containsKey(b.id())) {
                world.put(b.id(), locked.get(b.id()));
                continue;
            }
            var t = local.get(b.id());
            var matrix = t.matrix();
            double shy = shear.getOrDefault(b.id(), 0.0);
            if (shy != 0) {
                double angle = Math.toRadians((t.rotation() % 360 + 90 + shy % 360) % 360);
                matrix = new Affine2(matrix.a(), Math.cos(angle) * t.scaleY(), matrix.c(), Math.sin(angle) * t.scaleY(),
                        t.x(), t.y());
            }
            world.put(b.id(), b.parentId() == null ? matrix : world.get(b.parentId()).multiply(matrix));
        }
    }

    /**
     * The solve singularity gate is the target 4.2 absolute 1e-4 determinant,
     * separate
     * from the scale-relative edit inverse gate. At a singular parent the aim
     * vector is
     * zero, producing its deterministic local-zero direction. Root uses identity
     * parent.
     */
    private static void single(Bone bone, Map<EntityId, Transform> local, Map<EntityId, Affine2> world,
            IkConstraint ik) {
        Transform t = local.get(bone.id());
        var p = bone.parentId() == null ? Affine2.IDENTITY : world.get(bone.parentId());
        var target = world.get(ik.targetId());
        double determinant = Finite.require(p.a() * p.d() - p.b() * p.c(), "IK parent determinant");
        double dx = 0, dy = 0;
        if (Math.abs(determinant) > 0.0001) {
            double x = Finite.require(target.tx() - p.tx(), "IK target relative x"),
                    y = Finite.require(target.ty() - p.ty(), "IK target relative y");
            dx = Finite.require(Finite.require(x * p.d() - y * p.b(), "IK inverse numerator x") / determinant - t.x(),
                    "IK aim x");
            dy = Finite.require(Finite.require(y * p.a() - x * p.c(), "IK inverse numerator y") / determinant - t.y(),
                    "IK aim y");
        }
        double aim = Math.toDegrees(Math.atan2(dy, dx)) + (t.scaleX() < 0 ? 180 : 0);
        double rotation = t.rotation() + delta(aim, t.rotation()) * ik.mix();
        double sx = t.scaleX(), sy = t.scaleY();
        // A nonpositive signed length cannot be compressed/stretched in the target.
        double reach = Finite.require(bone.length() * sx, "IK signed reach");
        if ((ik.compress() || ik.stretch()) && reach > 0.0001) {
            double distance = Finite.require(Math.hypot(dx, dy), "IK distance");
            if (ik.compress() && distance < reach || ik.stretch() && distance > reach) {
                double factor = Finite.require((distance / reach - 1) * ik.mix() + 1, "IK scale factor");
                sx = Finite.require(sx * factor, "IK scaleX");
                if (ik.uniform())
                    sy = Finite.require(sy * factor, "IK scaleY");
            }
        }
        local.put(bone.id(), new Transform(t.x(), t.y(), rotation, sx, sy));
    }

    static double delta(double target, double current) {
        // 4.2 performs one wrap, not modulo normalization. Multiple-turn Setup
        // rotations therefore affect a partial mix; preserve that observable contract.
        double d = Finite.require(target - current, "IK angular difference");
        if (d > 180)
            d -= 360;
        else if (d < -180)
            d += 360;
        return d;
    }
}
