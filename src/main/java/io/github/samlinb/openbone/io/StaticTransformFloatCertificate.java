package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.Channel;
import io.github.samlinb.openbone.core.model.*;

/**
 * Compatibility certificate only: no renderer, sampler or product solver
 * consumes its
 * matrices. Static angular inputs permit an exact float-operation check,
 * including
 * atan2 signed zero and the target's single-wrap branch. Translation may
 * animate.
 */
final class StaticTransformFloatCertificate {
    private static final float PI = (float) Math.PI, DEG = PI / 180;

    private StaticTransformFloatCertificate() {
    }

    private static final class Unproved extends RuntimeException {
        Unproved(String text) {
            super(text);
        }
    }

    private static float f(float v) {
        if (!Float.isFinite(v))
            throw new Unproved("non-finite float angular intermediate");
        return v;
    }

    private record M(float a, float b, float c, float d) {
        M {
            f(a);
            f(b);
            f(c);
            f(d);
        }

        M child(M q) {
            return new M(a * q.a + b * q.c, a * q.b + b * q.d, c * q.a + d * q.c, c * q.b + d * q.d);
        }
    }

    private static float cos(float v) {
        return f((float) Math.cos(v));
    }

    private static float sin(float v) {
        return f((float) Math.sin(v));
    }

    private static float angle(float y, float x) {
        return f((float) Math.atan2(y, x));
    }

    private static float norm(float x, float y) {
        return f((float) Math.sqrt(f(x * x + y * y)));
    }

    private static float wrap(float a) {
        if (a > PI)
            a -= 2 * PI;
        else if (a < -PI)
            a += 2 * PI;
        return f(a);
    }

    private static M local(float[] p) {
        float x = f(p[0] * DEG), y = f((p[0] + 90 + p[3]) * DEG);
        return new M(cos(x) * p[1], cos(y) * p[2], sin(x) * p[1], sin(y) * p[2]);
    }

    private static void rebuild(List<Bone> bones, Map<EntityId, float[]> locals, Map<EntityId, M> locks,
            Map<EntityId, M> world) {
        for (var b : bones) {
            if (locks.containsKey(b.id())) {
                world.put(b.id(), locks.get(b.id()));
                continue;
            }
            var m = local(locals.get(b.id()));
            world.put(b.id(), b.parentId() == null ? m : world.get(b.parentId()).child(m));
        }
    }

    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        if (d.constraints().stream().noneMatch(c -> c instanceof TransformConstraint t && !t.inactive() && !t.local()
                && (t.mix().get(0) != 0 || t.mix().get(5) != 0)))
            return;
        if (d.animations().stream().flatMap(a -> a.timelines().stream())
                .anyMatch(t -> t.channel() != Channel.TRANSLATE))
            throw ProjectCodec.bad("transform.float-angular-certificate",
                    "World angular constraints with rotation/scale animation are not certified yet; translation animation and the lossless double project remain available");
        try {
            Map<EntityId, float[]> locals = new HashMap<>();
            for (var b : d.bones())
                locals.put(b.id(), new float[] { (float) b.local().rotation(), (float) b.local().scaleX(),
                        (float) b.local().scaleY(), 0 });
            Map<EntityId, M> locks = new HashMap<>(), world = new HashMap<>();
            Set<EntityId> unknown = new HashSet<>(), compare = new HashSet<>();
            rebuild(d.bones(), locals, locks, world);
            for (var constraint : d.constraints()) {
                if (!constraint.enabled())
                    continue;
                if (constraint instanceof IkConstraint ik) {
                    if (ik.mix() != 0) {
                        unknown.addAll(ik.boneIds());
                        for (var b : d.bones())
                            if (unknown.contains(b.parentId()))
                                unknown.add(b.id());
                    }
                    continue;
                }
                if (constraint instanceof PathConstraint p) {
                    if (!p.inactive()) {
                        unknown.addAll(p.boneIds());
                        for (var b : d.bones())
                            if (unknown.contains(b.parentId()))
                                unknown.add(b.id());
                    }
                    continue;
                }
                if (constraint instanceof PhysicsConstraint p) {
                    if (p.mix() != 0) {
                        unknown.add(p.boneId());
                        for (var b : d.bones())
                            if (unknown.contains(b.parentId()))
                                unknown.add(b.id());
                    }
                    continue;
                }
                var t = (TransformConstraint) constraint;
                if (t.inactive())
                    continue;
                if (unknown.contains(t.targetId()) || t.boneIds().stream().anyMatch(unknown::contains))
                    throw new Unproved("unproved preceding IK angular input");
                float[] mix = new float[6], off = new float[6];
                for (int n = 0; n < 6; n++) {
                    mix[n] = (float) (double) t.mix().get(n);
                    off[n] = (float) (double) t.offset().get(n);
                }
                if (t.local()) {
                    var target = locals.get(t.targetId());
                    for (var id : t.boneIds()) {
                        var p = locals.get(id);
                        float r = f(p[0] + (target[0] - (t.relative() ? 0 : p[0]) + off[0]) * mix[0]);
                        float sx = p[1], sy = p[2];
                        if (t.relative()) {
                            sx = f(sx * ((target[1] - 1 + off[3]) * mix[3] + 1));
                            sy = f(sy * ((target[2] - 1 + off[4]) * mix[4] + 1));
                        } else {
                            if (mix[3] != 0 && sx != 0)
                                sx = f((sx + (target[1] - sx + off[3]) * mix[3]) / sx);
                            if (mix[4] != 0 && sy != 0)
                                sy = f((sy + (target[2] - sy + off[4]) * mix[4]) / sy);
                        }
                        float shy = f(p[3] + (target[3] - (t.relative() ? 0 : p[3]) + off[5]) * mix[5]);
                        locals.put(id, new float[] { r, sx, sy, shy });
                    }
                } else {
                    var target = world.get(t.targetId());
                    float reflect = f(target.a * target.d - target.b * target.c) > 0 ? DEG : -DEG;
                    for (var id : t.boneIds()) {
                        var m = world.get(id);
                        float a = m.a, b = m.b, c = m.c, dd = m.d;
                        if (mix[0] != 0) {
                            float r = wrap(
                                    angle(target.c, target.a) - (t.relative() ? 0 : angle(c, a)) + off[0] * reflect)
                                    * mix[0], co = cos(r), si = sin(r), na = co * a - si * c, nb = co * b - si * dd;
                            c = si * a + co * c;
                            dd = si * b + co * dd;
                            a = na;
                            b = nb;
                        }
                        if (mix[3] != 0) {
                            float s = t.relative() ? (norm(target.a, target.c) - 1 + off[3]) * mix[3] + 1 : norm(a, c);
                            if (!t.relative() && s != 0)
                                s = (s + (norm(target.a, target.c) - s + off[3]) * mix[3]) / s;
                            f(s);
                            a *= s;
                            c *= s;
                        }
                        if (mix[4] != 0) {
                            float s = t.relative() ? (norm(target.b, target.d) - 1 + off[4]) * mix[4] + 1 : norm(b, dd);
                            if (!t.relative() && s != 0)
                                s = (s + (norm(target.b, target.d) - s + off[4]) * mix[4]) / s;
                            f(s);
                            b *= s;
                            dd *= s;
                        }
                        if (mix[5] > 0) {
                            float by = angle(dd, b), r;
                            if (t.relative())
                                r = by + (wrap(angle(target.d, target.b) - angle(target.c, target.a)) - PI / 2
                                        + off[5] * reflect) * mix[5];
                            else
                                r = by + (wrap(
                                        angle(target.d, target.b) - angle(target.c, target.a) - (by - angle(c, a)))
                                        + off[5] * reflect) * mix[5];
                            float length = norm(b, dd);
                            b = cos(r) * length;
                            dd = sin(r) * length;
                        }
                        locks.put(id, new M(a, b, c, dd));
                        compare.add(id);
                    }
                }
                rebuild(d.bones(), locals, locks, world);
            }
            var expected = d.evaluateWorld();
            for (var id : compare) {
                var m = world.get(id);
                var e = expected.bones().get(id).matrix();
                float[] actual = { m.a, m.b, m.c, m.d };
                double[] values = { e.a(), e.b(), e.c(), e.d() };
                for (int n = 0; n < 4; n++)
                    if (Math.abs(actual[n] - values[n]) > 1e-3 + 1e-5 * Math.abs(values[n]))
                        throw new Unproved("double/float angular branch differs at bone " + d.bone(id).name()
                                + ", component " + n);
                // Tiny scales must not hide a reversed axis behind an absolute matrix
                // tolerance. This is an additional normalized-direction requirement.
                for (int axis = 0; axis < 2; axis++) {
                    double af = Math.hypot(actual[axis], actual[axis + 2]),
                            ed = Math.hypot(values[axis], values[axis + 2]);
                    if ((af == 0) != (ed == 0))
                        throw new Unproved("double/float axis collapse differs");
                    if (af != 0 && (Math.abs(actual[axis] / af - values[axis] / ed) > 1e-5
                            || Math.abs(actual[axis + 2] / af - values[axis + 2] / ed) > 1e-5))
                        throw new Unproved("double/float normalized axis direction differs");
                }
            }
        } catch (Unproved e) {
            throw ProjectCodec.bad("transform.float-angular-certificate",
                    e.getMessage() + "; project remains editable and lossless");
        }
    }
}
