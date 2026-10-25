package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Strict 4.2 absolute parameter groups. Does not evaluate or supply constraint
 * poses.
 */
final class ConstraintTimelineJson {
    private ConstraintTimelineJson() {
    }

    static String section(ConstraintChannel c) {
        return switch (c) {
            case IK -> "ik";
            case TRANSFORM_MIX -> "transform";
            case PATH_POSITION, PATH_SPACING, PATH_MIX -> "path";
            default -> "physics";
        };
    }

    static String group(ConstraintChannel c) {
        return switch (c) {
            case PATH_POSITION -> "position";
            case PATH_SPACING -> "spacing";
            case PATH_MIX -> "mix";
            default -> c.physicsParameter() >= 0 ? c.name().substring(8).toLowerCase(Locale.ROOT) : "";
        };
    }

    static String[] fields(ConstraintChannel c) {
        return switch (c) {
            case IK -> new String[] { "mix", "softness", "bendPositive", "compress", "stretch" };
            case TRANSFORM_MIX -> new String[] { "mixRotate", "mixX", "mixY", "mixScaleX", "mixScaleY", "mixShearY" };
            case PATH_MIX -> new String[] { "mixRotate", "mixX", "mixY" };
            default -> new String[] { "value" };
        };
    }

    private static double number(JsonNode n, String field, double fallback, String path) throws ProjectFormatException {
        return n.has(field) ? ProjectCodec.number(n, field, path) : fallback;
    }

    private static List<Double> values(JsonNode n, ConstraintChannel c, String path) throws ProjectFormatException {
        var values = new ArrayList<Double>();
        var fields = fields(c);
        for (int i = 0; i < fields.length; i++) {
            if (c.discrete(i)) {
                boolean fallback = i == 2;
                values.add(n.has(fields[i]) ? ProjectCodec.bool(n, fields[i], path) ? 1. : 0. : fallback ? 1. : 0.);
                continue;
            }
            double fallback = c == ConstraintChannel.IK ? i == 0 ? 1 : 0
                    : c == ConstraintChannel.PATH_POSITION || c == ConstraintChannel.PATH_SPACING
                            || c.physicsParameter() >= 0 ? 0 : 1;
            if (i == 2 && c != ConstraintChannel.IK)
                fallback = values.get(1);
            if (i == 4 && c == ConstraintChannel.TRANSFORM_MIX)
                fallback = values.get(3);
            values.add(number(n, fields[i], fallback, path));
        }
        return List.copyOf(values);
    }

    static List<Keyframe> readKeys(JsonNode array, ConstraintChannel c, String p) throws ProjectFormatException {
        if (!array.isArray() || array.isEmpty())
            throw ProjectCodec.bad(p, "Expected nonempty constraint key array");
        var keys = new ArrayList<Keyframe>();
        int continuous = c == ConstraintChannel.IK ? 2 : c.components();
        double previous = -1;
        for (int i = 0; i < array.size(); i++) {
            var n = array.get(i);
            String q = p + "[" + i + "]";
            double time = number(n, "time", 0, q);
            if (time <= previous)
                throw ProjectCodec.bad(q + ".time", "Keys must be strictly increasing");
            previous = time;
            var value = values(n, c, q);
            var curves = new ArrayList<Curve>();
            var curve = n.get("curve");
            if (curve != null && i == array.size() - 1)
                throw ProjectCodec.bad(q + ".curve", "Last key cannot have an outgoing curve");
            if (curve != null && (!curve.isTextual() && !curve.isArray()
                    || curve.isTextual() && !curve.textValue().equals("stepped")
                    || curve.isArray() && curve.size() != 4 * continuous))
                throw ProjectCodec.bad(q + ".curve",
                        "Expected stepped or " + 4 * continuous + " absolute Bezier controls");
            for (int j = 0; j < c.components(); j++) {
                Curve parsed = c.discrete(j) ? Curve.STEPPED : Curve.LINEAR;
                if (!c.discrete(j) && curve != null) {
                    if (curve.isTextual())
                        parsed = Curve.STEPPED;
                    else {
                        double next = number(array.get(i + 1), "time", 0, p + "[" + (i + 1) + "]"), dt = next - time,
                                dv = values(array.get(i + 1), c, p).get(j) - value.get(j);
                        if (dt <= 0)
                            throw ProjectCodec.bad(q + ".time", "Keys must be strictly increasing");
                        double[] v = new double[4];
                        for (int k = 0; k < 4; k++) {
                            var raw = curve.get(j * 4 + k);
                            if (!raw.isNumber())
                                throw ProjectCodec.bad(q + ".curve", "Expected finite numeric controls");
                            v[k] = RuntimeRange.number(raw.doubleValue(), q + ".curve[" + (j * 4 + k) + "]");
                        }
                        if (dv == 0) {
                            if (v[1] != value.get(j) || v[3] != value.get(j))
                                throw ProjectCodec.bad(q + ".curve", "Equal endpoints need constant control values");
                            parsed = new Curve(Curve.Kind.BEZIER, (v[0] - time) / dt, 0, (v[2] - time) / dt, 1);
                        } else
                            parsed = new Curve(Curve.Kind.BEZIER, (v[0] - time) / dt, (v[1] - value.get(j)) / dv,
                                    (v[2] - time) / dt, (v[3] - value.get(j)) / dv);
                    }
                }
                curves.add(parsed);
            }
            keys.add(new Keyframe(EntityId.create(), time, value, curves));
        }
        return List.copyOf(keys);
    }

    static List<AnimationClip> read(JsonNode root, List<AnimationClip> clips, List<Constraint> constraints)
            throws ProjectFormatException {
        var result = new ArrayList<AnimationClip>();
        for (var clip : clips) {
            var node = root.path("animations").path(clip.name());
            var tracks = new ArrayList<ConstraintTimeline>();
            for (var channel : ConstraintChannel.values()) {
                String section = section(channel);
                if (!node.has(section))
                    continue;
                var sectionNode = node.get(section);
                if (!sectionNode.isObject())
                    throw ProjectCodec.bad("$.animations." + clip.name() + "." + section,
                            "Expected constraint-name object");
                for (var it = sectionNode.fields(); it.hasNext();) {
                    var entry = it.next();
                    String p = "$.animations." + clip.name() + "." + section + "." + entry.getKey();
                    String reference = p;
                    var constraint = constraints.stream().filter(c -> c.name().equals(entry.getKey())).findFirst()
                            .orElseThrow(() -> ProjectCodec.bad(reference, "Unknown constraint name"));
                    if (!channel.supports(constraint))
                        throw ProjectCodec.bad(p, "Constraint type does not match timeline section");
                    var raw = entry.getValue();
                    String group = group(channel);
                    if (!group.isEmpty()) {
                        if (!raw.has(group))
                            continue;
                        raw = raw.get(group);
                        p += "." + group;
                    }
                    tracks.add(new ConstraintTimeline(constraint.id(), channel, readKeys(raw, channel, p)));
                }
            }
            result.add(new AnimationClip(clip.id(), clip.name(), clip.timelines(), clip.deforms(), tracks,
                    clip.discrete()));
        }
        return result;
    }

    static void write(ObjectNode node, DocumentSnapshot d, AnimationClip clip) throws ProjectFormatException {
        for (var t : clip.constraintTracks()) {
            var c = t.channel();
            var setup = d.constraint(t.constraintId());
            var section = node.withObject("/" + section(c));
            String name = setup.name().replace("~", "~0").replace("/", "~1");
            ArrayNode out = group(c).isEmpty() ? section.putArray(setup.name())
                    : section.withObject("/" + name).putArray(group(c));
            int continuous = c == ConstraintChannel.IK ? 2 : c.components();
            var fields = fields(c);
            for (int i = 0; i < t.keys().size(); i++) {
                var k = t.keys().get(i);
                var n = out.addObject().put("time", k.time());
                for (int j = 0; j < c.components(); j++) {
                    double value = k.values().get(j);
                    if (!setup.enabled() && (c == ConstraintChannel.TRANSFORM_MIX || c == ConstraintChannel.PATH_MIX
                            || c == ConstraintChannel.IK && j == 0 || c == ConstraintChannel.PHYSICS_MIX))
                        value = 0;
                    if (c.discrete(j))
                        n.put(fields[j], value == 1);
                    else
                        n.put(fields[j], value);
                }
                if (i + 1 == t.keys().size())
                    continue;
                var next = t.keys().get(i + 1);
                boolean stepped = false, other = false, bezier = false;
                for (int j = 0; j < continuous; j++) {
                    var kind = k.curves().get(j).kind();
                    if (k.values().get(j).equals(next.values().get(j)))
                        continue;
                    stepped |= kind == Curve.Kind.STEPPED;
                    other |= kind != Curve.Kind.STEPPED;
                    bezier |= kind == Curve.Kind.BEZIER;
                }
                if (stepped && other)
                    throw ProjectCodec.bad(
                            "animations." + clip.name() + "." + section(c) + "." + setup.name() + ".curve",
                            "4.2 group cannot combine stepped and continuous components; project retains independent curves");
                if (stepped) {
                    n.put("curve", "stepped");
                    continue;
                }
                if (!bezier)
                    continue;
                var controls = n.putArray("curve");
                for (int j = 0; j < continuous; j++) {
                    var curve = k.curves().get(j);
                    double a = n.path(fields[j]).asDouble(), b = next.values().get(j);
                    if (!setup.enabled() && (c == ConstraintChannel.TRANSFORM_MIX || c == ConstraintChannel.PATH_MIX
                            || c == ConstraintChannel.IK && j == 0 || c == ConstraintChannel.PHYSICS_MIX))
                        b = 0;
                    double dt = next.time() - k.time(), dv = b - a;
                    boolean cubic = curve.kind() == Curve.Kind.BEZIER;
                    controls.add(k.time() + dt * (cubic ? curve.x1() : 1. / 3))
                            .add(a + dv * (cubic ? curve.y1() : 1. / 3))
                            .add(k.time() + dt * (cubic ? curve.x2() : 2. / 3))
                            .add(a + dv * (cubic ? curve.y2() : 2. / 3));
                }
            }
        }
    }

    static void scan(JsonNode animation, String p, List<String> errors) throws ProjectFormatException {
        for (String section : List.of("ik", "transform", "path", "physics"))
            if (animation.has(section)) {
                var value = animation.get(section);
                if (!value.isObject())
                    throw ProjectCodec.bad(p + "." + section, "Expected constraint-name object");
                for (var it = value.fields(); it.hasNext();) {
                    var entry = it.next();
                    String q = p + "." + section + "." + entry.getKey();
                    if (section.equals("physics") && entry.getKey().isEmpty()) {
                        errors.add(q + "<global>");
                        continue;
                    }
                    if (section.equals("path") || section.equals("physics")) {
                        if (!entry.getValue().isObject())
                            throw ProjectCodec.bad(q, "Expected parameter group object");
                        for (var gi = entry.getValue().fields(); gi.hasNext();) {
                            var group = gi.next();
                            ConstraintChannel channel = null;
                            for (var candidate : ConstraintChannel.values())
                                if (section(candidate).equals(section) && group(candidate).equals(group.getKey()))
                                    channel = candidate;
                            if (channel == null) {
                                errors.add(q + "." + group.getKey());
                                continue;
                            }
                            scanKeys(group.getValue(), channel, q + "." + group.getKey(), errors);
                        }
                    } else
                        scanKeys(entry.getValue(),
                                section.equals("ik") ? ConstraintChannel.IK : ConstraintChannel.TRANSFORM_MIX, q,
                                errors);
                }
            }
    }

    private static void scanKeys(JsonNode keys, ConstraintChannel c, String p, List<String> errors)
            throws ProjectFormatException {
        if (!keys.isArray())
            throw ProjectCodec.bad(p, "Expected key array");
        Set<String> names = new HashSet<>(List.of(fields(c)));
        names.addAll(List.of("time", "curve"));
        int i = 0;
        for (var key : keys) {
            String q = p + "[" + i++ + "]";
            if (!key.isObject())
                throw ProjectCodec.bad(q, "Expected key object");
            key.fieldNames().forEachRemaining(n -> {
                if (!names.contains(n))
                    errors.add(q + "." + n);
            });
        }
    }
}
