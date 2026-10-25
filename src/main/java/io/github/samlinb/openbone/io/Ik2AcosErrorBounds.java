package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Whole-input-range forward error bound for animated uniform IK2.
 * Range values enclose double arithmetic; e bounds float displacement from it.
 * No animation frames are sampled. Unsupported causal curves fail closed.
 */
final class Ik2AcosErrorBounds {
    private Ik2AcosErrorBounds() {
    }

    private static final class Unproved extends RuntimeException {
        Unproved(String text) {
            super(text);
        }
    }

    record R(double lo, double hi) {
        R {
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo > hi)
                throw new Unproved("Non-finite precision range");
        }

        static R of(double v) {
            return new R(v, v);
        }

        double abs() {
            return Math.max(Math.abs(lo), Math.abs(hi));
        }

        double minAbs() {
            return lo <= 0 && hi >= 0 ? 0 : Math.min(Math.abs(lo), Math.abs(hi));
        }

        R add(R b) {
            return enclose(lo + b.lo, hi + b.hi);
        }

        R sub(R b) {
            return enclose(lo - b.hi, hi - b.lo);
        }

        R mul(R b) {
            double[] v = { lo * b.lo, lo * b.hi, hi * b.lo, hi * b.hi };
            return enclose(Arrays.stream(v).min().orElseThrow(), Arrays.stream(v).max().orElseThrow());
        }

        R div(R b) {
            if (b.minAbs() == 0)
                throw new Unproved("Precision denominator may vanish");
            return mul(enclose(1 / b.hi, 1 / b.lo));
        }

        R union(R b) {
            return new R(Math.min(lo, b.lo), Math.max(hi, b.hi));
        }

        static R enclose(double lo, double hi) {
            return new R(Math.nextDown(lo), Math.nextUp(hi));
        }
    }

    record E(R r, double e) {
        E {
            if (!Double.isFinite(e) || e < 0)
                throw new Unproved("Unbounded float error");
        }

        static E value(double d, float f) {
            return new E(R.of(d), Math.abs(d - f));
        }

        static E exact(double d) {
            return value(d, (float) d);
        }

        boolean exactPoint() {
            return r.lo == r.hi && e == 0;
        }

        boolean exactPoint(double v) {
            return exactPoint() && r.lo == v;
        }

        private static double rounding(R r, double e) {
            double a = r.abs() + e;
            if (!Double.isFinite(a) || a > RuntimeRange.LIMIT)
                throw new Unproved("Float error range exceeds certified magnitude");
            return .5 * Math.ulp((float) a) + 8 * Math.ulp(a);
        }

        E add(E b) {
            if (b.exactPoint(0))
                return this;
            if (exactPoint(0))
                return b;
            if (exactPoint() && b.exactPoint())
                return value(r.lo + b.r.lo, (float) r.lo + (float) b.r.lo);
            R v = r.add(b.r);
            double error = e + b.e;
            return new E(v, error + rounding(v, error));
        }

        E sub(E b) {
            if (b.exactPoint(0))
                return this;
            if (exactPoint() && b.exactPoint())
                return value(r.lo - b.r.lo, (float) r.lo - (float) b.r.lo);
            R v = r.sub(b.r);
            double error = e + b.e;
            return new E(v, error + rounding(v, error));
        }

        E mul(E b) {
            if (exactPoint(0) || b.exactPoint(0))
                return exact(0);
            if (exactPoint(1))
                return b;
            if (b.exactPoint(1))
                return this;
            if (exactPoint() && b.exactPoint())
                return value(r.lo * b.r.lo, (float) r.lo * (float) b.r.lo);
            R v = r.mul(b.r);
            double error = r.abs() * b.e + b.r.abs() * e + e * b.e;
            return new E(v, error + rounding(v, error));
        }

        E div(E b) {
            if (b.exactPoint(1))
                return this;
            if (exactPoint() && b.exactPoint() && b.r.lo != 0)
                return value(r.lo / b.r.lo, (float) r.lo / (float) b.r.lo);
            double min = b.r.minAbs();
            if (min <= b.e)
                throw new Unproved("Float precision denominator may vanish");
            R v = r.div(b.r);
            double error = (e + (r.abs() / min) * b.e) / (min - b.e);
            return new E(v, error + rounding(v, error));
        }

        E square() {
            if (exactPoint())
                return value(r.lo * r.lo, (float) r.lo * (float) r.lo);
            R v = R.enclose(r.minAbs() * r.minAbs(), r.abs() * r.abs());
            double error = 2 * r.abs() * e + e * e;
            return new E(v, error + rounding(v, error));
        }

        E sqrt() {
            double low = Math.max(0, r.lo);
            R v = R.enclose(Math.sqrt(low), Math.sqrt(Math.max(0, r.hi)));
            double error = Math.max(Math.sqrt(low) - Math.sqrt(Math.max(0, low - e)),
                    Math.sqrt(low + e) - Math.sqrt(low));
            return new E(v, error + rounding(v, error));
        }

        E minOne() {
            return new E(new R(Math.min(1, r.lo), Math.min(1, r.hi)), e);
        }

        E union(E b) {
            return new E(r.union(b.r), Math.max(e, b.e));
        }
    }

    private static E sample(AnimationClip clip, EntityId target, Channel channel, int component) {
        E result = E.exact(channel == Channel.SCALE ? 1 : 0);
        for (var track : clip.timelines())
            if (track.boneId().equals(target) && track.channel() == channel) {
                var keys = track.keys();
                for (int n = 0; n < keys.size(); n++) {
                    var k = keys.get(n);
                    if (k.time() != (double) (float) k.time())
                        throw new Unproved(
                                "Causal/descendant key seconds require an exact float clock for the current precision proof");
                    double value = k.values().get(component);
                    E start = E.exact(value);
                    result = result.union(start);
                    if (n + 1 == keys.size())
                        continue;
                    var next = keys.get(n + 1);
                    double end = next.values().get(component);
                    if (k.curves().get(component).kind() == Curve.Kind.STEPPED || value == end)
                        continue;
                    if (k.curves().get(component).kind() != Curve.Kind.LINEAR)
                        throw new Unproved("Changing Bezier target/descendant requires a chord-error precision proof");
                    E time = new E(new R(k.time(), next.time()), 0), span = E.exact(next.time()).sub(E.exact(k.time())),
                            fraction = time.sub(E.exact(k.time())).div(span);
                    result = result.union(start.add(fraction.mul(E.exact(end).sub(start))));
                }
            }
        return result;
    }

    private static E offset(AnimationClip clip, EntityId target, int component) {
        return sample(clip, target, Channel.TRANSLATE, component);
    }

    private static E trig(E angle, boolean cosine) {
        double lo = angle.r.lo, hi = angle.r.hi, min = -1, max = 1;
        if (hi - lo < 2 * Math.PI) {
            double a = cosine ? Math.cos(lo) : Math.sin(lo), b = cosine ? Math.cos(hi) : Math.sin(hi);
            min = Math.min(a, b);
            max = Math.max(a, b);
            double shift = cosine ? 0 : Math.PI / 2;
            for (long k = (long) Math.ceil((lo - shift) / Math.PI); shift + k * Math.PI <= hi; k++) {
                double v = (k & 1) == 0 ? 1 : -1;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return new E(R.enclose(min, max), angle.e + .5 * Math.ulp(1f) + 32 * Math.ulp(Math.max(1, angle.r.abs())));
    }

    private static E atan2(E y, E x) {
        if (x.r.lo - x.e <= 0 && y.r.lo - y.e <= 0 && y.r.hi + y.e >= 0)
            throw new Unproved("Parent atan2 signed-pi/singular branch is unresolved over the complete hull");
        double radius = Math.hypot(x.r.minAbs(), y.r.minAbs()), error = Math.hypot(x.e, y.e);
        if (error >= radius)
            throw new Unproved("Parent atan2 direction error is unbounded");
        double[] v = { Math.atan2(y.r.lo, x.r.lo), Math.atan2(y.r.lo, x.r.hi), Math.atan2(y.r.hi, x.r.lo),
                Math.atan2(y.r.hi, x.r.hi) };
        return new E(R.enclose(Arrays.stream(v).min().orElseThrow(), Arrays.stream(v).max().orElseThrow()),
                Math.asin(error / radius) + .5 * Math.ulp((float) Math.PI) + 32 * Math.ulp(Math.PI));
    }

    private static E wrap(E angle) {
        double lo = angle.r.lo - angle.e, hi = angle.r.hi + angle.e;
        if (lo <= 180 && hi >= 180 || lo <= -180 && hi >= -180)
            throw new Unproved("IK single-wrap branch is unresolved over the complete hull");
        if (lo > 180)
            return angle.sub(E.exact(360));
        if (hi < -180)
            return angle.add(E.exact(360));
        return angle;
    }

    private record Matrix(E a, E b, E c, E d, E x, E y) {
        Matrix child(Matrix q) {
            return new Matrix(a.mul(q.a).add(b.mul(q.c)), a.mul(q.b).add(b.mul(q.d)), c.mul(q.a).add(d.mul(q.c)),
                    c.mul(q.b).add(d.mul(q.d)), a.mul(q.x).add(b.mul(q.y)).add(x), c.mul(q.x).add(d.mul(q.y)).add(y));
        }
    }

    private static Matrix matrix(E x, E y, E rotation, E sx, E sy) {
        E degrees = E.value(Math.PI / 180, Ik2PrecisionCertificate.DEG), a = rotation.mul(degrees),
                b = rotation.add(E.exact(90)).mul(degrees);
        return new Matrix(trig(a, true).mul(sx), trig(b, true).mul(sy), trig(a, false).mul(sx), trig(b, false).mul(sy),
                x, y);
    }

    private static void fullEndpoints(DocumentSnapshot doc, AnimationClip clip, IkConstraint ik,
            Ik2PrecisionCertificate.Input in, Set<EntityId> affected, E tx, E ty, E cosine, E dd, double thetaError) {
        var parent = doc.bone(ik.boneIds().get(0));
        var child = doc.bone(ik.boneIds().get(1));
        var p = parent.local();
        var c = child.local();
        int orientation = (p.scaleX() < 0 ? -1 : 1) * (p.scaleY() < 0 ? -1 : 1), bend = ik.bendPositive() ? 1 : -1;
        R clamped = new R(Math.max(-1, Math.min(1, cosine.r.lo)), Math.max(-1, Math.min(1, cosine.r.hi)));
        E theta = new E(R.enclose(Math.acos(clamped.hi), Math.acos(clamped.lo)), thetaError).mul(E.exact(bend)),
                first = E.value(in.firstDouble(), in.first()), reach = E.value(in.secondDouble(), in.second()),
                axisX = first.add(reach.mul(new E(clamped, cosine.e))), axisY = reach.mul(trig(theta, false));
        double cy = ik.stretch() ? 0 : c.y(), offset = Math.atan2(cy, c.x()) * orientation;
        float offsetF = (float) Math.atan2(in.cy(), (float) c.x()) * orientation;
        E os = E.value(offset, offsetF), radDeg = E.value(180 / Math.PI, 180 / Ik2PrecisionCertificate.PI),
                mix = E.exact(ik.mix());
        E parentDelta = atan2(ty.mul(axisX).sub(tx.mul(axisY)), tx.mul(axisX).add(ty.mul(axisY))).sub(os).mul(radDeg)
                .add(E.exact(p.scaleX() < 0 ? 180 : 0)).sub(E.exact(p.rotation())),
                childDelta = theta.add(os).mul(radDeg).mul(E.exact(orientation)).add(E.exact(c.scaleX() < 0 ? 180 : 0))
                        .sub(E.exact(c.rotation())),
                pr = E.exact(p.rotation()).add(wrap(parentDelta).mul(mix)),
                cr = E.exact(c.rotation()).add(wrap(childDelta).mul(mix)), sx = E.exact(p.scaleX()),
                sy = E.exact(p.scaleY());
        if (ik.stretch() && cosine.r.hi + cosine.e > 1) {
            if (cosine.r.lo - cosine.e <= 1)
                throw new Unproved("Stretch activation branch is unresolved over the complete hull");
            E scale = dd.sqrt().div(first.add(reach)).sub(E.exact(1)).mul(mix).add(E.exact(1));
            sx = sx.mul(scale);
            if (ik.uniform())
                sy = sy.mul(scale);
        }
        var up = in.doubles().get(parent.parentId());
        var f = in.floats().get(parent.parentId());
        Matrix upstream = new Matrix(E.value(up.a(), f.a()), E.value(up.b(), f.b()), E.value(up.c(), f.c()),
                E.value(up.d(), f.d()), E.value(up.tx(), f.x()), E.value(up.ty(), f.y()));
        Map<EntityId, Matrix> world = new HashMap<>();
        for (var b : doc.bones())
            if (affected.contains(b.id())) {
                Matrix local;
                if (b.id().equals(parent.id()))
                    local = matrix(E.exact(p.x()), E.exact(p.y()), pr, sx, sy);
                else if (b.id().equals(child.id()))
                    local = matrix(E.exact(c.x()), E.value(cy, in.cy()), cr, E.exact(c.scaleX()), E.exact(c.scaleY()));
                else {
                    var t = b.local();
                    local = matrix(E.exact(t.x()).add(sample(clip, b.id(), Channel.TRANSLATE, 0)),
                            E.exact(t.y()).add(sample(clip, b.id(), Channel.TRANSLATE, 1)),
                            E.exact(t.rotation()).add(sample(clip, b.id(), Channel.ROTATE, 0)),
                            E.exact(t.scaleX()).mul(sample(clip, b.id(), Channel.SCALE, 0)),
                            E.exact(t.scaleY()).mul(sample(clip, b.id(), Channel.SCALE, 1)));
                }
                Matrix m = (b.id().equals(parent.id()) ? upstream : world.get(b.parentId())).child(local);
                world.put(b.id(), m);
                E length = E.exact(b.length());
                E[] values = { m.a, m.b, m.c, m.d, m.x, m.y, m.x.add(m.a.mul(length)), m.y.add(m.c.mul(length)) };
                for (int n = 0; n < values.length; n++)
                    if (values[n].e > 1e-3)
                        throw new Unproved("Complete-hull matrix/endpoint float error exceeds absolute tolerance at "
                                + b.name() + " component " + n + ": " + values[n].e);
            }
    }

    private static double[] localBound(DocumentSnapshot d, AnimationClip clip, EntityId id) {
        var s = d.bone(id).local();
        double[] v = { Math.abs(s.x()), Math.abs(s.y()), Math.abs(s.scaleX()), Math.abs(s.scaleY()) };
        for (var track : clip.timelines())
            if (track.boneId().equals(id) && track.channel() != Channel.ROTATE) {
                int offset = track.channel() == Channel.SCALE ? 2 : 0;
                double[] setup = offset == 2 ? new double[] { s.scaleX(), s.scaleY() } : new double[] { s.x(), s.y() };
                for (int n = 0; n < track.keys().size(); n++)
                    for (int c = 0; c < 2; c++) {
                        var k = track.keys().get(n);
                        var points = new ArrayList<>(List.of(k.values().get(c)));
                        if (n + 1 < track.keys().size() && k.curves().get(c).kind() == Curve.Kind.BEZIER) {
                            double delta = track.keys().get(n + 1).values().get(c) - k.values().get(c);
                            points.add(k.values().get(c) + delta * k.curves().get(c).y1());
                            points.add(k.values().get(c) + delta * k.curves().get(c).y2());
                        }
                        for (double p : points)
                            v[offset + c] = Math.max(v[offset + c],
                                    Math.abs(offset == 2 ? setup[c] * p : setup[c] + p));
                    }
                for (int c = 0; c < 2; c++)
                    v[offset + c] *= 1.001;
            }
        return v;
    }

    private static double spectral(double a, double b, double c, double d) {
        double x = a * a + c * c, y = b * b + d * d, z = a * b + c * d;
        return Math.nextUp(Math.sqrt((x + y + Math.hypot(x - y, 2 * z)) * .5)) * (1 + 1e-12);
    }

    private static double rotationNorm() {
        // Input rotations <=1e6 degrees; one-wrap IK interpolation stays below 2001000.
        // The two rounded float arguments differ from pi/2 by at most the rounded
        // +90 error and two half-ULPs of their products. Gram's off-diagonal is <=
        // sin(argument error), so sqrt(1+error) bounds the operator norm. Eight
        // float epsilons cover rounded sin/cos columns and scale products.
        float bound = 2001000, deg = Ik2PrecisionCertificate.DEG;
        double error = Math.abs(90.0 * deg - Math.PI / 2) + .5 * Math.ulp(bound + 90) * deg
                + Math.ulp((bound + 90) * deg);
        return Math.sqrt(1 + Math.min(1, error)) * (1 + 8 * Math.ulp(1f));
    }

    private record Levers(double parent, double child) {
    }

    private static Levers lever(DocumentSnapshot d, AnimationClip clip, IkConstraint ik,
            Ik2PrecisionCertificate.Input in, Set<EntityId> affected, double stretch) {
        var parent = d.bone(ik.boneIds().get(0));
        var up = in.doubles().get(parent.parentId());
        var f = in.floats().get(parent.parentId());
        double norm = Math.max(spectral(up.a(), up.b(), up.c(), up.d()), spectral(f.a(), f.b(), f.c(), f.d())),
                rotation = rotationNorm();
        Map<EntityId, double[]> world = new HashMap<>();
        double max = 0;
        var child = ik.boneIds().get(1);
        var childTree = Ik2PrecisionCertificate.descendants(d, child);
        double childMax = 0;
        for (var b : d.bones())
            if (affected.contains(b.id())) {
                var v = localBound(d, clip, b.id());
                double n, p, cp = 0;
                if (b.id().equals(parent.id())) {
                    n = norm * rotation * Math.max(v[2], v[3]) * stretch;
                    p = 0;
                } else {
                    var old = world.get(b.parentId());
                    double translation = old[0] * Math.hypot(v[0], v[1]);
                    p = old[1] + translation;
                    n = old[0] * rotation * Math.max(v[2], v[3]);
                    if (childTree.contains(b.id()) && !b.id().equals(child))
                        cp = old[2] + translation;
                }
                world.put(b.id(), new double[] { n, p, cp });
                max = Math.max(max, p + n * b.length());
                if (childTree.contains(b.id()))
                    childMax = Math.max(childMax, cp + n * b.length());
            }
        if (!Double.isFinite(max) || !Double.isFinite(childMax))
            throw new Unproved("Descendant endpoint amplification is unbounded");
        return new Levers(max, childMax);
    }

    static void validate(DocumentSnapshot d, AnimationClip clip, IkConstraint ik, Ik2PrecisionCertificate.Input in,
            Set<EntityId> affected) throws ProjectFormatException {
        try {
            if (clip.duration() != (double) (float) clip.duration())
                throw new Unproved(
                        "Animation loop period requires an exact float duration for the current precision proof");
            var parent = d.bone(ik.boneIds().get(0));
            var child = d.bone(ik.boneIds().get(1));
            var target = d.bone(ik.targetId());
            var up = in.doubles().get(parent.parentId());
            var f = in.floats().get(parent.parentId());
            var tp = target.parentId() == null ? io.github.samlinb.openbone.core.math.Affine2.IDENTITY
                    : in.doubles().get(target.parentId());
            var tf = target.parentId() == null ? Ik2PrecisionCertificate.M.IDENTITY
                    : in.floats().get(target.parentId());
            E lx = E.exact(target.local().x()).add(offset(clip, target.id(), 0)),
                    ly = E.exact(target.local().y()).add(offset(clip, target.id(), 1)),
                    wx = E.value(tp.a(), tf.a()).mul(lx).add(E.value(tp.b(), tf.b()).mul(ly))
                            .add(E.value(tp.tx(), tf.x())),
                    wy = E.value(tp.c(), tf.c()).mul(lx).add(E.value(tp.d(), tf.d()).mul(ly))
                            .add(E.value(tp.ty(), tf.y()));
            float det = f.a() * f.d() - f.b() * f.c();
            double determinant = up.determinant();
            if (Math.abs(det) <= .0001 || Math.abs(determinant) <= .0001)
                throw new Unproved("Animated singular-parent precision branch is not certified");
            E inverse = E.value(1 / determinant, 1 / det), x = wx.sub(E.value(up.tx(), f.x())),
                    y = wy.sub(E.value(up.ty(), f.y())),
                    tx = x.mul(E.value(up.d(), f.d())).sub(y.mul(E.value(up.b(), f.b()))).mul(inverse)
                            .sub(E.exact(parent.local().x())),
                    ty = y.mul(E.value(up.a(), f.a())).sub(x.mul(E.value(up.c(), f.c()))).mul(inverse)
                            .sub(E.exact(parent.local().y()));
            E first = E.value(in.firstDouble(), in.first()), reach = E.value(in.secondDouble(), in.second()),
                    dd = tx.square().add(ty.square());
            double sd = ik.softness() * Math.abs(parent.local().scaleX()) * (Math.abs(child.local().scaleX()) + 1) / 2;
            float sf = (float) ik.softness() * (Math.abs((float) parent.local().scaleX())
                    * (Math.abs((float) child.local().scaleX()) + 1) * .5f);
            E soft = E.value(sd, sf), radius = dd.sqrt(), over = radius.sub(first).sub(reach).add(soft);
            if (ik.softness() != 0 && over.r.hi + over.e > 0) {
                if (over.r.lo - over.e <= 0)
                    throw new Unproved("Softness activation crosses an unresolved float branch over the input hull");
                E u = over.div(soft.mul(E.exact(2))).minOne().sub(E.exact(1)),
                        removed = over.sub(soft.mul(E.exact(1).sub(u.square()))).div(radius);
                tx = tx.sub(removed.mul(tx));
                ty = ty.sub(removed.mul(ty));
                dd = tx.square().add(ty.square());
            }
            E cosine = dd.sub(first.square()).sub(reach.square()).div(E.exact(2).mul(first).mul(reach));
            double lo = cosine.r.lo, hi = cosine.r.hi, error = cosine.e, theta;
            if (lo - error > 1)
                theta = 0;
            else if (hi + error < -1)
                theta = Math.abs((float) Math.PI - Math.PI);
            else {
                double extent = Math.max(Math.abs(lo), Math.abs(hi)) + error;
                if (extent < 1)
                    theta = error / Math.sqrt(1 - extent * extent);
                else
                    theta = 2 * Math.asin(Math.sqrt(Math.min(2, error) / 2));
                theta += .5 * Math.ulp((float) Math.PI) + 32 * Math.ulp(Math.PI);
            }
            if (theta == 0) {
                fullEndpoints(d, clip, ik, in, affected, tx, ty, cosine, dd, theta);
                return;
            }
            double cmin = Math.max(-1, Math.min(1, lo)),
                    axis = Math.sqrt(Math.max(0, in.firstDouble() * in.firstDouble()
                            + in.secondDouble() * in.secondDouble() + 2 * in.firstDouble() * in.secondDouble() * cmin));
            if (axis == 0)
                throw new Unproved("Folded circle has no finite angular sensitivity bound");
            double ratio = in.secondDouble() * theta / axis, parentError = ratio < 1 ? Math.asin(ratio) : Math.PI,
                    scaleError = ik.stretch() && lo - error <= 1 && hi + error >= 1 ? 4 * error : 0,
                    stretch = ik.stretch()
                            ? Math.max(1,
                                    Math.sqrt(Math.max(0, dd.r.hi + dd.e)) / (in.firstDouble() + in.secondDouble()))
                            : 1;
            var amplification = lever(d, clip, ik, in, affected, stretch);
            double bound = (amplification.parent * (parentError + scaleError) + amplification.child * theta)
                    * Math.max(ik.mix(), (float) ik.mix());
            // The external absolute tolerance is .001. Reserve three quarters for the
            // remaining matrix/translation arithmetic; this module bounds acos sensitivity.
            if (!Double.isFinite(bound) || bound > .00025)
                throw new Unproved("Complete-hull acos error budget exceeds descendant endpoint allowance: " + bound
                        + " (lever=" + amplification + ", cosineError=" + error + ")");
            fullEndpoints(d, clip, ik, in, affected, tx, ty, cosine, dd, theta);
        } catch (Unproved e) {
            throw Ik2PrecisionCertificate.bad(ik, "Animation " + clip.name() + ": " + e.getMessage());
        }
    }
}
