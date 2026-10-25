package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;

/**
 * Own versioned schema. Strict field/type checks prevent coercion or silent
 * loss of future fields.
 */
public final class ProjectCodec {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).maxStringLength(1_000_000)
                    .maxNumberLength(256).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public DocumentSnapshot read(Path path) throws IOException {
        requireProjectPath(path);
        try (InputStream input = Files.newInputStream(path)) {
            return decode(input.readNBytes(MAX_BYTES + 1));
        }
    }

    public DocumentSnapshot decode(byte[] bytes) throws ProjectFormatException {
        JsonNode root = parseTree(bytes);
        try {
            if (!root.isObject() || !root.has("schemaVersion"))
                throw bad("$.schemaVersion", "Missing required field");
            JsonNode version = root.get("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1
                    || version.intValue() > 7)
                throw bad("$.schemaVersion", "Unsupported schema version: " + version);
            if (version.intValue() == 1)
                fields(root, "$", "schemaVersion", "bones", "slots", "attachments");
            else if (version.intValue() >= 6)
                fields(root, "$", "schemaVersion", "bones", "slots", "attachments", "animations", "constraints",
                        "resources");
            else if (version.intValue() >= 4)
                fields(root, "$", "schemaVersion", "bones", "slots", "attachments", "animations", "constraints");
            else
                fields(root, "$", "schemaVersion", "bones", "slots", "attachments", "animations");
            List<Bone> bones = new ArrayList<>();
            List<Slot> slots = new ArrayList<>();
            List<Attachment> attachments = new ArrayList<>();
            int index = 0;
            for (JsonNode n : array(root, "bones", "$")) {
                String p = "$.bones[" + index++ + "]";
                fields(n, p, "id", "name", "parentId", "local", "length");
                bones.add(new Bone(id(n, "id", p), text(n, "name", p), nullableId(n, "parentId", p),
                        transform(n.get("local"), p + ".local"), number(n, "length", p)));
            }
            index = 0;
            for (JsonNode n : array(root, "slots", "$")) {
                String p = "$.slots[" + index++ + "]";
                if (version.intValue() >= 7)
                    fields(n, p, "id", "name", "boneId", "activeAttachmentId", "blend", "color");
                else if (version.intValue() >= 6)
                    fields(n, p, "id", "name", "boneId", "activeAttachmentId", "blend");
                else
                    fields(n, p, "id", "name", "boneId", "activeAttachmentId");
                slots.add(new Slot(id(n, "id", p), text(n, "name", p), id(n, "boneId", p),
                        nullableId(n, "activeAttachmentId", p),
                        version.intValue() >= 6 ? Slot.Blend.valueOf(text(n, "blend", p)) : Slot.Blend.NORMAL,
                        version.intValue() >= 7 ? P8ProjectJson.readColor(n,p) : Rgba.WHITE));
            }
            index = 0;
            for (JsonNode n : array(root, "attachments", "$")) {
                String p = "$.attachments[" + index++ + "]";
                if (text(n, "type", p).equals("path") && version.intValue() >= 4) {
                    fields(n, p, "type", "id", "name", "slotId", "vertices", "closed", "constantSpeed", "lengths");
                    attachments.add(new PathAttachment(id(n, "id", p), text(n, "name", p), id(n, "slotId", p),
                            points(n, "vertices", p), bool(n, "closed", p), bool(n, "constantSpeed", p),
                            numbers(n, "lengths", p)));
                    continue;
                }
                if (text(n, "type", p).equals("clipping") && version.intValue() >= 3) {
                    fields(n, p, "type", "id", "name", "slotId", "vertices", "endSlotId");
                    attachments.add(new ClippingAttachment(id(n, "id", p), text(n, "name", p), id(n, "slotId", p),
                            points(n, "vertices", p), nullableId(n, "endSlotId", p)));
                    continue;
                }
                if (text(n, "type", p).equals("mesh") && version.intValue() >= 3) {
                    if (n.has("weights"))
                        fields(n, p, "type", "id", "name", "slotId", "source", "vertices", "uvs", "hull", "triangles",
                                "weights");
                    else
                        fields(n, p, "type", "id", "name", "slotId", "source", "vertices", "uvs", "hull", "triangles");
                    List<List<Influence>> weights = new ArrayList<>();
                    if (n.has("weights"))
                        for (var row : array(n, "weights", p)) {
                            if (!row.isArray())
                                throw bad(p + ".weights", "Expected influence array");
                            List<Influence> values = new ArrayList<>();
                            for (var i : row) {
                                fields(i, p + ".weights", "boneId", "x", "y", "weight");
                                values.add(
                                        new Influence(id(i, "boneId", p), new io.github.samlinb.openbone.core.math.Vec2(
                                                number(i, "x", p), number(i, "y", p)), number(i, "weight", p)));
                            }
                            weights.add(values);
                        }
                    attachments.add(new MeshAttachment(id(n, "id", p), text(n, "name", p), id(n, "slotId", p),
                            text(n, "source", p), points(n, "vertices", p), points(n, "uvs", p),
                            integer(n.get("hull"), p + ".hull"), integers(n, "triangles", p), weights));
                    continue;
                }
                fields(n, p, "type", "id", "name", "slotId", "source", "local", "width", "height");
                if (!text(n, "type", p).equals("region"))
                    throw bad(p + ".type", "Unsupported attachment type");
                attachments.add(new RegionAttachment(id(n, "id", p), text(n, "name", p), id(n, "slotId", p),
                        text(n, "source", p),
                        transform(n.get("local"), p + ".local"), number(n, "width", p), number(n, "height", p)));
            }
            if (version.intValue() == 1)
                return new DocumentSnapshot(bones, slots, attachments);
            List<AnimationClip> animations = new ArrayList<>();
            index = 0;
            for (var a : array(root, "animations", "$")) {
                String p = "$.animations[" + index++ + "]";
                if (version.intValue() >= 6)
                    fields(a, p, "id", "name", "timelines", "deforms", "constraintTracks", "discrete");
                else if (version.intValue() >= 5)
                    fields(a, p, "id", "name", "timelines", "deforms", "constraintTracks");
                else if (version.intValue() >= 3 && a.has("deforms"))
                    fields(a, p, "id", "name", "timelines", "deforms");
                else
                    fields(a, p, "id", "name", "timelines");
                List<BoneTimeline> tracks = new ArrayList<>();
                int ti = 0;
                for (var t : array(a, "timelines", p)) {
                    String q = p + ".timelines[" + ti++ + "]";
                    fields(t, q, "boneId", "channel", "keys");
                    Channel channel = Channel.valueOf(text(t, "channel", q));
                    List<Keyframe> keys = new ArrayList<>();
                    int ki = 0;
                    for (var k : array(t, "keys", q)) {
                        String r = q + ".keys[" + ki++ + "]";
                        fields(k, r, "id", "time", "values", "curves");
                        List<Double> values = new ArrayList<>();
                        List<Curve> curves = new ArrayList<>();
                        for (var v : array(k, "values", r)) {
                            if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
                                throw bad(r + ".values", "Expected finite number");
                            values.add(v.doubleValue());
                        }
                        for (var c : array(k, "curves", r)) {
                            fields(c, r + ".curves", "kind", "x1", "y1", "x2", "y2");
                            curves.add(new Curve(Curve.Kind.valueOf(text(c, "kind", r)), number(c, "x1", r),
                                    number(c, "y1", r), number(c, "x2", r), number(c, "y2", r)));
                        }
                        keys.add(new Keyframe(id(k, "id", r), number(k, "time", r), values, curves));
                    }
                    tracks.add(new BoneTimeline(id(t, "boneId", q), channel, keys));
                }
                List<DeformTimeline> deforms = new ArrayList<>();
                if (a.has("deforms"))
                    for (var t : array(a, "deforms", p)) {
                        fields(t, p + ".deforms", "attachmentId", "keys");
                        List<Keyframe> keys = new ArrayList<>();
                        for (var k : array(t, "keys", p)) {
                            fields(k, p, "id", "time", "values", "curves");
                            List<Double> values = new ArrayList<>();
                            for (var v : array(k, "values", p)) {
                                if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
                                    throw bad(p, "Expected finite deform offset");
                                values.add(v.doubleValue());
                            }
                            List<Curve> curves = new ArrayList<>();
                            for (var c : array(k, "curves", p)) {
                                fields(c, p, "kind", "x1", "y1", "x2", "y2");
                                curves.add(new Curve(Curve.Kind.valueOf(text(c, "kind", p)), number(c, "x1", p),
                                        number(c, "y1", p), number(c, "x2", p), number(c, "y2", p)));
                            }
                            keys.add(new Keyframe(id(k, "id", p), number(k, "time", p), values, curves));
                        }
                        deforms.add(new DeformTimeline(id(t, "attachmentId", p), keys));
                    }
                List<ConstraintTimeline> constraintTracks = new ArrayList<>();
                if (a.has("constraintTracks"))
                    for (var t : array(a, "constraintTracks", p)) {
                        fields(t, p + ".constraintTracks", "constraintId", "channel", "keys");
                        constraintTracks.add(new ConstraintTimeline(id(t, "constraintId", p),
                                ConstraintChannel.valueOf(text(t, "channel", p)),
                                readKeys(t, p + ".constraintTracks")));
                    }
                if(version.intValue()<7 && a.path("discrete").has("colors")) throw bad(p+".discrete.colors","Color timelines require schema 7");
                animations.add(new AnimationClip(id(a, "id", p), text(a, "name", p), tracks, deforms, constraintTracks,
                        version.intValue() >= 6 ? P8ProjectJson.readTracks(a) : DiscreteTracks.EMPTY));
            }
            List<Constraint> constraints = new ArrayList<>();
            index = 0;
            if (version.intValue() >= 4)
                for (var n : array(root, "constraints", "$")) {
                    String p = "$.constraints[" + index++ + "]";
                    if (text(n, "type", p).equals("physics")) {
                        fields(n, p, "type", "id", "name", "order", "enabled", "boneId", "influence", "limit",
                                "parameters");
                        constraints.add(new PhysicsConstraint(id(n, "id", p), text(n, "name", p),
                                integer(n.get("order"), p + ".order"), bool(n, "enabled", p), id(n, "boneId", p),
                                numbers(n, "influence", p), number(n, "limit", p), numbers(n, "parameters", p)));
                        continue;
                    }
                    if (text(n, "type", p).equals("path")) {
                        fields(n, p, "type", "id", "name", "order", "enabled", "boneIds", "targetId", "positionMode",
                                "spacingMode", "rotateMode", "position", "spacing", "rotation", "mixRotate", "mixX",
                                "mixY");
                        var chain = new ArrayList<EntityId>();
                        for (var value : array(n, "boneIds", p)) {
                            if (!value.isTextual())
                                throw bad(p + ".boneIds", "Expected UUID string");
                            chain.add(new EntityId(value.textValue()));
                        }
                        constraints.add(new PathConstraint(id(n, "id", p), text(n, "name", p),
                                integer(n.get("order"), p + ".order"), bool(n, "enabled", p), chain,
                                id(n, "targetId", p), PathConstraint.PositionMode.valueOf(text(n, "positionMode", p)),
                                PathConstraint.SpacingMode.valueOf(text(n, "spacingMode", p)),
                                PathConstraint.RotateMode.valueOf(text(n, "rotateMode", p)), number(n, "position", p),
                                number(n, "spacing", p), number(n, "rotation", p), number(n, "mixRotate", p),
                                number(n, "mixX", p), number(n, "mixY", p)));
                        continue;
                    }
                    if (text(n, "type", p).equals("transform")) {
                        fields(n, p, "type", "id", "name", "order", "enabled", "boneIds", "targetId", "local",
                                "relative", "mix", "offset");
                        List<EntityId> chain = new ArrayList<>();
                        for (var value : array(n, "boneIds", p)) {
                            if (!value.isTextual())
                                throw bad(p + ".boneIds", "Expected UUID string");
                            chain.add(new EntityId(value.textValue()));
                        }
                        constraints.add(new TransformConstraint(id(n, "id", p), text(n, "name", p),
                                integer(n.get("order"), p + ".order"), bool(n, "enabled", p), chain,
                                id(n, "targetId", p), bool(n, "local", p), bool(n, "relative", p), numbers(n, "mix", p),
                                numbers(n, "offset", p)));
                        continue;
                    }
                    if (!text(n, "type", p).equals("ik"))
                        throw bad(p + ".type", "Unsupported constraint type");
                    fields(n, p, "type", "id", "name", "order", "enabled", "boneIds", "targetId", "mix", "softness",
                            "bendPositive", "compress", "stretch", "uniform");
                    List<EntityId> chain = new ArrayList<>();
                    for (var id : array(n, "boneIds", p)) {
                        if (!id.isTextual())
                            throw bad(p + ".boneIds", "Expected UUID string");
                        chain.add(new EntityId(id.textValue()));
                    }
                    constraints.add(new IkConstraint(id(n, "id", p), text(n, "name", p),
                            integer(n.get("order"), p + ".order"), bool(n, "enabled", p), chain, id(n, "targetId", p),
                            number(n, "mix", p), number(n, "softness", p), bool(n, "bendPositive", p),
                            bool(n, "compress", p), bool(n, "stretch", p), bool(n, "uniform", p)));
                }
            return new DocumentSnapshot(bones, slots, attachments, animations, constraints,
                    version.intValue() >= 6 ? P8ProjectJson.readResources(root) : Resources.EMPTY);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ProjectFormatException("Invalid project model: " + invalid.getMessage(), invalid);
        }
    }

    static List<Keyframe> readKeys(JsonNode track, String p) throws ProjectFormatException {
        List<Keyframe> keys = new ArrayList<>();
        int index = 0;
        for (var k : array(track, "keys", p)) {
            String q = p + ".keys[" + index++ + "]";
            fields(k, q, "id", "time", "values", "curves");
            List<Curve> curves = new ArrayList<>();
            for (var c : array(k, "curves", q)) {
                fields(c, q + ".curves", "kind", "x1", "y1", "x2", "y2");
                curves.add(new Curve(Curve.Kind.valueOf(text(c, "kind", q)), number(c, "x1", q), number(c, "y1", q),
                        number(c, "x2", q), number(c, "y2", q)));
            }
            keys.add(new Keyframe(id(k, "id", q), number(k, "time", q), numbers(k, "values", q), curves));
        }
        return keys;
    }

    static void putKeys(ArrayNode array, List<Keyframe> keys) {
        for (var k : keys) {
            var n = array.addObject().put("id", k.id().value()).put("time", k.time());
            k.values().forEach(n.putArray("values")::add);
            var curves = n.putArray("curves");
            for (var c : k.curves())
                curves.addObject().put("kind", c.kind().name()).put("x1", c.x1()).put("y1", c.y1()).put("x2", c.x2())
                        .put("y2", c.y2());
        }
    }

    public byte[] encode(DocumentSnapshot model) throws IOException {
        Objects.requireNonNull(model, "model");
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", model.slots().stream().anyMatch(s -> !s.color().equals(Rgba.WHITE))
                || model.animations().stream().anyMatch(a -> !a.discrete().colors().isEmpty()) ? 7 : P8ProjectJson.needed(model) ? 6
                : model.animations().stream().anyMatch(a -> !a.constraintTracks().isEmpty()) ? 5
                        : !model.constraints().isEmpty()
                                || model.attachments().stream().anyMatch(a -> a instanceof PathAttachment)
                                        ? 4
                                        : model.attachments().stream().anyMatch(a -> !(a instanceof RegionAttachment))
                                                ? 3
                                                : SCHEMA_VERSION);
        if (root.path("schemaVersion").asInt() >= 6)
            P8ProjectJson.writeResources(root, model);
        ArrayNode bones = root.putArray("bones"), slots = root.putArray("slots"),
                attachments = root.putArray("attachments");
        for (Bone b : model.bones()) {
            ObjectNode n = bones.addObject();
            n.put("id", b.id().value());
            n.put("name", b.name());
            putId(n, "parentId", b.parentId());
            n.set("local", transformNode(b.local()));
            n.put("length", b.length());
        }
        for (Slot s : model.slots()) {
            ObjectNode n = slots.addObject();
            n.put("id", s.id().value());
            n.put("name", s.name());
            n.put("boneId", s.boneId().value());
            putId(n, "activeAttachmentId", s.activeAttachmentId());
            if (root.path("schemaVersion").asInt() >= 6)
                n.put("blend", s.blend().name());
            if (root.path("schemaVersion").asInt() >= 7) s.color().values().forEach(n.putArray("color")::add);
        }
        for (Attachment a : model.attachments()) {
            if (a instanceof PathAttachment path) {
                var n = attachments.addObject().put("type", "path").put("id", path.id().value())
                        .put("name", path.name()).put("slotId", path.slotId().value()).put("closed", path.closed())
                        .put("constantSpeed", path.constantSpeed());
                putPoints(n.putArray("vertices"), path.vertices());
                path.lengths().forEach(n.putArray("lengths")::add);
                continue;
            }
            if (a instanceof ClippingAttachment c) {
                var n = attachments.addObject().put("type", "clipping").put("id", c.id().value()).put("name", c.name())
                        .put("slotId", c.slotId().value());
                putPoints(n.putArray("vertices"), c.vertices());
                putId(n, "endSlotId", c.endSlotId());
                continue;
            }
            if (a instanceof MeshAttachment m) {
                var n = attachments.addObject();
                n.put("type", "mesh");
                n.put("id", m.id().value());
                n.put("name", m.name());
                n.put("slotId", m.slotId().value());
                n.put("source", m.source());
                putPoints(n.putArray("vertices"), m.vertices());
                putPoints(n.putArray("uvs"), m.uvs());
                n.put("hull", m.hull());
                m.triangles().forEach(n.putArray("triangles")::add);
                if (m.weighted()) {
                    var weights = n.putArray("weights");
                    for (var row : m.weights()) {
                        var influences = weights.addArray();
                        for (var i : row)
                            influences.addObject().put("boneId", i.boneId().value()).put("x", i.point().x())
                                    .put("y", i.point().y()).put("weight", i.weight());
                    }
                }
                continue;
            }
            if (!(a instanceof RegionAttachment r))
                throw new ProjectFormatException("Unsupported attachment kind");
            ObjectNode n = attachments.addObject();
            n.put("type", "region");
            n.put("id", r.id().value());
            n.put("name", r.name());
            n.put("slotId", r.slotId().value());
            n.put("source", r.source());
            n.set("local", transformNode(r.local()));
            n.put("width", r.width());
            n.put("height", r.height());
        }
        ArrayNode animations = root.putArray("animations");
        for (var a : model.animations()) {
            var n = animations.addObject();
            n.put("id", a.id().value());
            n.put("name", a.name());
            if (root.path("schemaVersion").asInt() >= 6)
                P8ProjectJson.writeTracks(n, a.discrete());
            var timelines = n.putArray("timelines");
            for (var t : a.timelines()) {
                var track = timelines.addObject();
                track.put("boneId", t.boneId().value());
                track.put("channel", t.channel().name());
                var keys = track.putArray("keys");
                for (var k : t.keys()) {
                    var key = keys.addObject();
                    key.put("id", k.id().value());
                    key.put("time", k.time());
                    var values = key.putArray("values");
                    k.values().forEach(values::add);
                    var curves = key.putArray("curves");
                    for (var c : k.curves()) {
                        var curve = curves.addObject();
                        curve.put("kind", c.kind().name());
                        curve.put("x1", c.x1());
                        curve.put("y1", c.y1());
                        curve.put("x2", c.x2());
                        curve.put("y2", c.y2());
                    }
                }
            }
            if (root.path("schemaVersion").asInt() >= 5) {
                var tracks = n.putArray("constraintTracks");
                for (var t : a.constraintTracks()) {
                    var track = tracks.addObject().put("constraintId", t.constraintId().value()).put("channel",
                            t.channel().name());
                    putKeys(track.putArray("keys"), t.keys());
                }
            }
            if (!a.deforms().isEmpty() || root.path("schemaVersion").asInt() >= 5) {
                var tracks = n.putArray("deforms");
                for (var t : a.deforms()) {
                    var track = tracks.addObject().put("attachmentId", t.attachmentId().value());
                    var keys = track.putArray("keys");
                    for (var k : t.keys()) {
                        var key = keys.addObject().put("id", k.id().value()).put("time", k.time());
                        k.values().forEach(key.putArray("values")::add);
                        var curves = key.putArray("curves");
                        for (var c : k.curves())
                            curves.addObject().put("kind", c.kind().name()).put("x1", c.x1()).put("y1", c.y1())
                                    .put("x2", c.x2()).put("y2", c.y2());
                    }
                }
            }
        }
        if (root.path("schemaVersion").asInt() >= 4) {
            var constraints = root.putArray("constraints");
            for (var c : model.constraints()) {
                if (c instanceof PhysicsConstraint p) {
                    var n = constraints.addObject().put("type", "physics").put("id", p.id().value())
                            .put("name", p.name()).put("order", p.order()).put("enabled", p.enabled())
                            .put("boneId", p.boneId().value()).put("limit", p.limit());
                    p.influence().forEach(n.putArray("influence")::add);
                    p.parameters().forEach(n.putArray("parameters")::add);
                    continue;
                }
                if (c instanceof PathConstraint p) {
                    var n = constraints.addObject().put("type", "path").put("id", p.id().value()).put("name", p.name())
                            .put("order", p.order()).put("enabled", p.enabled()).put("targetId", p.targetId().value())
                            .put("positionMode", p.positionMode().name()).put("spacingMode", p.spacingMode().name())
                            .put("rotateMode", p.rotateMode().name()).put("position", p.position())
                            .put("spacing", p.spacing()).put("rotation", p.rotation()).put("mixRotate", p.mixRotate())
                            .put("mixX", p.mixX()).put("mixY", p.mixY());
                    p.boneIds().forEach(id -> n.withArray("boneIds").add(id.value()));
                    continue;
                }
                if (c instanceof TransformConstraint t) {
                    var n = constraints.addObject().put("type", "transform").put("id", t.id().value())
                            .put("name", t.name()).put("order", t.order()).put("enabled", t.enabled())
                            .put("targetId", t.targetId().value()).put("local", t.local())
                            .put("relative", t.relative());
                    t.boneIds().forEach(id -> n.withArray("boneIds").add(id.value()));
                    t.mix().forEach(n.putArray("mix")::add);
                    t.offset().forEach(n.putArray("offset")::add);
                    continue;
                }
                if (!(c instanceof IkConstraint ik))
                    throw new ProjectFormatException("Unsupported constraint kind");
                var n = constraints.addObject().put("type", "ik").put("id", ik.id().value()).put("name", ik.name())
                        .put("order", ik.order()).put("enabled", ik.enabled()).put("targetId", ik.targetId().value())
                        .put("mix", ik.mix()).put("softness", ik.softness()).put("bendPositive", ik.bendPositive())
                        .put("compress", ik.compress()).put("stretch", ik.stretch()).put("uniform", ik.uniform());
                ik.boneIds().forEach(id -> n.withArray("boneIds").add(id.value()));
            }
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAX_BYTES)
            throw new ProjectFormatException("Project exceeds " + MAX_BYTES + " bytes");
        return bytes;
    }

    private static void putPoints(ArrayNode out, List<io.github.samlinb.openbone.core.math.Vec2> v) {
        for (var p : v) {
            out.add(p.x());
            out.add(p.y());
        }
    }

    static List<io.github.samlinb.openbone.core.math.Vec2> points(JsonNode n, String key, String p)
            throws ProjectFormatException {
        var a = array(n, key, p);
        if (a.size() % 2 != 0)
            throw bad(p + "." + key, "Expected XY pairs");
        List<io.github.samlinb.openbone.core.math.Vec2> v = new ArrayList<>();
        for (int i = 0; i < a.size(); i += 2) {
            if (!a.get(i).isNumber() || !a.get(i + 1).isNumber())
                throw bad(p + "." + key, "Expected numeric XY pairs");
            v.add(new io.github.samlinb.openbone.core.math.Vec2(a.get(i).doubleValue(), a.get(i + 1).doubleValue()));
        }
        return v;
    }

    static int integer(JsonNode n, String p) throws ProjectFormatException {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt())
            throw bad(p, "Expected integer");
        return n.intValue();
    }

    static List<Integer> integers(JsonNode n, String key, String p) throws ProjectFormatException {
        List<Integer> values = new ArrayList<>();
        for (var v : array(n, key, p))
            values.add(integer(v, p + "." + key));
        return values;
    }

    private ObjectNode transformNode(Transform t) {
        ObjectNode n = mapper.createObjectNode();
        n.put("x", t.x());
        n.put("y", t.y());
        n.put("rotation", t.rotation());
        n.put("scaleX", t.scaleX());
        n.put("scaleY", t.scaleY());
        return n;
    }

    private static void putId(ObjectNode n, String key, EntityId id) {
        if (id == null)
            n.putNull(key);
        else
            n.put(key, id.value());
    }

    private static Transform transform(JsonNode n, String path) throws ProjectFormatException {
        fields(n, path, "x", "y", "rotation", "scaleX", "scaleY");
        return new Transform(number(n, "x", path), number(n, "y", path), number(n, "rotation", path),
                number(n, "scaleX", path), number(n, "scaleY", path));
    }

    JsonNode parseTree(byte[] bytes) throws ProjectFormatException {
        if (bytes.length > MAX_BYTES)
            throw new ProjectFormatException("Project exceeds " + MAX_BYTES + " bytes");
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null)
                throw new ProjectFormatException("Empty JSON input");
            return root;
        } catch (IOException malformed) {
            throw new ProjectFormatException("Malformed JSON: " + malformed.getMessage(), malformed);
        }
    }

    static void fields(JsonNode node, String path, String... expected) throws ProjectFormatException {
        if (node == null || !node.isObject())
            throw bad(path, "Expected object");
        Set<String> allowed = Set.of(expected);
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key))
                throw bad(path + "." + key, "Unknown field");
        }
        for (String key : expected)
            if (!node.has(key))
                throw bad(path + "." + key, "Missing required field");
    }

    static JsonNode array(JsonNode node, String key, String path) throws ProjectFormatException {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray())
            throw bad(path + "." + key, "Expected array");
        return value;
    }

    static String text(JsonNode node, String key, String path) throws ProjectFormatException {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual())
            throw bad(path + "." + key, "Expected string");
        return value.textValue();
    }

    static double number(JsonNode node, String key, String path) throws ProjectFormatException {
        JsonNode value = node.get(key);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw bad(path + "." + key, "Expected finite number");
        return value.doubleValue();
    }

    static boolean bool(JsonNode n, String key, String path) throws ProjectFormatException {
        var v = n.get(key);
        if (v == null || !v.isBoolean())
            throw bad(path + "." + key, "Expected boolean");
        return v.booleanValue();
    }

    static List<Double> numbers(JsonNode n, String key, String p) throws ProjectFormatException {
        List<Double> result = new ArrayList<>();
        for (var v : array(n, key, p)) {
            if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
                throw bad(p + "." + key, "Expected finite number array");
            result.add(v.doubleValue());
        }
        return result;
    }

    private static EntityId id(JsonNode node, String key, String path) throws ProjectFormatException {
        try {
            return new EntityId(text(node, key, path));
        } catch (IllegalArgumentException invalid) {
            throw bad(path + "." + key, "Expected canonical UUID");
        }
    }

    private static EntityId nullableId(JsonNode node, String key, String path) throws ProjectFormatException {
        return node.get(key).isNull() ? null : id(node, key, path);
    }

    static ProjectFormatException bad(String path, String message) {
        return new ProjectFormatException(path + ": " + message);
    }

    public static void requireProjectPath(Path path) throws ProjectFormatException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".spine") || name.endsWith(".skel"))
            throw new ProjectFormatException("Native .spine/.skel files are unsupported; use own schemaVersion=2 JSON");
    }
}
