package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * A fail-closed interval proof, never a pose evaluator. It encloses every float
 * clock in
 * each key interval, including curve control hulls. Sampling is not used as a
 * certificate.
 * Deliberately conservative: a safe project may require smaller
 * animation/geometry ranges.
 */
final class ClipFloatCertificate {
    private ClipFloatCertificate() {
    }

    private static final class Unproved extends RuntimeException {
        Unproved(String s) {
            super(s);
        }
    }

    record I(double lo, double hi) {
        I {
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo > hi
                    || Math.max(Math.abs(lo), Math.abs(hi)) > RuntimeRange.LIMIT)
                throw new Unproved("float intermediate range");
        }

        static I f(double x) {
            return new I((float) x, (float) x);
        }

        boolean fixed() {
            return lo == hi;
        }

        double abs() {
            return Math.max(Math.abs(lo), Math.abs(hi));
        }

        boolean zero() {
            return lo == 0 && hi == 0;
        }

        boolean excludesZero() {
            return lo > 0 || hi < 0;
        }

        double minAbs() {
            return Math.min(Math.abs(lo), Math.abs(hi));
        }

        I union(I b) {
            return new I(Math.min(lo, b.lo), Math.max(hi, b.hi));
        }

        I add(I b) {
            return rounded(lo + b.lo, hi + b.hi, fixed() && b.fixed());
        }

        I neg() {
            return new I(-hi, -lo);
        }

        I sub(I b) {
            return add(b.neg());
        }

        I mul(I b) {
            double[] v = { lo * b.lo, lo * b.hi, hi * b.lo, hi * b.hi };
            return rounded(Arrays.stream(v).min().orElseThrow(), Arrays.stream(v).max().orElseThrow(),
                    fixed() && b.fixed());
        }

        static I rounded(double a, double b, boolean fixed) {
            if (fixed)
                return f(a);
            float l = (float) a, h = (float) b;
            if (l > a)
                l = Math.nextDown(l);
            if (h < b)
                h = Math.nextUp(h);
            return new I(l, h);
        }

        I padded(double e) {
            return rounded(lo - e, hi + e, false);
        }
    }

    record P(I x, I y) {
    }

    private record M(I a, I b, I c, I d, I x, I y) {
        P apply(P p) {
            return new P(a.mul(p.x).add(b.mul(p.y)).add(x), c.mul(p.x).add(d.mul(p.y)).add(y));
        }

        M child(M q) {
            P p = apply(new P(q.x, q.y));
            return new M(a.mul(q.a).add(b.mul(q.c)), a.mul(q.b).add(b.mul(q.d)), c.mul(q.a).add(d.mul(q.c)),
                    c.mul(q.b).add(d.mul(q.d)), p.x, p.y);
        }

        boolean collapsed() {
            return a.zero() && b.zero() && c.zero() && d.zero();
        }
    }

    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        if (d.attachments().stream().noneMatch(a -> a instanceof ClippingAttachment))
            return;
        try {
            translationConstraint(d);
            prove(d, null, 0, 0);
            for (var clip : d.animations()) {
                TreeSet<Float> cuts = new TreeSet<>(List.of(0f));
                for (var k : clip.keys())
                    cuts.add((float) k.time());
                var times = new ArrayList<>(cuts);
                for (int n = 0; n < times.size(); n++) {
                    float a = times.get(n);
                    prove(d, clip, a, a);
                    if (n + 1 < times.size()) {
                        float b = Math.nextDown(times.get(n + 1));
                        if (b > a)
                            partition(d, clip, a, b, 0, new int[] { 0 });
                    }
                }
            }
        } catch (Unproved e) {
            throw ProjectCodec.bad("clipping.float-certificate",
                    "Cannot prove finite clipping/UV arithmetic over the whole animation: " + e.getMessage()
                            + ". Reduce ranges or remove clipping before runtime export; the double project remains editable.");
        }
    }

    /**
     * A deliberately bounded domain, proved after solving rather than using the old
     * unconstrained TRS boxes. No matrix from this audit enters the product
     * renderer.
     */
    private static TransformConstraint translationConstraint(DocumentSnapshot d) {
        var active = d.constraints().stream()
                .filter(c -> c.enabled() && (c instanceof IkConstraint p ? p.mix() != 0
                        : c instanceof TransformConstraint p ? !p.inactive()
                                : c instanceof PathConstraint p ? !p.inactive() : ((PhysicsConstraint) c).mix() != 0))
                .toList();
        if (active.isEmpty())
            return null;
        if (active.size() != 1 || !(active.get(0) instanceof TransformConstraint t) || t.boneIds().size() != 1
                || t.mix().get(0) != 0 || t.mix().get(3) != 0 || t.mix().get(4) != 0 || t.mix().get(5) != 0)
            throw new Unproved(
                    "constraints require one single-bone translation-only Transform; other solved matrix interval domains remain pending");
        return t;
    }

    private static void partition(DocumentSnapshot d, AnimationClip clip, float a, float b, int depth, int[] work) {
        try {
            prove(d, clip, a, b);
        } catch (Unproved failure) {
            if (depth >= 24 || ++work[0] > 2048)
                throw failure;
            float mid = (float) ((double) a + ((double) b - a) / 2);
            if (mid <= a || mid >= b)
                throw failure;
            partition(d, clip, a, mid, depth + 1, work);
            partition(d, clip, Math.nextUp(mid), b, depth + 1, work);
        }
    }

    /**
     * Exact static float matrices are a valid singleton interval certificate. This
     * refines the IK inverse gate's universal 10000 reciprocal bound when no
     * animation
     * or earlier constraint can change the ancestor chain. It never produces a
     * pose.
     */
    static OptionalDouble staticInverseBound(DocumentSnapshot d, EntityId ancestor) {
        if (ancestor == null)
            return OptionalDouble.of(1);
        Set<EntityId> chain = new HashSet<>();
        for (var id = ancestor; id != null; id = d.bone(id).parentId())
            chain.add(id);
        if (d.animations().stream().flatMap(a -> a.timelines().stream()).anyMatch(t -> chain.contains(t.boneId()))
                || d.constraints().stream().flatMap(c -> c.boneIds().stream()).anyMatch(chain::contains))
            return OptionalDouble.empty();
        try {
            Map<EntityId, M> matrices = new HashMap<>();
            for (var b : d.bones())
                if (chain.contains(b.id())) {
                    var t = b.local();
                    var m = matrix(I.f(t.x()), I.f(t.y()), I.f(t.rotation()), I.f(t.scaleX()), I.f(t.scaleY()));
                    matrices.put(b.id(), b.parentId() == null ? m : matrices.get(b.parentId()).child(m));
                }
            var m = matrices.get(ancestor);
            var det = m.a.mul(m.d).sub(m.b.mul(m.c));
            if (!det.fixed())
                return OptionalDouble.empty();
            if (det.abs() <= .0001)
                return OptionalDouble.of(0);
            double inverse = Math.max(m.a.abs() + m.b.abs(), m.c.abs() + m.d.abs()) / det.abs();
            return OptionalDouble.of(Math.nextUp(inverse * (1 + 1e-5)));
        } catch (Unproved invalid) {
            return OptionalDouble.empty();
        }
    }

    /**
     * A float sign certificate for the exact pre-constraint static linear chain.
     * Translation animation does not change it. Rotation/scale or another writer
     * does.
     */
    static OptionalInt staticHandedness(DocumentSnapshot d, EntityId bone, EntityId ignoredConstraint) {
        Set<EntityId> chain = new HashSet<>();
        for (var id = bone; id != null; id = d.bone(id).parentId())
            chain.add(id);
        if (d.animations().stream().flatMap(a -> a.timelines().stream())
                .anyMatch(t -> chain.contains(t.boneId()) && t.channel() != Channel.TRANSLATE)
                || d.constraints().stream().filter(c -> !c.id().equals(ignoredConstraint))
                        .flatMap(c -> c.boneIds().stream()).anyMatch(chain::contains))
            return OptionalInt.empty();
        try {
            Map<EntityId, M> matrices = new HashMap<>();
            for (var b : d.bones())
                if (chain.contains(b.id())) {
                    var t = b.local();
                    var m = matrix(I.f(0), I.f(0), I.f(t.rotation()), I.f(t.scaleX()), I.f(t.scaleY()));
                    matrices.put(b.id(), b.parentId() == null ? m : matrices.get(b.parentId()).child(m));
                }
            var m = matrices.get(bone);
            var det = m.a.mul(m.d).sub(m.b.mul(m.c));
            if (!det.fixed() || det.zero())
                return OptionalInt.empty();
            return OptionalInt.of(det.lo > 0 ? 1 : -1);
        } catch (Unproved failure) {
            return OptionalInt.empty();
        }
    }

    private static void prove(DocumentSnapshot d, AnimationClip clip, float from, float to) {
        Map<EntityId, M> bones = new HashMap<>(), locals = new HashMap<>();
        for (var bone : d.bones()) {
            var s = bone.local();
            I x = I.f(s.x()), y = I.f(s.y()), r = I.f(s.rotation()), sx = I.f(s.scaleX()), sy = I.f(s.scaleY());
            if (clip != null)
                for (var track : clip.timelines())
                    if (track.boneId().equals(bone.id())) {
                        switch (track.channel()) {
                            case TRANSLATE -> {
                                x = x.add(range(track.keys(), 0, from, to, I.f(0)));
                                y = y.add(range(track.keys(), 1, from, to, I.f(0)));
                            }
                            case ROTATE -> r = r.add(range(track.keys(), 0, from, to, I.f(0)));
                            case SCALE -> {
                                sx = sx.mul(range(track.keys(), 0, from, to, I.f(1)));
                                sy = sy.mul(range(track.keys(), 1, from, to, I.f(1)));
                            }
                        }
                    }
            M m = matrix(x, y, r, sx, sy);
            locals.put(bone.id(), m);
            bones.put(bone.id(), bone.parentId() == null ? m : bones.get(bone.parentId()).child(m));
        }
        var constraint = translationConstraint(d);
        if (constraint != null) {
            var id = constraint.boneIds().get(0);
            M input = constraint.local() ? locals.get(id) : bones.get(id),
                    target = constraint.local() ? locals.get(constraint.targetId()) : bones.get(constraint.targetId());
            P destination = constraint.local()
                    ? new P(target.x.add(I.f(constraint.offset().get(1))),
                            target.y.add(I.f(constraint.offset().get(2))))
                    : target.apply(new P(I.f(constraint.offset().get(1)), I.f(constraint.offset().get(2))));
            // Preserve the target operation grouping: absolute-local subtracts current
            // before adding offset, while world localToWorld adds offset before subtract.
            I dx, dy;
            if (constraint.relative()) {
                dx = destination.x;
                dy = destination.y;
            } else if (constraint.local()) {
                dx = target.x.sub(input.x).add(I.f(constraint.offset().get(1)));
                dy = target.y.sub(input.y).add(I.f(constraint.offset().get(2)));
            } else {
                dx = destination.x.sub(input.x);
                dy = destination.y.sub(input.y);
            }
            M solved = new M(input.a, input.b, input.c, input.d, input.x.add(dx.mul(I.f(constraint.mix().get(1)))),
                    input.y.add(dy.mul(I.f(constraint.mix().get(2)))));
            if (constraint.local())
                locals.put(id, solved);
            for (var bone : d.bones()) {
                if (!constraint.local() && bone.id().equals(id)) {
                    bones.put(id, solved);
                    continue;
                }
                M m = locals.get(bone.id());
                bones.put(bone.id(), bone.parentId() == null ? m : bones.get(bone.parentId()).child(m));
            }
        }
        Map<EntityId, Slot> slots = new HashMap<>();
        d.slots().forEach(s -> slots.put(s.id(), s));
        Map<EntityId, Attachment> attachments = new HashMap<>();
        d.attachments().forEach(a -> attachments.put(a.id(), a));
        ClippingAttachment active = null;
        double clipR = 0;
        boolean empty = false;
        for (var slot : d.slots()) {
            var attachment = attachments.get(slot.activeAttachmentId());
            if (attachment instanceof ClippingAttachment c) {
                if (active == null) {
                    active = c;
                    var m = bones.get(slot.boneId());
                    empty = m.collapsed();
                    var p = c.vertices().stream().map(v -> m.apply(point(v))).toList();
                    clipR = radius(p);
                    // Winding is computed before the target knows that a clip is empty.
                    // Even a collapsed polygon at a huge translation must not hide overflow.
                    checkMagnitude(16.0 * c.vertices().size() * clipR * clipR, "polygon winding/cross products");
                    if (!empty) {
                        for (int i = 0; i < c.triangles().size(); i += 3)
                            if (!area(p, c.triangles(), i).excludesZero())
                                throw new Unproved("clip polygon may collapse/change orientation");
                    }
                }
                continue;
            }
            if (active != null && !empty && attachment != null) {
                List<P> points;
                List<Integer> triangles;
                double uv = 1;
                if (attachment instanceof RegionAttachment r) {
                    var t = r.local();
                    if (r.width() < 1e-4 || r.height() < 1e-4 || Math.abs(t.scaleX()) < 1e-4
                            || Math.abs(t.scaleY()) < 1e-4)
                        throw new Unproved("clipped region dimensions/local scales require abs >= 0.0001");
                    M parent = bones.get(slot.boneId());
                    points = regionQuad(r).stream().map(parent::apply).toList();
                    triangles = List.of(0, 1, 2, 2, 3, 0);
                } else if (attachment instanceof MeshAttachment mesh) {
                    points = new ArrayList<>();
                    var offsets = new ArrayList<I>();
                    for (int i = 0; i < mesh.deformSize(); i++)
                        offsets.add(deform(clip, mesh, i, from, to));
                    int i = 0;
                    if (!mesh.weighted()) {
                        var m = bones.get(slot.boneId());
                        for (var p : mesh.vertices())
                            points.add(m.apply(new P(offsets.get(i++), offsets.get(i++))));
                    } else
                        for (var row : mesh.weights()) {
                            I x = I.f(0), y = I.f(0);
                            for (var influence : row) {
                                P p = bones.get(influence.boneId()).apply(new P(offsets.get(i++), offsets.get(i++)));
                                x = x.add(p.x.mul(I.f(influence.weight())));
                                y = y.add(p.y.mul(I.f(influence.weight())));
                            }
                            points.add(new P(x, y));
                        }
                    triangles = mesh.triangles();
                    for (var p : mesh.uvs())
                        uv = Math.max(uv, Math.max(Math.abs((float) p.x()), Math.abs((float) p.y())));
                } else
                    throw new Unproved("unknown clipped attachment");
                // Each clipping edge emits at most two points per input edge, even if
                // roundoff duplicates a boundary point. Do not assume an exact-arithmetic
                // convex n+3 bound for the target's float lists. At most n-2 convex pieces.
                double outputBound = (double) (triangles.size() / 3) * (active.vertices().size() - 2)
                        * Math.scalb(3.0, active.vertices().size());
                if (outputBound > 60000)
                    throw new Unproved("clipped uint16 vertex budget (conservative 3*2^n*(n-2) per source triangle)");
                double r = Math.max(clipR, radius(points));
                checkMagnitude(64 * r * r, "intersection/cross products");
                for (int i = 0; i < triangles.size(); i += 3) {
                    I det = area(points, triangles, i);
                    if (!det.excludesZero())
                        throw new Unproved("source triangle may have zero float area");
                    double reciprocal = 1 / det.minAbs();
                    checkMagnitude(reciprocal, "UV reciprocal area");
                    checkMagnitude(128 * r * r * reciprocal * uv, "UV interpolation intermediate");
                }
            }
            if (active != null && Objects.equals(active.endSlotId(), slot.id()))
                active = null;
        }
    }

    private static void checkMagnitude(double n, String label) {
        if (!Double.isFinite(n) || n > RuntimeRange.LIMIT)
            throw new Unproved(label);
    }

    private static double radius(List<P> points) {
        double r = 0;
        for (var p : points)
            r = Math.max(r, Math.max(p.x.abs(), p.y.abs()));
        return r;
    }

    private static P point(Vec2 p) {
        return new P(I.f(p.x()), I.f(p.y()));
    }

    /**
     * Float enclosure of a static full-page region's local quad, not evaluated
     * geometry.
     * Region uses paired cos/sin of ONE rounded angle, unlike Bone's two angle
     * axes.
     * 64 ULPs cover alternative half-size/scale/product/add grouping and full-page
     * metadata.
     * Package visibility supports an external-only actual-runtime enclosure audit.
     */
    static List<P> regionQuad(RegionAttachment r) {
        var t = r.local();
        I rotation = I.f(t.rotation()), cos = trig(rotation, true), sin = trig(rotation, false), sx = I.f(t.scaleX()),
                sy = I.f(t.scaleY());
        M local = new M(cos.mul(sx), sin.neg().mul(sy), sin.mul(sx), cos.mul(sy), I.f(t.x()), I.f(t.y()));
        var v = List.of(new Vec2(-r.width() / 2, -r.height() / 2), new Vec2(-r.width() / 2, r.height() / 2),
                new Vec2(r.width() / 2, r.height() / 2), new Vec2(r.width() / 2, -r.height() / 2));
        double e = 64.0 * Math.ulp((float) (Math.abs(t.x()) + Math.abs(t.y()) + r.width() * Math.abs(t.scaleX())
                + r.height() * Math.abs(t.scaleY())));
        return v.stream().map(p -> local.apply(point(p))).map(p -> new P(p.x.padded(e), p.y.padded(e))).toList();
    }

    private static I area(List<P> p, List<Integer> t, int i) {
        P a = p.get(t.get(i)), b = p.get(t.get(i + 1)), c = p.get(t.get(i + 2));
        return b.y.sub(c.y).mul(a.x.sub(c.x)).add(c.x.sub(b.x).mul(a.y.sub(c.y)));
    }

    private static M matrix(I x, I y, I rotation, I sx, I sy) {
        I yAxis = rotation.add(I.f(90));
        return new M(trig(rotation, true).mul(sx), trig(yAxis, true).mul(sy), trig(rotation, false).mul(sx),
                trig(yAxis, false).mul(sy), x, y);
    }

    private static I trig(I degrees, boolean cosine) {
        I radians = degrees.mul(I.f((float) (Math.PI / 180)));
        if (radians.fixed())
            return I.f(cosine ? Math.cos(radians.lo) : Math.sin(radians.lo));
        double a = radians.lo, b = radians.hi;
        if (b - a >= 2 * Math.PI)
            return new I(-1, 1);
        double min = Math.min(cosine ? Math.cos(a) : Math.sin(a), cosine ? Math.cos(b) : Math.sin(b)),
                max = Math.max(cosine ? Math.cos(a) : Math.sin(a), cosine ? Math.cos(b) : Math.sin(b));
        double shift = cosine ? 0 : Math.PI / 2;
        for (long k = (long) Math.ceil((a - shift) / Math.PI); shift + k * Math.PI <= b; k++) {
            double v = k % 2 == 0 ? 1 : -1;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return I.rounded(Math.nextDown(min), Math.nextUp(max), false);
    }

    private static I deform(AnimationClip clip, MeshAttachment mesh, int index, float a, float b) {
        double setup = 0;
        if (!mesh.weighted())
            setup = index % 2 == 0 ? mesh.vertices().get(index / 2).x() : mesh.vertices().get(index / 2).y();
        else {
            int n = 0;
            outer: for (var row : mesh.weights())
                for (var influence : row) {
                    if (n++ == index / 2) {
                        setup = index % 2 == 0 ? influence.point().x() : influence.point().y();
                        break outer;
                    }
                }
        }
        I base = I.f(setup);
        if (clip == null)
            return base;
        for (var t : clip.deforms())
            if (t.attachmentId().equals(mesh.id())) {
                // The reader pre-adds Setup for unweighted keys; weighted keys stay offsets.
                if (mesh.weighted())
                    return base.add(range(t.keys(), index, a, b, I.f(0)));
                List<Keyframe> absolute = new ArrayList<>();
                for (var k : t.keys()) {
                    var v = new ArrayList<>(k.values());
                    v.set(index, (double) (float) ((float) setup + (float) (double) v.get(index)));
                    absolute.add(new Keyframe(k.id(), k.time(), v, k.curves()));
                }
                return range(absolute, index, a, b, base);
            }
        return base;
    }

    private static I range(List<Keyframe> keys, int component, float from, float to, I before) {
        int index = -1;
        for (int i = 0; i < keys.size(); i++)
            if ((float) keys.get(i).time() <= from)
                index = i;
            else
                break;
        if (index < 0)
            return before;
        var k = keys.get(index);
        I v = I.f(k.values().get(component));
        if (index == keys.size() - 1)
            return v;
        var next = keys.get(index + 1);
        I end = I.f(next.values().get(component));
        var curve = k.curves().get(component);
        if (curve.kind() == Curve.Kind.STEPPED)
            return v;
        if (from == to && from == (float) k.time())
            return v;
        if (curve.kind() == Curve.Kind.BEZIER) {
            // Every finite-difference chord lies inside this padded control hull. Unlike
            // sampled extrema this enclosure also covers the times between all frames.
            I p1 = I.f(k.values().get(component)
                    + (next.values().get(component) - k.values().get(component)) * curve.y1()),
                    p2 = I.f(k.values().get(component)
                            + (next.values().get(component) - k.values().get(component)) * curve.y2());
            I hull = v.union(end).union(p1).union(p2);
            return hull.padded(hull.abs() * .001 + 128.0 * Float.MIN_VALUE);
        }
        I time = new I(from, to).sub(I.f(k.time()));
        I span = I.f(next.time()).sub(I.f(k.time()));
        if (!span.excludesZero())
            throw new Unproved("key clock interval");
        I fraction = I.rounded(time.lo / span.hi, time.hi / span.lo, false);
        return v.add(end.sub(v).mul(fraction));
    }
}
