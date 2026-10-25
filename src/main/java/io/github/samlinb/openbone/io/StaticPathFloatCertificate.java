package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * IO-only static compatibility audit. Its float matrices never enter the
 * product pose.
 * Chord lengths use polynomial forward differences; the double solver uses
 * Bernstein samples.
 */
final class StaticPathFloatCertificate {
    private StaticPathFloatCertificate() {
    }

    private record P(float x, float y) {
    }

    private record M(float a, float b, float c, float d, float x, float y) {
        P point(P p) {
            return new P(a * p.x + b * p.y + x, c * p.x + d * p.y + y);
        }

        M child(M q) {
            var p = point(new P(q.x, q.y));
            return new M(a * q.a + b * q.c, a * q.b + b * q.d, c * q.a + d * q.c, c * q.b + d * q.d, p.x, p.y);
        }
    }

    private static final float PI = (float) Math.PI, DEG = PI / 180;

    private static float cos(float r) {
        return (float) Math.cos(r);
    }

    private static float sin(float r) {
        return (float) Math.sin(r);
    }

    private static float angle(float y, float x) {
        return (float) Math.atan2(y, x);
    }

    private static float norm(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static float wrap(float a) {
        if (a > PI)
            a -= 2 * PI;
        else if (a < -PI)
            a += 2 * PI;
        return a;
    }

    private static P bezier(List<P> p, int curve, float t) {
        int i = curve * 3;
        float u = 1 - t, tt = t * t, uuu = u * u * u, ut3 = u * t * 3, uut = u * ut3, utt = ut3 * t, ttt = tt * t;
        return new P(p.get(i).x * uuu + p.get(i + 1).x * uut + p.get(i + 2).x * utt + p.get(i + 3).x * ttt,
                p.get(i).y * uuu + p.get(i + 1).y * uut + p.get(i + 2).y * utt + p.get(i + 3).y * ttt);
    }

    private static float[] chords(List<P> p, int curve, int n) {
        // Cubic polynomial forward differences. The powers of h are fixed, never
        // integrated.
        int k = curve * 3;
        var p0 = p.get(k);
        var p1 = p.get(k + 1);
        var p2 = p.get(k + 2);
        var p3 = p.get(k + 3);
        // For h=1/n, Δ1=3h(P1-P0)+3h²(P0-2P1+P2)+h³(-P0+3P1-3P2+P3).
        // Evaluate rational coefficients directly in float, without a rounded h*h*h.
        float quadratic = 3f / (n * n), cubic = 6f / (n * n * n), linear = 3f / n;
        float qx = (p0.x - p1.x * 2 + p2.x) * quadratic, qy = (p0.y - p1.y * 2 + p2.y) * quadratic,
                dddx = ((p1.x - p2.x) * 3 - p0.x + p3.x) * cubic, dddy = ((p1.y - p2.y) * 3 - p0.y + p3.y) * cubic;
        float dx = (p1.x - p0.x) * linear + qx + dddx * (1f / 6), dy = (p1.y - p0.y) * linear + qy + dddy * (1f / 6),
                ddx = qx * 2 + dddx, ddy = qy * 2 + dddy, sum = 0;
        var result = new float[n];
        for (int i = 0; i < n; i++) {
            sum += norm(dx, dy);
            result[i] = sum;
            dx += ddx;
            dy += ddy;
            ddx += dddx;
            ddy += dddy;
        }
        return result;
    }

    private static float[] at(PathAttachment path, List<P> p, float[] cumulative, float distance, boolean tangent) {
        float total = cumulative[cumulative.length - 1], v = distance;
        if (path.closed()) {
            v %= total;
            if (v < 0)
                v += total;
        }
        if (v < 0 || v > total) {
            int i = v < 0 ? 0 : p.size() - 2;
            var first = p.get(i);
            var last = p.get(i + 1);
            float a = angle(last.y - first.y, last.x - first.x), d = v < 0 ? v : v - total;
            var start = p.get(v < 0 ? 0 : p.size() - 1);
            return new float[] { start.x + d * cos(a), start.y + d * sin(a), a };
        }
        int curve = 0;
        while (curve < cumulative.length - 1 && v > cumulative[curve])
            curve++;
        float before = curve == 0 ? 0 : cumulative[curve - 1], t = (v - before) / (cumulative[curve] - before);
        if (path.constantSpeed()) {
            var s = chords(p, curve, 10);
            float d = t * s[9];
            int segment = 0;
            while (segment < 9 && d > s[segment])
                segment++;
            float a = segment == 0 ? 0 : s[segment - 1];
            t = (segment + (d - a) / (s[segment] - a)) * .1f;
        }
        int k = curve * 3;
        var q = t < .00001f ? p.get(k) : bezier(p, curve, t);
        float a = 0;
        if (tangent || t < .00001f) {
            if (t < .001f)
                a = angle(p.get(k + 1).y - p.get(k).y, p.get(k + 1).x - p.get(k).x);
            else {
                float u = 1 - t;
                var p0 = p.get(k);
                var p1 = p.get(k + 1);
                var p2 = p.get(k + 2);
                a = angle(q.y - (p0.y * u * u + p1.y * u * t * 2 + p2.y * t * t),
                        q.x - (p0.x * u * u + p1.x * u * t * 2 + p2.x * t * t));
            }
        }
        return new float[] { q.x, q.y, a };
    }

    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        if (d.constraints().stream().noneMatch(c -> c instanceof PathConstraint p && !p.inactive()))
            return;
        if (d.animations().stream().anyMatch(a -> !a.timelines().isEmpty()))
            throw ProjectCodec.bad("path.float-certificate.animation",
                    "Path animation matrix proof is pending; project retains all tracks");
        var matrices = new HashMap<EntityId, M>();
        for (var b : d.bones()) {
            var t = b.local();
            float r = (float) t.rotation() * DEG;
            var m = new M(cos(r) * (float) t.scaleX(), cos(((float) t.rotation() + 90) * DEG) * (float) t.scaleY(),
                    sin(r) * (float) t.scaleX(), sin(((float) t.rotation() + 90) * DEG) * (float) t.scaleY(),
                    (float) t.x(), (float) t.y());
            matrices.put(b.id(), b.parentId() == null ? m : matrices.get(b.parentId()).child(m));
        }
        var expected = d.evaluateWorld();
        for (var c : d.constraints())
            if (c instanceof PathConstraint pc && !pc.inactive()) {
                var slot = d.slots().stream().filter(s -> s.id().equals(pc.targetId())).findFirst().orElseThrow();
                var attachment = d.attachments().stream().filter(a -> a.id().equals(slot.activeAttachmentId()))
                        .findFirst().orElse(null);
                if (!(attachment instanceof PathAttachment path))
                    continue;
                var inputs = new ArrayList<EntityId>();
                inputs.add(slot.boneId());
                inputs.addAll(pc.boneIds());
                for (var id : inputs)
                    if (ClipFloatCertificate.staticHandedness(d, id, pc.id()).isEmpty())
                        throw ProjectCodec.bad("path.float-certificate.inputs",
                                "Static, nondegenerate linear inputs without preceding constraint writers are required");
                var target = matrices.get(slot.boneId());
                var points = path.vertices().stream().map(v -> target.point(new P((float) v.x(), (float) v.y())))
                        .toList();
                var chain = new ArrayList<>(points.subList(1, path.closed() ? points.size() : points.size() - 1));
                if (path.closed()) {
                    chain.add(points.get(0));
                    chain.add(points.get(1));
                }
                float max = 0;
                for (var p : chain) {
                    max = Math.max(max, Math.max(Math.abs(p.x), Math.abs(p.y)));
                    if (!Float.isFinite(max) || max > 1e6)
                        throw ProjectCodec.bad("path.float-certificate.controls",
                                "World path coordinates exceed certified static range");
                }
                float margin = Math.max(1e-6f, 1024 * Math.ulp(max));
                var cumulative = new float[path.curveCount()];
                float total = 0;
                for (int n = 0; n < path.curveCount(); n++) {
                    for (int steps : new int[] { 4, 10 }) {
                        float previous = 0;
                        for (float v : chords(chain, n, steps)) {
                            if (!Float.isFinite(v) || v - previous <= margin)
                                throw ProjectCodec.bad("path.float-certificate.lengths",
                                        "Degenerate or insufficiently separated chord lengths; lossless project remains editable");
                            previous = v;
                        }
                    }
                    float value = path.constantSpeed() ? total + chords(chain, n, 4)[3]
                            : (float) (double) path.lengths().get(n);
                    if (!Float.isFinite(value) || value - total <= margin)
                        throw ProjectCodec.bad("path.float-certificate.lengths",
                                "Cumulative lengths collapse in float");
                    cumulative[n] = value;
                    total = value;
                }
                int count = pc.boneIds().size(),
                        samples = pc.rotateMode() == PathConstraint.RotateMode.TANGENT ? count : count + 1;
                float[] spaces = new float[samples], lens = new float[count];
                float sum = 0, spacing = (float) pc.spacing();
                for (int i = 1; i < samples; i++) {
                    var id = pc.boneIds().get(i - 1);
                    var m = matrices.get(id);
                    float setup = (float) d.bone(id).length(), length = norm(setup * m.a, setup * m.c);
                    lens[i - 1] = length;
                    spaces[i] = switch (pc.spacingMode()) {
                        case PERCENT -> spacing;
                        case PROPORTIONAL -> setup < .00001f ? spacing : length;
                        case FIXED, LENGTH -> setup < .00001f ? spacing
                                : ((pc.spacingMode() == PathConstraint.SpacingMode.LENGTH ? setup : 0) + spacing)
                                        * length / setup;
                    };
                    if (spaces[i] < 0)
                        throw ProjectCodec.bad("path.float-certificate.spacing",
                                "Backward spacing cache semantics are not certified");
                    if (pc.spacingMode() == PathConstraint.SpacingMode.PROPORTIONAL && setup >= .00001f)
                        sum += length;
                }
                if (pc.spacingMode() == PathConstraint.SpacingMode.PROPORTIONAL && sum > 0)
                    for (int i = 1; i < samples; i++)
                        spaces[i] *= samples / sum * spacing;
                float multiplier = switch (pc.spacingMode()) {
                    case PERCENT -> total;
                    case PROPORTIONAL -> total / samples;
                    default -> 1;
                }, position = (float) pc.position()
                        * (pc.positionMode() == PathConstraint.PositionMode.PERCENT ? total : 1);
                float[][] positions = new float[samples][];
                for (int i = 0; i < samples; i++) {
                    float space = spaces[i] * multiplier;
                    position += space;
                    positions[i] = at(path, chain, cumulative, position,
                            pc.rotateMode() == PathConstraint.RotateMode.TANGENT || i > 0 && space < .00001f);
                }
                float x = positions[0][0], y = positions[0][1], mix = (float) pc.mixRotate(),
                        off = (float) pc.rotation() * (target.a * target.d - target.b * target.c > 0 ? DEG : -DEG);
                for (int i = 0; i < count; i++) {
                    var id = pc.boneIds().get(i);
                    var m = matrices.get(id);
                    float a = m.a, b = m.b, cc = m.c, dd = m.d, wx = m.x + (x - m.x) * (float) pc.mixX(),
                            wy = m.y + (y - m.y) * (float) pc.mixY(), nx = i + 1 < samples ? positions[i + 1][0] : 0,
                            ny = i + 1 < samples ? positions[i + 1][1] : 0, dx = nx - x, dy = ny - y;
                    if (pc.rotateMode() == PathConstraint.RotateMode.CHAIN_SCALE && lens[i] >= .00001f) {
                        float s = (norm(dx, dy) / lens[i] - 1) * mix + 1;
                        a *= s;
                        cc *= s;
                    }
                    if (mix > 0) {
                        float r = pc.rotateMode() == PathConstraint.RotateMode.TANGENT ? positions[i][2]
                                : spaces[i + 1] < .00001f ? positions[i + 1][2] : angle(dy, dx);
                        r -= angle(cc, a);
                        if (pc.rotateMode() == PathConstraint.RotateMode.CHAIN && pc.rotation() == 0) {
                            float co = cos(r), si = sin(r), length = (float) d.bone(id).length();
                            nx += (length * (co * a - si * cc) - dx) * mix;
                            ny += (length * (si * a + co * cc) - dy) * mix;
                        } else
                            r += off;
                        r = wrap(r);
                        if (mix < 1 && Math.abs(Math.abs(r) - PI) < .001)
                            throw ProjectCodec.bad("path.float-certificate.rotation",
                                    "Partial mix at ambiguous ±pi boundary");
                        r *= mix;
                        float co = cos(r), si = sin(r), na = co * a - si * cc, nb = co * b - si * dd;
                        cc = si * a + co * cc;
                        dd = si * b + co * dd;
                        a = na;
                        b = nb;
                    }
                    var e = expected.bones().get(id).matrix();
                    float[] actual = { a, b, cc, dd, wx, wy };
                    double[] values = { e.a(), e.b(), e.c(), e.d(), e.tx(), e.ty() };
                    for (int n = 0; n < 6; n++)
                        if (!Float.isFinite(actual[n])
                                || Math.abs(actual[n] - values[n]) > 1e-3 + 1e-5 * Math.abs(values[n]))
                            throw ProjectCodec.bad("path.float-certificate.compatibility",
                                    "Static double/float branch or approximation differs; project remains lossless");
                    matrices.put(id, new M(a, b, cc, dd, wx, wy));
                    x = nx;
                    y = ny;
                }
            }
    }
}
