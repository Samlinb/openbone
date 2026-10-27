package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.math.PhysicsEnvelope;
import io.github.samlinb.openbone.core.math.PathConstraintSolver;
import io.github.samlinb.openbone.core.model.*;

/**
 * Conservative certificate for the 4.2 float runtime, separate from the double
 * project domain.
 * Leave headroom for runtime polynomial differences, matrix sums and float
 * rounding.
 */
public final class RuntimeRange {
    public static final double LIMIT = Float.MAX_VALUE / 1024.0;

    private RuntimeRange() {
    }

    public static double number(double v, String p) throws ProjectFormatException {
        if (!Double.isFinite(v) || Math.abs(v) > LIMIT || v != 0 && Math.abs(v) < Float.MIN_NORMAL)
            throw ProjectCodec.bad(p,
                    "Outside certified float32 range (zero or normal float, abs <= Float.MAX_VALUE/1024)");
        return v;
    }

    private static void rotation(double v, String p) throws ProjectFormatException {
        number(v, p);
        if (Math.abs(v) > 1_000_000)
            throw ProjectCodec.bad(p, "Rotation outside certified ±1000000 degrees");
    }

    private static void transform(Transform t, String p) throws ProjectFormatException {
        number(t.x(), p + ".x");
        number(t.y(), p + ".y");
        rotation(t.rotation(), p + ".rotation");
        rotation(t.shearX(), p + ".shearX"); rotation(t.shearY(), p + ".shearY");
        number(t.scaleX(), p + ".scaleX");
        number(t.scaleY(), p + ".scaleY");
    }

    public static void validate(DocumentSnapshot d) throws ProjectFormatException {
        for(var attachment:d.attachments())if(attachment instanceof PathAttachment path
                &&(path.weighted()||d.animations().stream().flatMap(a->a.deforms().stream()).anyMatch(t->t.attachmentId().equals(path.id()))))
            throw new ProjectFormatException(io.github.samlinb.openbone.i18n.M.m("pathattachment.runtimeExport"));
        P8Range.validate(d);
        if (d.animations().stream().anyMatch(a -> !a.constraintTracks().isEmpty())) {
            ConstraintTimelineFloatCertificate.validate(d);
            return;
        }
        for (var c : d.constraints()) {
            if (c instanceof PhysicsConstraint p) {
                for (double v : p.influence())
                    number(v, "physics influence");
                for (double v : p.parameters())
                    number(v, "physics parameter");
                number(p.limit(), "physics limit");
                if (p.mix() != 0) {
                    var inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(p.boneId()).parentId());
                    if (inverse.isEmpty() || inverse.getAsDouble() == 0)
                        throw ProjectCodec.bad("physics.float-certificate.parent",
                                "Static nonsingular parent required for target applied-pose proof");
                    if (p.influence().get(3) > 0) {
                        if (d.animations().stream().flatMap(a -> a.timelines().stream())
                                .anyMatch(t -> t.boneId().equals(p.boneId()) && t.channel() == Channel.SCALE))
                            throw ProjectCodec.bad("physics.float-certificate.scaleX",
                                    "Animated scale reach proof is pending");
                        double reach = d.bone(p.boneId()).length() * Math.abs(d.bone(p.boneId()).local().scaleX())
                                / (2 * inverse.getAsDouble());
                        if (reach != 0 && reach < 1e-6)
                            throw ProjectCodec.bad("physics.float-certificate.scaleX",
                                    "Tiny positive reach uses the engineering double degeneracy; runtime IO refused");
                    }
                }
                continue;
            }
            if (c instanceof PathConstraint p) {
                for (double v : new double[] { p.position(), p.spacing(), p.rotation(), p.mixRotate(), p.mixX(),
                        p.mixY() })
                    number(v, "path parameter");
                rotation(p.rotation(), "path rotation");
                if (!p.inactive())
                    for (var id : p.boneIds()) {
                        var inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(id).parentId());
                        if (inverse.isEmpty() || inverse.getAsDouble() == 0)
                            throw ProjectCodec.bad("path.float-certificate.parent",
                                    "Cannot certify applied-pose inverse for constrained/animated/singular parent");
                        number(inverse.getAsDouble(), "path parent inverse");
                    }
                continue;
            }
            if (c instanceof TransformConstraint t) {
                for (double v : t.mix())
                    number(v, "transform mix");
                for (double v : t.offset())
                    number(v, "transform offset");
                rotation(t.offset().get(0), "transform rotation offset");
                rotation(t.offset().get(5), "transform shear offset");
                if (!t.inactive())
                    for (var id : t.boneIds()) {
                        if (!t.local() && t.mix().get(5) > 0 && t.mix().get(5) < 1) {
                            var targetSign = ClipFloatCertificate.staticHandedness(d, t.targetId(), t.id());
                            var boneSign = ClipFloatCertificate.staticHandedness(d, id, t.id());
                            if (targetSign.isEmpty() || !t.relative()
                                    && (boneSign.isEmpty() || targetSign.getAsInt() != boneSign.getAsInt()))
                                throw ProjectCodec.bad("$.transform." + t.name() + ".mixShearY",
                                        "Cannot certify the ±180 branch for partial world shear with a changing/degenerate target linear chain or opposite absolute handedness; use 0/1 shear mix or a certified static linear chain. The project remains lossless");
                        }
                        if (!t.relative() && (t.mix().get(3) != 0 || t.mix().get(4) != 0)
                                && d.animations().stream().flatMap(a -> a.timelines().stream()).anyMatch(
                                        track -> track.boneId().equals(id) && track.channel() == Channel.SCALE))
                            throw ProjectCodec.bad("$.transform." + t.name() + ".scale",
                                    "Absolute-scale animation float division proof is pending; the project remains lossless");
                        if (t.local() && !t.relative())
                            for (int axis : new int[] { 3, 4 }) {
                                double scale = axis == 3 ? d.bone(id).local().scaleX() : d.bone(id).local().scaleY();
                                if (t.mix().get(axis) != 0 && scale != 0 && Math.abs(scale) < 1e-12)
                                    throw ProjectCodec.bad("$.transform." + t.name() + ".scale",
                                            "Tiny absolute-local axis uses the engineering degenerate strategy; runtime IO refused");
                            }
                        if (!t.local()) {
                            var inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(id).parentId());
                            if (inverse.isEmpty() || inverse.getAsDouble() == 0)
                                throw ProjectCodec.bad("$.transform." + t.name() + ".parent",
                                        "Cannot certify the target applied-pose inverse for an animated/constrained/singular parent");
                            number(inverse.getAsDouble(), "transform parent inverse");
                            if (!t.relative())
                                for (int axis : new int[] { 3, 4 }) {
                                    double scale = Math
                                            .abs(axis == 3 ? d.bone(id).local().scaleX() : d.bone(id).local().scaleY());
                                    if (t.mix().get(axis) != 0 && scale != 0
                                            && scale / (2 * inverse.getAsDouble()) < 1e-12)
                                        throw ProjectCodec.bad("$.transform." + t.name() + ".scale",
                                                "Cannot exclude the engineering degenerate world-axis range");
                                }
                        }
                    }
                continue;
            }
            if (!(c instanceof IkConstraint ik))
                throw ProjectCodec.bad("$.constraints", "Constraint float certificate is not implemented");
            if (d.bone(ik.boneIds().get(0)).parentId() == null)
                throw ProjectCodec.bad("$.ik." + ik.name() + ".bones",
                        "4.2.22 IK requires a parent bone; root IK remains editable in the project");
            number(ik.mix(), "IK mix");
            number(ik.softness(), "IK softness");
            if (ik.boneIds().size() == 2) {
                var parent = d.bone(ik.boneIds().get(0));
                var child = d.bone(ik.boneIds().get(1));
                if (child.length() * Math.abs(child.local().scaleX()) < 1e-12
                        || Math.abs(parent.local().scaleX()) < 1e-12 || Math.abs(parent.local().scaleY()) < 1e-12)
                    throw ProjectCodec.bad("$.ik." + ik.name(),
                            "Zero IK2 second length/chain scale is editable, but target 4.2 can divide by zero");
                if (d.animations().stream().flatMap(a -> a.timelines().stream())
                        .anyMatch(t -> ik.boneIds().contains(t.boneId()) && t.channel() == Channel.SCALE))
                    throw ProjectCodec.bad("$.ik." + ik.name() + ".animations.scale",
                            "IK2 animated chain-scale float proof is pending; the project retains all tracks losslessly");
                float x = Math.abs((float) parent.local().scaleX()), y = Math.abs((float) parent.local().scaleY());
                double diff = Math.abs(Math.abs(parent.local().scaleX()) - Math.abs(parent.local().scaleY()));
                if (Math.abs(diff - .0001) < 16 * Math.ulp(Math.max(x, y)))
                    throw ProjectCodec.bad("$.ik." + ik.name(),
                            "Cannot certify the uniform/nonuniform float branch near its 0.0001 threshold");
            }
        }
        for (var b : d.bones()) {
            transform(b.local(), "bone " + b.name());
            number(b.length(), "bone length " + b.name());
        }
        StaticTransformFloatCertificate.validate(d);
        StaticPathFloatCertificate.validate(d);
        PhysicsFloatCertificate.validate(d);
        for (var a : d.attachments()) {
            if (a instanceof PathAttachment p) {
                for (var v : p.vertices()) {
                    number(v.x(), "path local x");
                    number(v.y(), "path local y");
                }
                for (double v : p.lengths())
                    number(v, "path stored length");
                continue;
            }
            if (a instanceof BoundingBoxAttachment box) {
                for (var v : box.vertices()) { number(v.x(), "boundingbox.x"); number(v.y(), "boundingbox.y"); }
                continue;
            }
            if (a instanceof ClippingAttachment c) {
                List<io.github.samlinb.openbone.core.math.Vec2> transmitted = new ArrayList<>();
                for (var v : c.vertices()) {
                    number(v.x(), "clipping.local.x");
                    number(v.y(), "clipping.local.y");
                    transmitted.add(new io.github.samlinb.openbone.core.math.Vec2((float) v.x(), (float) v.y()));
                }
                try {
                    io.github.samlinb.openbone.core.math.MeshTopology.boundary(transmitted, transmitted.size());
                } catch (IllegalArgumentException invalid) {
                    throw ProjectCodec.bad("clipping " + c.name(),
                            "Topology collapses in float32: " + invalid.getMessage());
                }
                continue;
            }
            if (a instanceof MeshAttachment m) {
                if (m.weighted()) {
                    for (var row : m.weights())
                        for (var i : row) {
                            number(i.point().x(), "skin bone-local.x");
                            number(i.point().y(), "skin bone-local.y");
                            number(i.weight(), "skin weight");
                        }
                    for (var uv : m.uvs()) {
                        number(uv.x(), "mesh.uv.u");
                        number(uv.y(), "mesh.uv.v");
                    }
                    continue;
                }
                List<io.github.samlinb.openbone.core.math.Vec2> transmitted = new ArrayList<>();
                for (var v : m.vertices()) {
                    number(v.x(), "mesh.local.x");
                    number(v.y(), "mesh.local.y");
                    transmitted.add(new io.github.samlinb.openbone.core.math.Vec2((float) v.x(), (float) v.y()));
                }
                for (var uv : m.uvs()) {
                    number(uv.x(), "mesh.uv.u");
                    number(uv.y(), "mesh.uv.v");
                }
                try {
                    io.github.samlinb.openbone.core.math.MeshTopology.validate(transmitted, m.hull(), m.triangles());
                } catch (IllegalArgumentException invalid) {
                    throw ProjectCodec.bad("mesh " + m.name(),
                            "Topology collapses/changes in float32: " + invalid.getMessage());
                }
                continue;
            }
            var r = (RegionAttachment) a;
            var t = r.local();
            transform(t, "region " + r.name());
            number(r.width(), "region width");
            number(r.height(), "region height");
            // Runtime constructs the region's local quad BEFORE applying its bone. A tiny
            // ancestor must not hide width*localScale overflow in updateRegion().
            double x = number(Math.abs(t.scaleX()) * r.width(), "region local width*scale"),
                    y = number(Math.abs(t.scaleY()) * r.height(), "region local height*scale");
            number(Math.hypot(t.x(), t.y()) + Math.hypot(x, y), "region local quad before parent");
        }
        bounds(d, null);
        for (var clip : d.animations()) {
            for (var t : clip.deforms()) {
                float previous = -1;
                for (int i = 0; i < t.keys().size(); i++) {
                    var k = t.keys().get(i);
                    number(k.time(), "deform.time");
                    if ((float) k.time() <= previous)
                        throw ProjectCodec.bad("deform.time", "Distinct seconds collapse in float32");
                    previous = (float) k.time();
                    for (double v : k.values())
                        number(v, "deform.offset");
                    if (i + 1 < t.keys().size()) {
                        var next = t.keys().get(i + 1);
                        number(next.time() - k.time(), "deform time difference");
                        for (int c = 0; c < k.values().size(); c++)
                            number(next.values().get(c) - k.values().get(c), "deform difference");
                        var curve = k.curves().get(0);
                        if (curve.kind() == Curve.Kind.BEZIER) {
                            number(k.time() + (next.time() - k.time()) * curve.x1(), "deform curve time");
                            number(k.time() + (next.time() - k.time()) * curve.x2(), "deform curve time");
                            number(curve.y1(), "deform percent control");
                            number(curve.y2(), "deform percent control");
                            certifyTimeKnots(k.time(), next.time(), curve);
                        }
                    }
                }
            }
            for (var t : clip.timelines()) {
                float last = -1;
                for (int i = 0; i < t.keys().size(); i++) {
                    var k = t.keys().get(i);
                    number(k.time(), "key time");
                    if ((float) k.time() <= last)
                        throw ProjectCodec.bad("key time", "Distinct seconds collapse in float32");
                    last = (float) k.time();
                    for (int c = 0; c < t.channel().components(); c++) {
                        double v = k.values().get(c);
                        number(v, "key value");
                        if (t.channel() == Channel.ROTATE || t.channel() == Channel.SHEAR)
                            rotation(v, "rotation key");
                        if (i + 1 < t.keys().size()) {
                            var next = t.keys().get(i + 1);
                            number(next.time() - k.time(), "key time difference");
                            number(next.values().get(c) - v, "key value difference");
                            if (k.curves().get(c).kind() == Curve.Kind.BEZIER) {
                                var curve = k.curves().get(c);
                                for (double x : List.of(curve.x1(), curve.x2()))
                                    number(k.time() + (next.time() - k.time()) * x, "Bezier absolute time");
                                for (double y : List.of(curve.y1(), curve.y2()))
                                    number(v + (next.values().get(c) - v) * y, "Bezier absolute value");
                                certifyTimeKnots(k.time(), next.time(), curve);
                            }
                        }
                    }
                }
            }
            bounds(d, clip);
        }
        Ik2PrecisionCertificate.validate(d);
        ClipFloatCertificate.validate(d);
    }

    static void certifyTimeKnots(double start, double end, Curve curve) throws ProjectFormatException {
        // Independently evaluate Bernstein time points from the FLOAT input controls.
        // The
        // target's nine forward-difference points can accumulate roundoff proportional
        // to
        // the absolute time origin. Reserve 128 origin-ULPs on each side of every
        // chord;
        // this conservative 256-ULP separation prevents zero/negative denominators
        // without
        // copying the runtime evaluator. Distinct key times alone are not enough.
        double p0 = (float) start, p3 = (float) end, p1 = (float) (start + (end - start) * curve.x1()),
                p2 = (float) (start + (end - start) * curve.x2());
        double gap = 256.0 * Math.ulp((float) p3), previous = p0;
        for (int i = 1; i <= 10; i++) {
            double u = i / 10.0, v = 1 - u,
                    point = i == 10 ? p3 : v * v * v * p0 + 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u * p3;
            if (!(point - previous > gap))
                throw ProjectCodec.bad("Bezier time",
                        "Cannot certify distinct float32 time knots; increase key spacing or use linear/stepped");
            previous = point;
        }
    }

    static void tracedPhysicsBounds(DocumentSnapshot d, AnimationClip clip,
            Map<EntityId, io.github.samlinb.openbone.core.math.PhysicsTraceProof.Offsets> states)
            throws ProjectFormatException {
        bounds(d, clip, states);
    }

    private static void bounds(DocumentSnapshot d, AnimationClip clip) throws ProjectFormatException {
        bounds(d, clip, Map.of());
    }

    private static void bounds(DocumentSnapshot d, AnimationClip clip,
            Map<EntityId, io.github.samlinb.openbone.core.math.PhysicsTraceProof.Offsets> traced)
            throws ProjectFormatException {
        Map<EntityId, double[]> local = new HashMap<>(), world = new HashMap<>();
        for (var b : d.bones()) {
            var t = b.local();
            local.put(b.id(), new double[] { Math.abs(t.x()), Math.abs(t.y()), Math.abs(t.rotation()),
                    Math.abs(t.scaleX()), Math.abs(t.scaleY()), Math.abs(t.shearX()), Math.abs(t.shearY()) });
        }
        if (clip != null)
            for (var t : clip.timelines()) {
                var s = d.bone(t.boneId()).local();
                double[] setup = { s.x(), s.y(), s.rotation(), s.scaleX(), s.scaleY(), s.shearX(), s.shearY() }, v = local.get(t.boneId());
                int offset = switch (t.channel()) {
                    case TRANSLATE -> 0;
                    case ROTATE -> 2;
                    case SCALE -> 3;
                    case SHEAR -> 5;
                };
                for (int i = 0; i < t.keys().size(); i++)
                    for (int c = 0; c < t.channel().components(); c++) {
                        var k = t.keys().get(i);
                        double a = k.values().get(c);
                        List<Double> points = new ArrayList<>(List.of(a));
                        if (i + 1 < t.keys().size() && k.curves().get(c).kind() == Curve.Kind.BEZIER) {
                            var curve = k.curves().get(c);
                            double delta = t.keys().get(i + 1).values().get(c) - a;
                            points.add(a + delta * curve.y1());
                            points.add(a + delta * curve.y2());
                        }
                        for (double point : points) {
                            double absolute = t.channel() == Channel.SCALE ? setup[offset + c] * point
                                    : setup[offset + c] + point;
                            number(absolute, "animated local pose");
                            if (t.channel() == Channel.ROTATE || t.channel() == Channel.SHEAR)
                                rotation(absolute, "animated rotation");
                            v[offset + c] = Math.max(v[offset + c], Math.abs(absolute));
                        }
                    }
                // Includes Setup in the maximum, so cancellation cannot hide float conversion
                // residuals. 0.1% also covers the target's finite-difference value roundoff
                // (nine steps, normal input controls) before any ancestor can amplify it.
                for (int c = 0; c < t.channel().components(); c++)
                    if (v[offset + c] != 0)
                        v[offset + c] = number(Math.nextUp(v[offset + c] * 1.001), "animated float rounding envelope");
            }
        Map<EntityId, double[]> worldOverrides = new HashMap<>();
        Set<EntityId> shearedLocal = new HashSet<>();
        List<Constraint> stages = new ArrayList<>();
        stages.add(null);
        stages.addAll(clip == null ? d.constraints()
                : d.constraints().stream().map(c -> ConstraintTimeline.envelope(clip, c)).toList());
        for (var stage : stages) {
            if (stage instanceof IkConstraint ik && ik.enabled() && ik.mix() != 0) {
                var b = d.bone(ik.boneIds().get(0));
                var v = local.get(b.id());
                var p = b.parentId() == null ? new double[] { 1, 0 } : world.get(b.parentId());
                var target = world.get(ik.targetId());
                number(2 * p[0] * p[0], "IK determinant multiply/subtract");
                double relative = number(target[1] + p[1], "IK relative target");
                double numerator = number(2 * p[0] * relative, "IK inverse numerators");
                double distance = number((numerator * 10001 + Math.hypot(v[0], v[1])) * 1.001,
                        "IK aim after inverse gate");
                var staticInverse = ClipFloatCertificate.staticInverseBound(d, b.parentId());
                if (staticInverse.isPresent())
                    distance = number((2 * staticInverse.getAsDouble() * relative + Math.hypot(v[0], v[1])) * 1.001,
                            "IK static-ancestor inverse bound");
                if (ik.boneIds().size() == 2) {
                    // The target computes first-segment inverse numerators even when id=0.
                    // A zero target must not hide overflow in child-origin * parent matrix.
                    double childRelative = number(world.get(ik.boneIds().get(1))[1] + p[1],
                            "IK2 first segment relative origin");
                    double childNumerator = number(2 * p[0] * childRelative,
                            "IK2 first inverse numerators before singular zero multiplier");
                    double first = number((childNumerator * 10001 + Math.hypot(v[0], v[1])) * 1.001,
                            "IK2 first inverse vector");
                    if (staticInverse.isPresent())
                        first = number(
                                (2 * staticInverse.getAsDouble() * childRelative + Math.hypot(v[0], v[1])) * 1.001,
                                "IK2 static first inverse vector");
                    number(2 * first * first, "IK2 first segment squared components");
                    certifyIk2(d, ik, local, distance, first);
                }
                number(2 * distance * distance, "IK target distance squares");
                double reach = number(b.length() * v[3], "IK signed length");
                number(reach * reach, "IK length square");
                if (ik.stretch() && (b.length() > 0 || ik.boneIds().size() == 2)) {
                    double factor = number(1 + distance * 10001, "IK stretch factor");
                    v[3] = number(v[3] * factor, "IK post-solve scaleX");
                    if (ik.uniform())
                        v[4] = number(v[4] * factor, "IK post-solve scaleY");
                }
            }
            if (stage instanceof TransformConstraint tc && !tc.inactive()) {
                var target = local.get(tc.targetId());
                var targetWorld = world.get(tc.targetId());
                if (tc.local())
                    for (var id : tc.boneIds()) {
                        var v = local.get(id);
                        for (int n : new int[] { 0, 1, 2 })
                            v[n] = number((v[n] + target[n] + Math.abs(tc.offset().get(n == 2 ? 0 : n + 1))) * 1.001,
                                    "local constrained float component");
                        for (int n = 3; n <= 4; n++)
                            if (tc.mix().get(n) != 0) {
                                double offset = Math.abs(tc.offset().get(n));
                                v[n] = number(
                                        (tc.relative() ? v[n] * (2 + target[n] + offset)
                                                : (2 * v[n] + target[n] + offset) / 1e-12) * 1.001,
                                        "local constrained float scale");
                            }
                        shearedLocal.add(id);
                    }
                else {
                    number(2 * targetWorld[0] * targetWorld[0], "transform target determinant");
                    double targetPosition = position(targetWorld[0], targetWorld[1], tc.offset().get(1),
                            tc.offset().get(2));
                    for (var id : tc.boneIds()) {
                        var old = world.get(id);
                        double offset = Math.max(Math.abs(tc.offset().get(3)), Math.abs(tc.offset().get(4)));
                        number(2 * old[0] * old[0], "transform original column squares");
                        double norm = number(
                                (tc.relative() ? 2 * old[0] * (2 + targetWorld[0] + offset)
                                        : 2 * (old[0] + targetWorld[0] + offset)) * 1.001,
                                "constrained world float matrix");
                        double pos = number((old[1] + targetPosition) * 1.001, "constrained world float position");
                        double inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(id).parentId())
                                .orElseThrow();
                        if (!tc.relative())
                            for (int axis : new int[] { 3, 4 })
                                if (tc.mix().get(axis) != 0) {
                                    double scale = Math
                                            .abs(axis == 3 ? d.bone(id).local().scaleX() : d.bone(id).local().scaleY());
                                    if (scale != 0)
                                        number((2 * old[0] + targetWorld[0] + offset) * 2 * inverse / scale,
                                                "world absolute scale division");
                                }
                        // World constraints call updateAppliedTransform even if no later
                        // constraint reads it. Certify that otherwise-hidden arithmetic.
                        double applied = number(2 * inverse * norm, "target applied local matrix");
                        number(2 * applied * applied, "target applied determinant/dot");
                        number(2 * applied * applied * 10001, "target applied scaleY division");
                        double parentPosition = d.bone(id).parentId() == null ? 0 : world.get(d.bone(id).parentId())[1];
                        number(2 * inverse * (pos + parentPosition), "target applied translation");
                        worldOverrides.put(id, new double[] { norm, pos });
                    }
                }
            }
            if (stage instanceof PathConstraint pc && !pc.inactive()) {
                var slot = d.slots().stream().filter(s -> s.id().equals(pc.targetId())).findFirst().orElseThrow();
                var attachment = d.attachments().stream().filter(a -> a.id().equals(slot.activeAttachmentId()))
                        .findFirst().orElse(null);
                if (attachment instanceof PathAttachment path) {
                    var target = world.get(slot.boneId());
                    var solved = PathConstraintSolver.bounds(pc, path, target[0], target[1], world, d.bones());
                    for (var id : pc.boneIds()) {
                        var old = world.get(id);
                        double length = d.bone(id).length();
                        number(2 * old[0] * old[0] * length * length, "path original bone length squares");
                        var output = solved.get(id);
                        number(output[0], "path solved matrix envelope");
                        number(output[1], "path solved position envelope");
                        number(8 * output[1] * output[1], "path point distance squares");
                        number(4 * output[1] / .00001, "path scale factor");
                        double inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(id).parentId())
                                .orElseThrow(), applied = number(2 * inverse * output[0], "path applied local matrix");
                        number(2 * applied * applied * 10001, "path applied determinant/dot/division");
                        double parentPosition = d.bone(id).parentId() == null ? 0 : world.get(d.bone(id).parentId())[1];
                        number(2 * inverse * (output[1] + parentPosition), "path applied translation");
                    }
                    worldOverrides.putAll(solved);
                }
            }
            if (stage instanceof PhysicsConstraint pc && pc.mix() != 0) {
                if (traced.containsKey(pc.id())) {
                    var old = world.get(pc.boneId());
                    var state = traced.get(pc.id());
                    number(Math.max(state.xy(), Float.MIN_NORMAL), "physics traced XY bound");
                    number(Math.max(state.rotation(), Float.MIN_NORMAL), "physics traced rotation bound");
                    number(Math.max(state.scale(), Float.MIN_NORMAL), "physics traced scale bound");
                    number(Math.max(state.velocity(), Float.MIN_NORMAL), "physics traced velocity bound");
                    number(state.rotation() * (pc.influence().get(2) + pc.influence().get(4)) * pc.mix(),
                            "physics sub-tick angle product");
                    var bounded = PhysicsEnvelope.fromTrace(pc, old[0], old[1], state);
                    double norm = number(bounded.matrixNorm() * 1.001, "physics traced sub-tick matrix"),
                            pos = number(bounded.positionNorm() * 1.001, "physics traced sub-tick position"),
                            inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(pc.boneId()).parentId())
                                    .orElseThrow(),
                            applied = number(2 * inverse * norm, "physics traced applied matrix");
                    number(2 * norm * norm, "physics traced column squares");
                    number(2 * applied * applied * 10001, "physics traced applied determinant/dot/division");
                    double parent = d.bone(pc.boneId()).parentId() == null ? 0
                            : world.get(d.bone(pc.boneId()).parentId())[1];
                    number(2 * inverse * (pos + parent), "physics traced applied position");
                    worldOverrides.put(pc.boneId(), new double[] { norm, pos });
                } else {
                    var bone = d.bone(pc.boneId());
                    var old = world.get(pc.boneId());
                    double seconds = d.animations().stream().mapToDouble(a -> a.duration() == 0 ? 5 : a.duration())
                            .max().orElse(5), gain = PhysicsFloatCertificate.gain(pc, (int) Math.ceil(seconds * 60)),
                            dt = PhysicsConstraint.STEP, force = Math.abs(pc.wind()) + Math.abs(pc.gravity()),
                            inverse = ClipFloatCertificate.staticInverseBound(d, bone.parentId()).orElseThrow(),
                            reach = bone.length() * Math.abs(bone.local().scaleX()) / (2 * inverse),
                            nudge = pc.limit() * 600;
                    double xy = number(gain * (2 * nudge + force * 100 * dt * dt / pc.mass()) * 1.01,
                            "physics XY state envelope"),
                            rotation = number(
                                    gain * (2 * Math.PI + force * bone.length() / 100 * dt * dt / pc.mass()) * 1.01,
                                    "physics rotation state envelope"),
                            scale = number(
                                    gain * ((reach == 0 ? 0 : 4 * nudge / reach) + force * dt * dt / pc.mass()) * 1.01,
                                    "physics scale state envelope");
                    double state = number(Math.max(Math.max(xy, rotation), scale), "physics state maximum");
                    number(state / dt, "physics velocity envelope");
                    number(state * pc.strength() + force * 100 + force * bone.length() / 100,
                            "physics restoring force envelope");
                    number((state * pc.strength() + force * 100 + force * bone.length() / 100) * dt / pc.mass(),
                            "physics velocity increment");
                    number(2 * (old[1] + xy), "physics pre-clamp input difference");
                    number(bone.length() * old[0] + 2 * (old[1] + xy), "physics pole plus displacement");
                    double norm = number(
                            old[0] * (pc.influence().get(4) > 0 ? Math.sqrt(2) : 1)
                                    * (1 + scale * pc.mix() * pc.influence().get(3)) * 1.001,
                            "physics solved matrix envelope"),
                            pos = number(
                                    (old[1] + Math.sqrt(2) * xy * pc.mix()
                                            * Math.max(pc.influence().get(0), pc.influence().get(1))) * 1.001,
                                    "physics solved position envelope"),
                            applied = number(2 * inverse * norm, "physics applied matrix");
                    number(2 * norm * norm, "physics output column squares");
                    number(2 * applied * applied * 10001, "physics applied determinant/dot/division");
                    double parentPosition = bone.parentId() == null ? 0 : world.get(bone.parentId())[1];
                    number(2 * inverse * (pos + parentPosition), "physics applied position inverse");
                    worldOverrides.put(pc.boneId(), new double[] { norm, pos });

                }
            }
            for (var b : d.bones()) {
                double[] v = local.get(b.id()),
                        p = b.parentId() == null ? new double[] { 1, 0 } : world.get(b.parentId());
                if (worldOverrides.containsKey(b.id())) {
                    world.put(b.id(), worldOverrides.get(b.id()));
                    continue;
                }
                double norm = linear(p[0], Math.max(v[3], v[4]) * (shearedLocal.contains(b.id()) || v[5] != 0 || v[6] != 0 ? Math.sqrt(2) : 1));
                double pos = position(p[0], p[1], v[0], v[1]);
                number(pos + norm * b.length(), "world bone tip " + b.name());
                world.put(b.id(), new double[] { norm, pos });
            }
        }
        Map<EntityId, Slot> slots = new HashMap<>();
        d.slots().forEach(s -> slots.put(s.id(), s));
        for (var a : d.attachments()) {
            if (a instanceof PathAttachment path) {
                var p = world.get(slots.get(path.slotId()).boneId());
                for (var v : path.vertices())
                    position(p[0], p[1], v.x(), v.y());
                continue;
            }
            if (a instanceof BoundingBoxAttachment box) {
                var p = world.get(slots.get(box.slotId()).boneId());
                for (var v : box.vertices()) position(p[0], p[1], v.x(), v.y());
                continue;
            }
            if (a instanceof ClippingAttachment c) {
                var p = world.get(slots.get(c.slotId()).boneId());
                for (var v : c.vertices())
                    position(p[0], p[1], v.x(), v.y());
                continue;
            }
            if (a instanceof MeshAttachment m) {
                double[] offsets = clip == null ? new double[m.deformSize()]
                        : DeformTimeline.bounds(clip, m.id(), m.deformSize());
                for (int i = 0; i < offsets.length; i++)
                    if (offsets[i] != 0)
                        offsets[i] = number(offsets[i] * 1.001, "deform float rounding envelope");
                int index = 0;
                if (m.weighted()) {
                    for (var row : m.weights()) {
                        double sum = 0;
                        for (var i : row) {
                            var p = world.get(i.boneId());
                            double x = number(Math.abs(i.point().x()) + offsets[index++], "skin setup+deform x"),
                                    y = number(Math.abs(i.point().y()) + offsets[index++], "skin setup+deform y");
                            number(x * 2, "skin deform delta x");
                            number(y * 2, "skin deform delta y");
                            double bound = position(p[0], p[1], x, y);
                            double next = sum + bound * Math.abs((float) i.weight());
                            sum = next == 0 ? 0
                                    : number(Math.nextUp(next * (1 + 2e-6)), "skin float32 weighted intermediate sum");
                        }
                    }
                    continue;
                }
                var p = world.get(slots.get(m.slotId()).boneId());
                for (var v : m.vertices()) {
                    double x = number(Math.abs(v.x()) + offsets[index++], "mesh setup+deform x"),
                            y = number(Math.abs(v.y()) + offsets[index++], "mesh setup+deform y");
                    number(x * 2, "mesh deform delta x");
                    number(y * 2, "mesh deform delta y");
                    position(p[0], p[1], x, y);
                }
                continue;
            }
            var r = (RegionAttachment) a;
            var t = r.local();
            var p = world.get(slots.get(r.slotId()).boneId());
            double norm = linear(p[0], Math.max(Math.abs(t.scaleX()), Math.abs(t.scaleY()))),
                    pos = position(p[0], p[1], t.x(), t.y());
            number(pos + norm * Math.hypot(r.width() / 2, r.height() / 2), "complete region vertices " + r.name());
        }
    }

    private static void certifyIk2(DocumentSnapshot d, IkConstraint ik, Map<EntityId, double[]> local, double distance,
            double first) throws ProjectFormatException {
        var p = d.bone(ik.boneIds().get(0));
        var c = d.bone(ik.boneIds().get(1));
        var a = local.get(p.id());
        var b = local.get(c.id());
        double l1 = number(Math.max(first, Math.hypot(a[0], a[1])),
                "IK2 first length bound including singular inverse"),
                l2 = number(c.length() * b[3], "IK2 second length bound");
        double ax = number(a[3] * l2, "IK2 ellipse X"), ay = number(a[4] * l2, "IK2 ellipse Y");
        double aa = number(ax * ax, "IK2 ellipse X square"), bb = number(ay * ay, "IK2 ellipse Y square"),
                r2 = number(distance * distance, "IK2 target radius square");
        number(l1 * l1, "IK2 first length square");
        var pt = p.local();
        var ct = c.local();
        boolean uniform = Math.abs(Math.abs(pt.scaleX()) - Math.abs(pt.scaleY())) <= .0001;
        double reach = Math.abs((float) pt.scaleX()) * Math.abs((float) ct.scaleX()) * (float) c.length();
        number((r2 + l1 * l1 + aa) / (2 * .0001 * reach), "IK2 uniform cosine division bound");
        if (!uniform) {
            double q0 = number(bb * l1 * l1 + aa * r2 + aa * bb, "IK2 ellipse q0"),
                    q1 = number(2 * bb * l1, "IK2 ellipse q1");
            double disc = number(q1 * q1 + 4 * (aa + bb) * q0, "IK2 ellipse discriminant bound");
            float fl2 = (float) c.length() * Math.abs((float) ct.scaleX()), fx = Math.abs((float) pt.scaleX()) * fl2,
                    fy = Math.abs((float) pt.scaleY()) * fl2;
            float faa = fx * fx, fbb = fy * fy;
            double denominator = Math.abs(fbb - faa);
            if (denominator == 0)
                throw ProjectCodec.bad("IK2 ellipse", "Nonuniform ellipse quadratic collapses in float32");
            double root = number((q1 + Math.sqrt(disc)) / denominator, "IK2 first quadratic root bound");
            double other = number(q0 / (.000099 * fbb), "IK2 second quadratic root bound");
            number(Math.max(root, other) * Math.max(root, other), "IK2 root square");
            number(root / Math.abs((float) pt.scaleY()), "IK2 child ellipse angle Y division");
            number((root + l1) / Math.abs((float) pt.scaleX()), "IK2 child ellipse angle X division");
        }
        if (ik.softness() > 0) {
            double soft = number(ik.softness() * a[3] * (b[3] + 1) / 2, "IK2 scaled softness");
            if (soft > reach)
                throw ProjectCodec.bad("IK2 softness",
                        "Cannot certify target-origin division when scaled softness exceeds the second reach");
            number((distance + l1 + ax + soft) / (2 * soft), "IK2 softness ratio");
            number((distance + l1 + ax + soft) * 10001, "IK2 softened target factor");
        }
    }

    private static double linear(double parent, double scale) throws ProjectFormatException {
        if (parent == 0 || scale == 0)
            return 0;
        // Target Bone builds X at float(rotation*degRad), Y at
        // float((rotation+90)*degRad).
        // At large angles those axes need not be orthogonal. A unit-rotation norm plus
        // epsilon
        // is UNSOUND over long chains (±1e6°, root scale 1e35 overflows near bone
        // 19706).
        // The two column lengths are each <= scale*(1+rounding), so their Frobenius
        // norm
        // <= sqrt(2)*scale bounds the operator norm independently of their angle error.
        return number(Math.nextUp((parent * (Math.sqrt(2) * scale * (1 + 2e-6) + 8 * Float.MIN_VALUE)) * (1 + 2e-6)
                + 8 * Float.MIN_VALUE), "complete float32 world/region matrix");
    }

    private static double position(double norm, double parent, double x, double y) throws ProjectFormatException {
        if (parent == 0 && (norm == 0 || x == 0 && y == 0))
            return 0;
        return number(Math.nextUp((parent + norm * Math.hypot(x, y)) * (1 + 2e-6) + 8 * Float.MIN_VALUE),
                "float32 world translation");
    }
}
