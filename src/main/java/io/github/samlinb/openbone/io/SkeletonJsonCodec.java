package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;

/**
 * Editable, explicitly bounded JSON subset. 4.2.22 imports are strict;
 * 3.8.x imports translate legacy fields and report filtered configurations.
 * No official runtime class or source is included. Schema projects retain IDs;
 * exports use names.
 */
public final class SkeletonJsonCodec {
    public static final String VERSION = "4.2.22";
    public static final String VERSION_38 = Spine38Json.VERSION;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> warnings = new ArrayList<>();
    private final Map<Path,java.awt.image.BufferedImage> importedImages = new HashMap<>();
    private String sourceVersion;
    /** Actual source header version, separate from the translated internal JSON contract. */
    public Optional<String> sourceVersion() { return Optional.ofNullable(sourceVersion); }
    public List<String> warnings() { return warnings.stream().distinct().toList(); }

    /** Compatibility policies do not select a wire format or perform schema conversion.
     * Only SPINE_38_JSON enters Spine38Json.normalize/recover38; binary 4.0 is already translated. */
    private enum ImportMode {
        STRICT_42, SPINE_38_JSON, SPINE_40_BINARY, SPINE_VERSION_JSON;
        boolean compatibleResources() { return this != STRICT_42; }
    }

    public byte[] encode(DocumentSnapshot d, Map<EntityId, String> regionPaths, String images, String version) throws IOException {
        if (VERSION.equals(version)) return encode(d, regionPaths, images);
        var target=SpineVersion.target(version);
        ObjectNode root = (ObjectNode)json.readTree(encode(d, regionPaths, images));
        if(target.legacy())Spine38Json.export(root, d);
        SpineVersionJson.export(root,target,version);
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > ProjectCodec.MAX_BYTES) throw new ProjectFormatException("Export exceeds 16 MiB");
        return bytes;
    }

    public byte[] encode(DocumentSnapshot d, Map<EntityId, String> regionPaths, String images) throws IOException {
        RuntimeRange.validate(d);
        // Runtime Skeleton assumes exactly one root at index 0. Multiple roots are
        // valid projects.
        if (d.bones().isEmpty() || d.bones().stream().filter(b -> b.parentId() == null).count() != 1)
            throw ProjectCodec.bad("$.bones", "Runtime export requires exactly one root bone");
        var root = json.createObjectNode();
        root.putObject("skeleton").put("spine", VERSION).put("images", images);
        SpineMetadataJson.writeSkeleton((ObjectNode)root.get("skeleton"), d.resources().metadata());
        if (d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint))
            ((ObjectNode) root.get("skeleton")).put("referenceScale", 100);
        var bones = root.putArray("bones");
        for (var b : d.bones()) {
            var n = bones.addObject().put("name", b.name());
            if (b.parentId() != null)
                n.put("parent", d.bone(b.parentId()).name());
            putTransform(n, b.local());
            n.put("length", b.length());
            n.put("inherit", b.inherit().name());
            if (!b.color().equals(Rgba.WHITE)) n.put("color", b.color().hex());
        }
        Map<EntityId, Integer> constraintOrder = new HashMap<>();
        for (int order = 0; order < d.constraints().size(); order++)
            constraintOrder.put(d.constraints().get(order).id(), order);
        if (d.constraints().stream().anyMatch(c -> c instanceof IkConstraint)) {
            var constraints = root.putArray("ik");
            for (var c : d.constraints())
                if (c instanceof IkConstraint ik) {
                    var n = constraints.addObject().put("name", ik.name()).put("order", constraintOrder.get(ik.id()))
                            .put("target", d.bone(ik.targetId()).name()).put("mix", ik.enabled() ? ik.mix() : 0)
                            .put("softness", ik.softness()).put("bendPositive", ik.bendPositive())
                            .put("compress", ik.compress()).put("stretch", ik.stretch()).put("uniform", ik.uniform());
                    ik.boneIds().forEach(id -> n.withArray("bones").add(d.bone(id).name()));
                }
        }
        if (d.constraints().stream().anyMatch(c -> c instanceof TransformConstraint)) {
            var constraints = root.putArray("transform");
            for (var c : d.constraints())
                if (c instanceof TransformConstraint t) {
                    var n = constraints.addObject().put("name", t.name()).put("order", constraintOrder.get(t.id()))
                            .put("target", d.bone(t.targetId()).name()).put("local", t.local())
                            .put("relative", t.relative());
                    t.boneIds().forEach(id -> n.withArray("bones").add(d.bone(id).name()));
                    String[] mixes = { "mixRotate", "mixX", "mixY", "mixScaleX", "mixScaleY", "mixShearY" },
                            offsets = { "rotation", "x", "y", "scaleX", "scaleY", "shearY" };
                    for (int i = 0; i < 6; i++) {
                        n.put(mixes[i], t.enabled() ? t.mix().get(i) : 0);
                        n.put(offsets[i], t.offset().get(i));
                    }
                }
        }
        Map<EntityId, Attachment> attachments = new HashMap<>();
        d.attachments().forEach(a -> attachments.put(a.id(), a));
        if (d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint)) {
            var constraints = root.putArray("physics");
            for (var c : d.constraints())
                if (c instanceof PhysicsConstraint p) {
                    var n = constraints.addObject().put("name", p.name()).put("order", constraintOrder.get(p.id()))
                            .put("bone", d.bone(p.boneId()).name()).put("limit", p.limit()).put("fps", 60);
                    String[] influence = { "x", "y", "rotate", "scaleX", "shearX" },
                            parameters = { "inertia", "strength", "damping", "mass", "wind", "gravity", "mix" };
                    for (int i = 0; i < 5; i++)
                        n.put(influence[i], p.influence().get(i));
                    for (int i = 0; i < 7; i++)
                        n.put(parameters[i], i == 6 ? p.mix() : p.parameters().get(i));
                }
        }
        if (d.constraints().stream().anyMatch(c -> c instanceof PathConstraint)) {
            var constraints = root.putArray("path");
            for (var c : d.constraints())
                if (c instanceof PathConstraint p) {
                    var slot = d.slots().stream().filter(s -> s.id().equals(p.targetId())).findFirst().orElseThrow();
                    var n = constraints.addObject().put("name", p.name()).put("order", constraintOrder.get(p.id()))
                            .put("target", slot.name())
                            .put("positionMode", p.positionMode().name().toLowerCase(Locale.ROOT))
                            .put("spacingMode", p.spacingMode().name().toLowerCase(Locale.ROOT))
                            .put("rotateMode",
                                    p.rotateMode() == PathConstraint.RotateMode.CHAIN_SCALE ? "chainScale"
                                            : p.rotateMode().name().toLowerCase(Locale.ROOT))
                            .put("position", p.position()).put("spacing", p.spacing()).put("rotation", p.rotation())
                            .put("mixRotate", p.enabled() ? p.mixRotate() : 0).put("mixX", p.enabled() ? p.mixX() : 0)
                            .put("mixY", p.enabled() ? p.mixY() : 0);
                    p.boneIds().forEach(id -> n.withArray("bones").add(d.bone(id).name()));
                }
        }
        Map<EntityId, Slot> bySlot = new HashMap<>();
        var slots = root.putArray("slots");
        for (var s : d.slots()) {
            bySlot.put(s.id(), s);
            var n = slots.addObject().put("name", s.name()).put("bone", d.bone(s.boneId()).name()).put("blend",
                    s.blend().name().toLowerCase(Locale.ROOT));
            if (s.activeAttachmentId() != null)
                n.put("attachment", attachments.get(s.activeAttachmentId()).name());
            if (!s.color().equals(Rgba.WHITE)) n.put("color",s.color().hex());
        }
        var skinArray = root.putArray("skins");
        for (var sourceSkin : d.skins()) {
            var skin = skinArray.addObject().put("name", sourceSkin.name()).putObject("attachments");
            for (var a : d.attachments()) {
                if (!Objects.equals(sourceSkin.find(a.slotId(), a.name()), a.id()))
                    continue;
                if (a instanceof BoundingBoxAttachment box) {
                    var n = skin.withObject("/" + escape(bySlot.get(box.slotId()).name())).putObject(box.name())
                            .put("type", "boundingbox").put("vertexCount", box.vertices().size()).put("color", box.color().hex());
                    var vertices = n.putArray("vertices");
                    for (var v : box.vertices()) vertices.add(v.x()).add(v.y());
                    continue;
                }
                if (a instanceof PathAttachment p) {
                    var n = skin.withObject("/" + escape(bySlot.get(p.slotId()).name())).putObject(p.name())
                            .put("type", "path").put("closed", p.closed()).put("constantSpeed", p.constantSpeed())
                            .put("vertexCount", p.vertices().size());
                    var raw=n.putArray("vertices");
                    if(!p.weighted()) for(var v:p.vertices()) raw.add(v.x()).add(v.y());
                    else for(var row:p.weights()) {
                        raw.add(row.size());
                        for(var influence:row) {
                            raw.add(d.bones().indexOf(d.bone(influence.boneId())));
                            raw.add(influence.point().x()).add(influence.point().y()).add(influence.weight());
                        }
                    }
                    p.lengths().forEach(n.putArray("lengths")::add);
                    continue;
                }
                if (a instanceof ClippingAttachment c) {
                    var n = skin.withObject("/" + escape(bySlot.get(c.slotId()).name())).putObject(c.name())
                            .put("type", "clipping").put("vertexCount", c.vertices().size());
                    n.put("color", c.color().hex());
                    var v = n.putArray("vertices");
                    for (var p : c.vertices()) {
                        v.add(p.x());
                        v.add(p.y());
                    }
                    if (c.endSlotId() != null)
                        n.put("end", bySlot.get(c.endSlotId()).name());
                    continue;
                }
                if (a instanceof MeshAttachment m) {
                    String path = regionPaths.get(m.id());
                    if (path == null)
                        throw new IOException("Missing packed mesh: " + m.name());
                    var n = skin.withObject("/" + escape(bySlot.get(m.slotId()).name())).putObject(m.name())
                            .put("type", "mesh").put("path", path).put("hull", m.hull());
                    var vertices = n.putArray("vertices");
                    if (!m.weighted())
                        for (var p : m.vertices()) {
                            vertices.add(p.x());
                            vertices.add(p.y());
                        }
                    else
                        for (var row : m.weights()) {
                            vertices.add(row.size());
                            for (var i : row) {
                                int boneIndex = -1;
                                for (int b = 0; b < d.bones().size(); b++)
                                    if (d.bones().get(b).id().equals(i.boneId()))
                                        boneIndex = b;
                                vertices.add(boneIndex).add(i.point().x()).add(i.point().y()).add(i.weight());
                            }
                        }
                    var uvs = n.putArray("uvs");
                    for (var uv : m.uvs()) {
                        uvs.add(uv.x());
                        uvs.add(uv.y());
                    }
                    m.triangles().forEach(n.putArray("triangles")::add);
                    SpineMetadataJson.writeMesh(n, d.resources().metadata().meshes().get(m.id()));
                    continue;
                }
                var r = (RegionAttachment) a;
                String path = regionPaths.get(r.id());
                if (path == null)
                    throw new IOException("Missing packed region: " + r.name());
                var slot = skin.withObject("/" + escape(bySlot.get(r.slotId()).name()));
                var n = slot.putObject(r.name()).put("type", "region").put("path", path);
                putTransform(n, r.local());
                n.put("width", r.width()).put("height", r.height());
            }
        }
        P8RuntimeJson.definitions(root, d);
        var anims = root.putObject("animations");
        for (var clip : d.animations()) {
            var animation = anims.putObject(clip.name());
            ConstraintTimelineJson.write(animation, d, clip);
            P8RuntimeJson.write(animation, d, clip);
            var anim = animation.putObject("bones");
            for (var t : clip.timelines()) {
                var bone = anim.withObject("/" + escape(d.bone(t.boneId()).name()));
                if (t.channel() == Channel.ROTATE)
                    writeTrack(bone.putArray("rotate"), t, 0);
                else {
                    // Independent axis timelines avoid the whole-timeline stepped flag of paired
                    // tracks.
                    String prefix = t.channel().name().toLowerCase(Locale.ROOT);
                    writeTrack(bone.putArray(prefix + "x"), t, 0);
                    writeTrack(bone.putArray(prefix + "y"), t, 1);
                }
            }
            if (!clip.deforms().isEmpty()) {
                var deformSkins = animation.putObject("attachments");
                for (var t : clip.deforms()) {
                    var m = attachments.get(t.attachmentId());
                    String skinName = d.skins().stream()
                            .filter(s -> Objects.equals(s.find(m.slotId(), m.name()), m.id())).findFirst().orElseThrow()
                            .name();
                    var skins = deformSkins.withObject("/" + escape(skinName));
                    var keys = skins.withObject("/" + escape(bySlot.get(m.slotId()).name()))
                            .withObject("/" + escape(m.name())).putArray("deform");
                    for (int i = 0; i < t.keys().size(); i++) {
                        var k = t.keys().get(i);
                        var n = keys.addObject().put("time", k.time());
                        int start = 0, end = k.values().size();
                        while (start < end && k.values().get(start) == 0)
                            start++;
                        while (end > start && k.values().get(end - 1) == 0)
                            end--;
                        if (start < end) {
                            if (start != 0)
                                n.put("offset", start);
                            var values = n.putArray("vertices");
                            for (int c = start; c < end; c++)
                                values.add(k.values().get(c));
                        }
                        if (i + 1 < t.keys().size()) {
                            var curve = k.curves().get(0);
                            if (curve.kind() == Curve.Kind.STEPPED)
                                n.put("curve", "stepped");
                            if (curve.kind() == Curve.Kind.BEZIER) {
                                double dt = t.keys().get(i + 1).time() - k.time();
                                n.putArray("curve").add(k.time() + dt * curve.x1()).add(curve.y1())
                                        .add(k.time() + dt * curve.x2()).add(curve.y2());
                            }
                        }
                    }
                }
            }
        }
        SkinActivationJson.writeRuntime(root,d);
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > ProjectCodec.MAX_BYTES)
            throw new ProjectFormatException("Export exceeds 16 MiB");
        return bytes;
    }

    private static String escape(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    private static void putTransform(ObjectNode n, Transform t) {
        n.put("x", t.x()).put("y", t.y()).put("rotation", t.rotation()).put("scaleX", t.scaleX()).put("scaleY",
                t.scaleY());
        if (t.shearX() != 0) n.put("shearX", t.shearX());
        if (t.shearY() != 0) n.put("shearY", t.shearY());
    }

    private void writeTrack(ArrayNode out, BoneTimeline t, int component) {
        for (int i = 0; i < t.keys().size(); i++) {
            var k = t.keys().get(i);
            var n = out.addObject().put("time", k.time()).put("value", k.values().get(component));
            if (i + 1 == t.keys().size())
                continue;
            var c = k.curves().get(component);
            if (c.kind() == Curve.Kind.STEPPED)
                n.put("curve", "stepped");
            if (c.kind() == Curve.Kind.BEZIER) {
                var next = t.keys().get(i + 1);
                double dt = next.time() - k.time(), dv = next.values().get(component) - k.values().get(component);
                n.putArray("curve").add(k.time() + dt * c.x1()).add(k.values().get(component) + dv * c.y1())
                        .add(k.time() + dt * c.x2()).add(k.values().get(component) + dv * c.y2());
            }
        }
    }

    public DocumentSnapshot read(Path file) throws IOException {
        return read(file,file.toAbsolutePath().normalize().getParent());
    }

    public DocumentSnapshot read(Path file, Path imageBase) throws IOException {
        return read(file,imageBase,null);
    }

    public DocumentSnapshot read(Path file, Path imageBase, SpineImportPolicy.Approval approval) throws IOException {
        sourceVersion = null;
        warnings.clear();
        importedImages.clear();
        if (Spine40Binary.isBinary(file)) {
            var decoded = Spine40Binary.decode(ResourcePaths.bytes(file, ProjectCodec.MAX_BYTES),approval);
            var atlas = AtlasSubset.read(file.toAbsolutePath().normalize(), true, warnings);
            var document = decodeTree(decoded.tree(), imageBase.toAbsolutePath().normalize(), atlas, ImportMode.SPINE_40_BINARY);
            sourceVersion = decoded.sourceVersion();
            if(!decoded.selection().known())warnings.add(decoded.selection().message());
            return document;
        }
        ProjectCodec.requireProjectPath(file);
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(ProjectCodec.MAX_BYTES + 1);
        }
        boolean legacy = SpineImportPolicy.select(bytes,SpineImportPolicy.Format.JSON,approval).parser()!=SpineImportPolicy.Parser.JSON_42;
        Map<String, AtlasSubset.Region> atlas;
        try { atlas = AtlasSubset.read(file.toAbsolutePath().normalize(), legacy, warnings); }
        catch (IOException invalidAtlas) {
            if (!legacy) throw invalidAtlas;
            warnings.add("atlas: invalid configuration filtered; using image links: " + invalidAtlas.getMessage());
            atlas = null;
        }
        return decode(bytes, imageBase.toAbsolutePath().normalize(), atlas,approval);
    }

    public DocumentSnapshot decode(byte[] bytes, Path base) throws IOException {
        sourceVersion = null;
        warnings.clear();
        importedImages.clear();
        return decode(bytes, base, null,null);
    }

    private DocumentSnapshot decode(byte[] bytes, Path base, Map<String, AtlasSubset.Region> atlas, SpineImportPolicy.Approval approval) throws IOException {
        var root = new ProjectCodec().parseTree(bytes);
        String version = root.path("skeleton").path("spine").asText();
        var selection=SpineImportPolicy.select(bytes,SpineImportPolicy.Format.JSON,approval);
        if(!selection.known())warnings.add(selection.message());
        boolean legacy = selection.parser()==SpineImportPolicy.Parser.JSON_38;
        if (legacy) {
            try(var cache=io.github.samlinb.openbone.core.math.MeshTopology.cacheValidations()) {
                ((ObjectNode)root.path("skeleton")).put("spine",Spine38Json.VERSION);
                root = Spine38Json.normalize(root, warnings);
                try {
                    var document = decodeTree(root, base, atlas, ImportMode.SPINE_38_JSON);
                    sourceVersion = version;
                    return document;
                }
                catch (ProjectFormatException invalid) {
                    var document = recover38((ObjectNode)root, base, atlas, invalid);
                    sourceVersion = version;
                    return document;
                }
            }
        }
        root=SpineVersionJson.normalize((ObjectNode)root,selection.parser().family());
        var document = decodeTree(root, base, atlas, selection.parser()==SpineImportPolicy.Parser.JSON_42 ? ImportMode.STRICT_42 : ImportMode.SPINE_VERSION_JSON);
        sourceVersion = version;
        return document;
    }

    private DocumentSnapshot decodeTree(JsonNode root, Path base, Map<String, AtlasSubset.Region> atlas, ImportMode mode) throws IOException {
        boolean compatibleResources = mode.compatibleResources();
        List<String> unsupported = new ArrayList<>();
        scan(root, unsupported);
        if (!unsupported.isEmpty())
            throw new ProjectFormatException(
                    "Unsupported 4.2.22 data (nothing imported):\n" + String.join("\n", unsupported));
        try {
            var metadata = root.get("skeleton");
            String version = ProjectCodec.text(metadata, "spine", "$.skeleton");
            if (metadata.has("referenceScale") && ProjectCodec.number(metadata, "referenceScale", "$.skeleton") != 100)
                throw ProjectCodec.bad("$.skeleton.referenceScale", "Physics referenceScale supports exactly 100");
            if (!VERSION.equals(version))
                throw ProjectCodec.bad("$.skeleton.spine", "Expected exactly " + VERSION + ", got " + version);
            List<Bone> bones = new ArrayList<>();
            Map<String, EntityId> boneIds = new LinkedHashMap<>();
            for (var n : ProjectCodec.array(root, "bones", "$")) {
                String name = ProjectCodec.text(n, "name", "$.bones");
                if (boneIds.putIfAbsent(name, EntityId.create()) != null)
                    throw ProjectCodec.bad("$.bones", "Duplicate bone name " + name);
            }
            for (var n : root.get("bones")) {
                if(n.has("icon")) {
                    ProjectCodec.text(n,"icon","$.bones");
                    warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.boneIcons"));
                }
                String name = ProjectCodec.text(n, "name", "$.bones"),
                        parent = n.has("parent") ? ProjectCodec.text(n, "parent", "$.bones") : null;
                if (parent != null && !boneIds.containsKey(parent))
                    throw ProjectCodec.bad("$.bones.parent", "Unknown parent " + parent);
                bones.add(new Bone(boneIds.get(name), name, parent == null ? null : boneIds.get(parent),
                        transform(n, "$.bones"), num(n, "length", 0, "$.bones"),
                        n.has("color") ? Rgba.parse(ProjectCodec.text(n, "color", "$.bones")) : Rgba.WHITE,
                        Bone.Inherit.valueOf(n.has("inherit") ? ProjectCodec.text(n, "inherit", "$.bones") : "normal")));
            }
            if (bones.isEmpty() || bones.stream().filter(b -> b.parentId() == null).count() != 1)
                throw ProjectCodec.bad("$.bones", "Exactly one root required");
            Map<String, EntityId> slotIds = new LinkedHashMap<>();
            List<Slot> slots = new ArrayList<>();
            for (var n : ProjectCodec.array(root, "slots", "$")) {
                String name = ProjectCodec.text(n, "name", "$.slots"), bone = ProjectCodec.text(n, "bone", "$.slots");
                if (slotIds.putIfAbsent(name, EntityId.create()) != null)
                    throw ProjectCodec.bad("$.slots", "Duplicate slot name " + name);
                if (!boneIds.containsKey(bone))
                    throw ProjectCodec.bad("$.slots.bone", "Unknown bone " + bone);
                slots.add(new Slot(slotIds.get(name), name, boneIds.get(bone), null,
                        n.has("blend") && ProjectCodec.text(n, "blend", "slots").equals("additive")
                                ? Slot.Blend.ADDITIVE
                                : Slot.Blend.NORMAL,
                        n.has("color") ? Rgba.parse(ProjectCodec.text(n,"color","slots")) : Rgba.WHITE));
            }
            var skins = ProjectCodec.array(root, "skins", "$");
            if (skins.isEmpty())
                throw ProjectCodec.bad("$.skins", "At least default skin required");
            var importedSkins = new ArrayList<Skin>();
            var atlasMetadata = new LinkedHashMap<EntityId, AtlasRegion>();
            var eventDefinitions = P8RuntimeJson.definitions(root);
            String folder = metadata.has("images") ? ProjectCodec.text(metadata, "images", "$.skeleton") : "";
            Path images = folder.isEmpty() ? base : ResourcePaths.contained(base, folder);
            List<Attachment> attachments = new ArrayList<>();
            Map<String, EntityId> attachmentIds = new HashMap<>();
            for (var skin : skins) {
                String skinName = ProjectCodec.text(skin, "name", "skins");
                var all = skin.get("attachments");
                object(all, "skins.attachments");
                var skinEntries = new LinkedHashMap<EntityId, Map<String, EntityId>>();
                for (var it = all.fields(); it.hasNext();) {
                    var entry = it.next();
                    String slot = entry.getKey();
                    if (!slotIds.containsKey(slot))
                        throw ProjectCodec.bad("$.skins.attachments." + slot, "Unknown slot");
                    for (var ait = entry.getValue().fields(); ait.hasNext();) {
                        var ae = ait.next();
                        var n = ae.getValue();
                        String name = ae.getKey(), p = "$.skins.attachments." + slot + "." + name;
                        if (n.path("type").asText().equals("boundingbox")) {
                            int count = ProjectCodec.integer(n.get("vertexCount"), p + ".vertexCount");
                            var vertices = ProjectCodec.points(n, "vertices", p);
                            if (vertices.size() != count) throw ProjectCodec.bad(p + ".vertices", "Expected unweighted bounding box XY pairs");
                            var id = EntityId.create();
                            attachmentIds.put(skinName + "\0" + slot + "\0" + name, id);
                            skinEntries.computeIfAbsent(slotIds.get(slot), s -> new LinkedHashMap<>()).put(name, id);
                            attachments.add(new BoundingBoxAttachment(id, name, slotIds.get(slot), vertices,
                                    n.has("color") ? Rgba.parse(ProjectCodec.text(n,"color",p)) : BoundingBoxAttachment.DEFAULT_COLOR));
                            continue;
                        }
                        if (n.path("type").asText().equals("clipping")) {
                            int count = ProjectCodec.integer(n.get("vertexCount"), p + ".vertexCount");
                            if (count < 3 || count > io.github.samlinb.openbone.core.math.MeshTopology.MAX_VERTICES)
                                throw ProjectCodec.bad(p + ".vertexCount", "Expected 3–512 clipping vertices");
                            var raw = ProjectCodec.array(n, "vertices", p);
                            if (raw.size() != count * 2)
                                throw ProjectCodec.bad(p + ".vertices",
                                        "Weighted clipping is unsupported; expected unweighted XY pairs");
                            EntityId end = null;
                            if (n.has("end") && !n.get("end").isNull()) {
                                String endName = ProjectCodec.text(n, "end", p);
                                end = slotIds.get(endName);
                                if (end == null)
                                    throw ProjectCodec.bad(p + ".end", "Unknown end slot " + endName);
                            }
                            var id = EntityId.create();
                            attachmentIds.put(skinName + "\0" + slot + "\0" + name, id);
                            skinEntries.computeIfAbsent(slotIds.get(slot), s -> new LinkedHashMap<>()).put(name, id);
                            attachments.add(new ClippingAttachment(id, name, slotIds.get(slot),
                                    ProjectCodec.points(n, "vertices", p), end,
                                    n.has("color") ? Rgba.parse(ProjectCodec.text(n,"color",p)) : ClippingAttachment.DEFAULT_COLOR));
                            continue;
                        }
                        if (n.path("type").asText().equals("path")) {
                            int count = ProjectCodec.integer(n.get("vertexCount"), p + ".vertexCount");
                            if (count < 3 || count > 510 || count % 3 != 0)
                                throw ProjectCodec.bad(p + ".vertexCount", "Expected a multiple of 3 in 3–510");
                            var geometry=pathGeometry(n,p,count,bones,compatibleResources);
                            var id = EntityId.create();
                            attachmentIds.put(skinName + "\0" + slot + "\0" + name, id);
                            skinEntries.computeIfAbsent(slotIds.get(slot), s -> new LinkedHashMap<>()).put(name, id);
                            attachments.add(new PathAttachment(id, name, slotIds.get(slot),
                                    geometry.vertices(), bool(n, "closed", false, p),
                                    bool(n, "constantSpeed", true, p), ProjectCodec.numbers(n, "lengths", p),geometry.weights()));
                            continue;
                        }
                        String path = n.has("path") ? ProjectCodec.text(n, "path", p) : name;
                        Path png = ResourcePaths.contained(images, path + ".png");
                        if (atlas != null) {
                            if (!atlas.containsKey(path))
                                throw ProjectCodec.bad(p + ".path", "Missing atlas region: " + path);
                            png = atlas.get(path).path();
                        }
                        // Strict import refuses missing/bad images before changing the old document.
                        java.awt.image.BufferedImage pixels = null;
                        try {
                            pixels = importedImages.get(png);
                            if(pixels==null) {
                                // Legacy editor import uses the viewport decoder, not runtime export's
                                // color-management restrictions. Keep the original linked image bytes.
                                pixels=io.github.samlinb.openbone.viewport.ImageAssets.decodePng(
                                        compatibleResources ? ResourcePaths.png(png) : RuntimePng.read(png));
                                importedImages.put(png,pixels);
                            }
                        } catch (IOException invalidImage) {
                            if (!compatibleResources) throw invalidImage;
                            warnings.add(p + ": image unavailable; resource link retained: " + png
                                    + "; reason: " + invalidImage.getMessage());
                        }
                        var id = EntityId.create();
                        attachmentIds.put(skinName + "\0" + slot + "\0" + name, id);
                        skinEntries.computeIfAbsent(slotIds.get(slot), s -> new LinkedHashMap<>()).put(name, id);
                        if (atlas != null && atlas.get(path).metadata() != null)
                            atlasMetadata.put(id, atlas.get(path).metadata());
                        if (n.path("type").asText().equals("mesh")) {
                            var uvs = ProjectCodec.points(n, "uvs", p);
                            var triangles=ProjectCodec.integers(n,"triangles",p);
                            boolean forward=false,reverse=false;
                            for(int t=0;t+2<triangles.size();t+=3)for(int edge=0;edge<3;edge++) {
                                int a=triangles.get(t+edge),b=triangles.get(t+(edge+1)%3);
                                forward|=a==0&&b==1;reverse|=a==1&&b==0;
                            }
                            if(reverse&&!forward) {
                                var oriented=new ArrayList<>(triangles);
                                for(int t=0;t+2<oriented.size();t+=3)Collections.swap(oriented,t+1,t+2);
                                triangles=List.copyOf(oriented);
                                warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.triangleWinding"));
                            }
                            var raw = ProjectCodec.array(n, "vertices", p);
                            List<io.github.samlinb.openbone.core.math.Vec2> vertices;
                            List<List<Influence>> weights = new ArrayList<>();
                            if (raw.size() == uvs.size() * 2)
                                vertices = ProjectCodec.points(n, "vertices", p);
                            else {
                                int cursor = 0;
                                for (int v = 0; v < uvs.size(); v++) {
                                    if (cursor >= raw.size())
                                        throw ProjectCodec.bad(p + ".vertices", "Missing weighted vertex");
                                    int count = vertexInteger(raw.get(cursor++), p + ".vertices.count");
                                    if (count < 1 || count > 4 || cursor + count * 4 > raw.size())
                                        throw ProjectCodec.bad(p + ".vertices",
                                                "Each vertex requires 1–4 complete influences");
                                    List<Influence> row = new ArrayList<>();
                                    for (int j = 0; j < count; j++) {
                                        int bi = vertexInteger(raw.get(cursor++), p + ".vertices.boneIndex");
                                        if (bi < 0 || bi >= bones.size())
                                            throw ProjectCodec.bad(p + ".vertices.boneIndex", "Out of range");
                                        double[] values = new double[3];
                                        for (int c = 0; c < 3; c++) {
                                            var value = raw.get(cursor++);
                                            if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
                                                throw ProjectCodec.bad(p + ".vertices",
                                                        "Expected finite influence component");
                                            values[c] = value.doubleValue();
                                        }
                                        row.add(new Influence(bones.get(bi).id(),
                                                new io.github.samlinb.openbone.core.math.Vec2(values[0], values[1]),
                                                values[2]));
                                    }
                                    try { Influence.validate(row); }
                                    catch(IllegalArgumentException invalid) {
                                        double total=row.stream().mapToDouble(Influence::weight).sum();
                                        if(!compatibleResources || Math.abs(total-1)>.001) throw invalid;
                                        row=new ArrayList<>(Influence.normalize(row));
                                        warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.roundedWeights"));
                                    }
                                    weights.add(row);
                                }
                                if (cursor != raw.size())
                                    throw ProjectCodec.bad(p + ".vertices", "Trailing weighted vertex data");
                                vertices = io.github.samlinb.openbone.core.math.MeshTopology.certificate(uvs.size(),
                                        ProjectCodec.integer(n.get("hull"), p + ".hull"),
                                        triangles);
                            }
                            attachments.add(new MeshAttachment(id, name, slotIds.get(slot), png.toString(), vertices,
                                    uvs, ProjectCodec.integer(n.get("hull"), p + ".hull"),
                                    triangles, weights));
                            continue;
                        }
                        attachments.add(new RegionAttachment(id, name, slotIds.get(slot), png.toString(),
                                transform(n, p),
                                num(n, "width",
                                        atlasMetadata.containsKey(id) ? atlasMetadata.get(id).originalWidth()
                                                : pixels == null ? 1 : pixels.getWidth(),
                                        p),
                                num(n, "height", atlasMetadata.containsKey(id) ? atlasMetadata.get(id).originalHeight()
                                        : pixels == null ? 1 : pixels.getHeight(), p)));
                    }
                }
                importedSkins.add(new Skin(skinName, skinEntries));
            }
            int si = 0;
            for (var n : root.get("slots")) {
                Slot s = slots.get(si);
                if (n.has("attachment") && !n.get("attachment").isNull()) {
                    String name = ProjectCodec.text(n, "attachment", "$.slots");
                    EntityId id = attachmentIds.get("default\0" + s.name() + "\0" + name);
                    if (id == null)
                        for (var candidate : importedSkins) {
                            id = candidate.find(s.id(), name);
                            if (id != null)
                                break;
                        }
                    if (id == null)
                        throw ProjectCodec.bad("$.slots.attachment", "Unknown active attachment " + name);
                    slots.set(si, s.withActiveAttachment(id));
                }
                si++;
            }
            List<AnimationClip> animations = new ArrayList<>();
            if (root.has("animations"))
                for (var ai = root.get("animations").fields(); ai.hasNext();) {
                    var ae = ai.next();
                    List<BoneTimeline> tracks = new ArrayList<>();
                    var anim = ae.getValue();
                    if (anim.has("bones"))
                        for (var bi = anim.get("bones").fields(); bi.hasNext();) {
                            var be = bi.next();
                            if (!boneIds.containsKey(be.getKey()))
                                throw ProjectCodec.bad("$.animations.bones", "Unknown bone " + be.getKey());
                            var bt = be.getValue();
                            for (var channel : Channel.values()) {
                                String prefix = switch (channel) {
                                    case TRANSLATE -> "translate";
                                    case ROTATE -> "rotate";
                                    case SCALE -> "scale";
                                    case SHEAR -> "shear";
                                };
                                EntityId id = boneIds.get(be.getKey());
                                if (bt.has(prefix)) {
                                    if (bt.has(prefix + "x") || bt.has(prefix + "y"))
                                        throw ProjectCodec.bad("$.animations." + prefix,
                                                "Paired and split tracks overlap");
                                    tracks.add(new BoneTimeline(id, channel, readKeys(bt.get(prefix),
                                            channel.components(), channel == Channel.SCALE ? 1 : 0)));
                                } else if (channel != Channel.ROTATE
                                        && (bt.has(prefix + "x") || bt.has(prefix + "y"))) {
                                    var x = bt.has(prefix + "x")
                                            ? readKeys(bt.get(prefix + "x"), 1, channel == Channel.SCALE ? 1 : 0)
                                            : null;
                                    var y = bt.has(prefix + "y")
                                            ? readKeys(bt.get(prefix + "y"), 1, channel == Channel.SCALE ? 1 : 0)
                                            : null;
                                    tracks.add(new BoneTimeline(id, channel,
                                            pair(x, y, channel == Channel.SCALE ? 1 : 0, prefix)));
                                }
                            }
                        }
                    List<DeformTimeline> deforms = new ArrayList<>();
                    if (anim.has("attachments")) {
                        var skinsNode = anim.get("attachments");
                        for (var skinIterator = skinsNode.fields(); skinIterator.hasNext();) {
                            var skinEntry = skinIterator.next();
                            if (importedSkins.stream().noneMatch(s -> s.name().equals(skinEntry.getKey())))
                                throw ProjectCodec.bad("$.animations.attachments", "Unknown skin");
                            for (var slotIterator = skinEntry.getValue().fields(); slotIterator.hasNext();) {
                                var slotEntry = slotIterator.next();
                                if (!slotIds.containsKey(slotEntry.getKey()))
                                    throw ProjectCodec.bad("$.animations.attachments.slot",
                                            "Unknown slot " + slotEntry.getKey());
                                for (var attachmentIterator = slotEntry.getValue().fields(); attachmentIterator
                                        .hasNext();) {
                                    var attachmentEntry = attachmentIterator.next();
                                    var aid = attachmentIds.get(skinEntry.getKey() + "\0" + slotEntry.getKey() + "\0"
                                            + attachmentEntry.getKey());
                                    if (aid == null)
                                        throw ProjectCodec.bad("$.animations.attachments.attachment",
                                                "Unknown attachment");
                                    var target = attachments.stream().filter(a -> a.id().equals(aid)).findFirst()
                                            .orElseThrow();
                                    int size=target instanceof MeshAttachment mesh?mesh.deformSize()
                                            :target instanceof PathAttachment pathAttachment?pathAttachment.deformSize():-1;
                                    if (size<0)
                                        throw ProjectCodec.bad("$.animations.attachments.deform",
                                                "Only mesh and path deform supported");
                                    if (attachmentEntry.getValue().has("deform"))
                                        deforms.add(new DeformTimeline(aid, readDeformKeys(
                                                attachmentEntry.getValue().get("deform"), size)));
                                }
                            }
                        }
                    }
                    animations.add(new AnimationClip(EntityId.create(), ae.getKey(), tracks, deforms, List.of(),
                            P8RuntimeJson.read(anim, slots, eventDefinitions)));
                }
            List<Constraint> constraints = new ArrayList<>();
            if (root.has("ik")) {
                int ci = 0;
                for (var n : ProjectCodec.array(root, "ik", "$")) {
                    String p = "$.ik[" + ci++ + "]";
                    List<EntityId> chain = new ArrayList<>();
                    for (var name : ProjectCodec.array(n, "bones", p)) {
                        if (!name.isTextual() || !boneIds.containsKey(name.textValue()))
                            throw ProjectCodec.bad(p + ".bones", "Unknown bone or non-string bone name");
                        chain.add(boneIds.get(name.textValue()));
                    }
                    String target = ProjectCodec.text(n, "target", p);
                    if (!boneIds.containsKey(target))
                        throw ProjectCodec.bad(p + ".target", "Unknown target bone");
                    constraints.add(new IkConstraint(EntityId.create(), ProjectCodec.text(n, "name", p),
                            n.has("order") ? ProjectCodec.integer(n.get("order"), p + ".order") : 0, true, chain,
                            boneIds.get(target), num(n, "mix", 1, p), num(n, "softness", 0, p),
                            bool(n, "bendPositive", true, p), bool(n, "compress", false, p),
                            bool(n, "stretch", false, p), bool(n, "uniform", false, p)));
                }
            }
            if (root.has("transform")) {
                int ci = 0;
                for (var n : ProjectCodec.array(root, "transform", "$")) {
                    String p = "$.transform[" + ci++ + "]";
                    List<EntityId> chain = new ArrayList<>();
                    for (var name : ProjectCodec.array(n, "bones", p)) {
                        if (!name.isTextual() || !boneIds.containsKey(name.textValue()))
                            throw ProjectCodec.bad(p + ".bones", "Unknown bone or non-string name");
                        chain.add(boneIds.get(name.textValue()));
                    }
                    String target = ProjectCodec.text(n, "target", p);
                    if (!boneIds.containsKey(target))
                        throw ProjectCodec.bad(p + ".target", "Unknown target bone");
                    double mixX = num(n, "mixX", 1, p), mixScaleX = num(n, "mixScaleX", 1, p);
                    constraints.add(new TransformConstraint(EntityId.create(), ProjectCodec.text(n, "name", p),
                            n.has("order") ? ProjectCodec.integer(n.get("order"), p + ".order") : 0, true, chain,
                            boneIds.get(target), bool(n, "local", false, p), bool(n, "relative", false, p),
                            List.of(num(n, "mixRotate", 1, p), mixX, num(n, "mixY", mixX, p), mixScaleX,
                                    num(n, "mixScaleY", mixScaleX, p), num(n, "mixShearY", 1, p)),
                            List.of(num(n, "rotation", 0, p), num(n, "x", 0, p), num(n, "y", 0, p),
                                    num(n, "scaleX", 0, p), num(n, "scaleY", 0, p), num(n, "shearY", 0, p))));
                }
            }
            if (root.has("path")) {
                int ci = 0;
                for (var n : ProjectCodec.array(root, "path", "$")) {
                    String p = "$.path[" + ci++ + "]";
                    var chain = new ArrayList<EntityId>();
                    for (var name : ProjectCodec.array(n, "bones", p)) {
                        if (!name.isTextual() || !boneIds.containsKey(name.textValue()))
                            throw ProjectCodec.bad(p + ".bones", "Unknown bone or non-string name");
                        chain.add(boneIds.get(name.textValue()));
                    }
                    String target = ProjectCodec.text(n, "target", p);
                    if (!slotIds.containsKey(target))
                        throw ProjectCodec.bad(p + ".target", "Unknown target slot");
                    double mixX = num(n, "mixX", 1, p);
                    constraints.add(new PathConstraint(EntityId.create(), ProjectCodec.text(n, "name", p),
                            n.has("order") ? ProjectCodec.integer(n.get("order"), p + ".order") : 0, true, chain,
                            slotIds.get(target),
                            pathMode(n, "positionMode", "percent", PathConstraint.PositionMode.class, p),
                            pathMode(n, "spacingMode", "length", PathConstraint.SpacingMode.class, p),
                            pathMode(n, "rotateMode", "tangent", PathConstraint.RotateMode.class, p),
                            num(n, "position", 0, p), num(n, "spacing", 0, p), num(n, "rotation", 0, p),
                            num(n, "mixRotate", 1, p), mixX, num(n, "mixY", mixX, p)));
                }
            }
            if (root.has("physics")) {
                int ci = 0;
                for (var n : ProjectCodec.array(root, "physics", "$")) {
                    String p = "$.physics[" + ci++ + "]", bone = ProjectCodec.text(n, "bone", p);
                    if (!boneIds.containsKey(bone))
                        throw ProjectCodec.bad(p + ".bone", "Unknown physics bone");
                    if (n.has("fps") && ProjectCodec.integer(n.get("fps"), p + ".fps") != 60)
                        throw ProjectCodec.bad(p + ".fps", "Physics uses fixed 60Hz");
                    for (String field : List.of("inertiaGlobal", "strengthGlobal", "dampingGlobal", "massGlobal",
                            "windGlobal", "gravityGlobal", "mixGlobal"))
                        if (bool(n, field, false, p))
                            throw ProjectCodec.bad(p + "." + field, "Global physics timelines are unsupported");
                    constraints
                            .add(new PhysicsConstraint(EntityId.create(), ProjectCodec.text(n, "name", p),
                                    n.has("order") ? ProjectCodec.integer(n.get("order"), p + ".order") : 0, true,
                                    boneIds.get(bone), List.of(num(n, "x", 0, p), num(n, "y", 0, p),
                                            num(n, "rotate", 0, p), num(n, "scaleX", 0, p), num(n, "shearX", 0, p)),
                                    num(n, "limit", 5000, p),
                                    List.of(num(n, "inertia", 1, p), num(n, "strength", 100, p),
                                            num(n, "damping", 1, p), num(n, "mass", 1, p), num(n, "wind", 0, p),
                                            num(n, "gravity", 0, p), num(n, "mix", 1, p))));
                }
            }
            animations = ConstraintTimelineJson.read(root, animations, constraints);
            var activation = SkinActivationJson.readRuntime(root, bones, constraints);
            var d = new DocumentSnapshot(bones, slots, attachments, animations, constraints,
                    new Resources(importedSkins.size() == 1 && importedSkins.get(0).name().equals("default") && activation.equals(SkinActivation.EMPTY) ? List.of()
                            : importedSkins, "default", eventDefinitions, atlasMetadata, SpineMetadataJson.readRuntime(root, attachmentIds), activation));
            // Runtime export certificates must not delete valid editable source data.
            if (mode == ImportMode.STRICT_42 && !ProjectCodec.needsSkinActivation(d)) RuntimeRange.validate(d);
            return d;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ProjectFormatException("Invalid 4.2.22 model: " + e.getMessage(), e);
        }
    }

    /** Retry independent entries transactionally, so a malformed mesh or track cannot discard its siblings. */
    private DocumentSnapshot recover38(ObjectNode source, Path base, Map<String, AtlasSubset.Region> atlas,
            ProjectFormatException failure) throws IOException {
        warnings.add("3.8 recovery: " + failure.getMessage());
        ObjectNode clean = source.deepCopy();
        clean.remove(List.of("ik", "transform", "path", "events"));
        clean.putObject("animations");
        for (var skin : clean.path("skins")) ((ObjectNode)skin).putObject("attachments");
        for (var slot : clean.path("slots")) ((ObjectNode)slot).remove("attachment");
        // The skeleton is the irreducible foundation. Do not publish an invalid hierarchy.
        decodeTree(clean, base, atlas, ImportMode.SPINE_38_JSON);
        for (var it = source.path("events").fields(); it.hasNext();) {
            var entry = it.next();
            clean = accept38(clean, base, atlas, "$.events." + entry.getKey(),
                    n -> n.withObject("/events").set(entry.getKey(), entry.getValue()));
        }
        for (int i = 0; i < source.path("skins").size(); i++) {
            final int index = i;
            for (var si = source.path("skins").get(i).path("attachments").fields(); si.hasNext();) {
                var slot = si.next();
                for (var ai = slot.getValue().fields(); ai.hasNext();) {
                    var attachment = ai.next();
                    clean = accept38(clean, base, atlas, "$.skins[" + i + "]." + slot.getKey() + "." + attachment.getKey(),
                            n -> ((ObjectNode)n.path("skins").get(index).path("attachments"))
                                    .withObject("/" + escape(slot.getKey())).set(attachment.getKey(), attachment.getValue()));
                }
            }
        }
        for (String kind : List.of("ik", "transform", "path")) {
            int i = 0;
            for (var constraint : source.path(kind)) {
                clean = accept38(clean, base, atlas, "$." + kind + "[" + i++ + "]", n -> n.withArray(kind).add(constraint));
            }
        }
        for (int i = 0; i < source.path("slots").size(); i++) {
            final int index = i;
            var slot = source.path("slots").get(i);
            if (slot.has("attachment")) clean = accept38(clean, base, atlas, "$.slots[" + i + "].attachment",
                    n -> ((ObjectNode)n.path("slots").get(index)).set("attachment", slot.get("attachment")));
        }
        for (var it = source.path("animations").fields(); it.hasNext();) {
            var animation = it.next(); String name = animation.getKey(), pointer = "/animations/" + escape(name);
            clean.withObject("/animations").putObject(name);
            for (String kind : List.of("bones", "slots", "ik", "transform", "path")) {
                for (var rows = animation.getValue().path(kind).fields(); rows.hasNext();) {
                    var row = rows.next(); String rowPointer = pointer + "/" + kind + "/" + escape(row.getKey());
                    if (row.getValue().isArray()) {
                        clean = accept38(clean, base, atlas, "$.animations." + name + "." + kind + "." + row.getKey(),
                                n -> n.withObject(pointer + "/" + kind).set(row.getKey(), row.getValue()));
                    } else for (var tracks = row.getValue().fields(); tracks.hasNext();) {
                        var track = tracks.next();
                        clean = accept38(clean, base, atlas, "$.animations." + name + "." + kind + "." + row.getKey() + "." + track.getKey(),
                                n -> n.withObject(rowPointer).set(track.getKey(), track.getValue()));
                    }
                }
            }
            for (String kind : List.of("events", "drawOrder")) {
                int i = 0;
                for (var key : animation.getValue().path(kind))
                    clean = accept38(clean, base, atlas, "$.animations." + name + "." + kind + "[" + i++ + "]",
                            n -> n.withObject(pointer).withArray(kind).add(key));
            }
            for (var skins = animation.getValue().path("attachments").fields(); skins.hasNext();) {
                var skin = skins.next();
                for (var slots = skin.getValue().fields(); slots.hasNext();) {
                    var slot = slots.next();
                    for (var entries = slot.getValue().fields(); entries.hasNext();) {
                        var attachment = entries.next();
                        clean = accept38(clean, base, atlas, "$.animations." + name + ".deform." + skin.getKey() + "." + slot.getKey() + "." + attachment.getKey(),
                                n -> n.withObject(pointer + "/attachments/" + escape(skin.getKey()) + "/" + escape(slot.getKey()))
                                        .set(attachment.getKey(), attachment.getValue()));
                    }
                }
            }
        }
        return decodeTree(clean, base, atlas, ImportMode.SPINE_38_JSON);
    }

    private ObjectNode accept38(ObjectNode current, Path base, Map<String, AtlasSubset.Region> atlas,
            String path, java.util.function.Consumer<ObjectNode> edit) throws IOException {
        ObjectNode candidate = current.deepCopy();
        int count = warnings.size();
        try {
            edit.accept(candidate);
            decodeTree(candidate, base, atlas, ImportMode.SPINE_38_JSON);
            return candidate;
        } catch (ProjectFormatException | IllegalArgumentException invalid) {
            warnings.add(path + ": filtered invalid configuration: " + invalid.getMessage());
            return current;
        } finally {
            // Resource warnings are produced once by the final decode, not once per trial.
            if (warnings.size() > count) {
                var filtered = warnings.subList(count, warnings.size()).stream()
                        .filter(w -> w.contains(": filtered invalid configuration:")).toList();
                warnings.subList(count, warnings.size()).clear();
                warnings.addAll(filtered);
            }
        }
    }

    private static List<Keyframe> pair(List<Keyframe> x, List<Keyframe> y, double defaultValue, String p)
            throws ProjectFormatException {
        var reference = x == null ? y : x;
        List<Keyframe> keys = new ArrayList<>();
        if (x != null && y != null && (x.size() != y.size()))
            throw ProjectCodec.bad(p, "Independent X/Y key times must match in this editable subset");
        for (int i = 0; i < reference.size(); i++) {
            var a = x == null ? null : x.get(i);
            var b = y == null ? null : y.get(i);
            if (a != null && b != null && a.time() != b.time())
                throw ProjectCodec.bad(p, "Independent X/Y key times must match in this editable subset");
            keys.add(new Keyframe(EntityId.create(), reference.get(i).time(),
                    List.of(a == null ? defaultValue : a.values().get(0), b == null ? defaultValue : b.values().get(0)),
                    List.of(a == null ? Curve.LINEAR : a.curves().get(0),
                            b == null ? Curve.LINEAR : b.curves().get(0))));
        }
        return keys;
    }

    private record PathGeometry(List<io.github.samlinb.openbone.core.math.Vec2> vertices,List<List<Influence>> weights) {}

    // Spine writes counts and bone indices inside float vertex arrays as e.g. 1.0.
    // Accept exact integer-valued numbers here without relaxing project-schema integers.
    private static int vertexInteger(JsonNode value,String path) throws ProjectFormatException {
        if(value==null||!value.isNumber()||!Double.isFinite(value.doubleValue())
                ||value.doubleValue()!=Math.rint(value.doubleValue())
                ||value.doubleValue()<Integer.MIN_VALUE||value.doubleValue()>Integer.MAX_VALUE)
            throw ProjectCodec.bad(path,"Expected integer-valued vertex component");
        return value.intValue();
    }

    private PathGeometry pathGeometry(JsonNode node,String path,int count,List<Bone> bones,boolean compatible) throws IOException {
        var raw=ProjectCodec.array(node,"vertices",path);
        if(raw.size()==count*2)return new PathGeometry(ProjectCodec.points(node,"vertices",path),List.of());
        var rows=new ArrayList<List<Influence>>();
        int cursor=0;
        for(int vertex=0;vertex<count;vertex++) {
            if(cursor>=raw.size())throw ProjectCodec.bad(path+".vertices","Missing weighted path vertex");
            int influences=vertexInteger(raw.get(cursor++),path+".vertices.count");
            if(influences<1||influences>4||cursor+influences*4>raw.size())
                throw ProjectCodec.bad(path+".vertices","Each path vertex requires 1–4 complete influences");
            var row=new ArrayList<Influence>();
            for(int i=0;i<influences;i++) {
                int bone=vertexInteger(raw.get(cursor++),path+".vertices.boneIndex");
                if(bone<0||bone>=bones.size())throw ProjectCodec.bad(path+".vertices.boneIndex","Out of range");
                double[] values=new double[3];
                for(int c=0;c<3;c++) {
                    var value=raw.get(cursor++);
                    if(!value.isNumber()||!Double.isFinite(value.doubleValue()))throw ProjectCodec.bad(path+".vertices","Expected finite influence component");
                    values[c]=value.doubleValue();
                }
                row.add(new Influence(bones.get(bone).id(),new io.github.samlinb.openbone.core.math.Vec2(values[0],values[1]),values[2]));
            }
            try {Influence.validate(row);} catch(IllegalArgumentException invalid) {
                double total=row.stream().mapToDouble(Influence::weight).sum();
                if(!compatible||Math.abs(total-1)>.001)throw invalid;
                row=new ArrayList<>(Influence.normalize(row));
                warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.roundedWeights"));
            }
            rows.add(row);
        }
        if(cursor!=raw.size())throw ProjectCodec.bad(path+".vertices","Trailing weighted path data");
        return new PathGeometry(Collections.nCopies(count,new io.github.samlinb.openbone.core.math.Vec2(0,0)),rows);
    }

    private static List<Keyframe> readDeformKeys(JsonNode array, int size) throws IOException {
        if (!array.isArray() || array.isEmpty())
            throw ProjectCodec.bad("deform", "Expected nonempty keys");
        List<Keyframe> keys = new ArrayList<>();
        double previous = -1;
        for (int i = 0; i < array.size(); i++) {
            var n = array.get(i);
            double time = num(n, "time", 0, "deform");
            if (time <= previous)
                throw ProjectCodec.bad("deform.time", "Keys must be strictly increasing");
            previous = time;
            List<Double> values = new ArrayList<>(Collections.nCopies(size, 0.0));
            if (n.has("vertices")) {
                var raw = ProjectCodec.array(n, "vertices", "deform");
                int offset = n.has("offset") ? ProjectCodec.integer(n.get("offset"), "deform.offset") : 0;
                if (offset < 0 || offset > size || raw.size() > size - offset)
                    throw ProjectCodec.bad("deform.offset", "Sparse values exceed deform layout");
                for (int c = 0; c < raw.size(); c++) {
                    var v = raw.get(c);
                    if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
                        throw ProjectCodec.bad("deform.vertices", "Expected finite offsets");
                    values.set(offset + c, v.doubleValue());
                }
            } else if (n.has("offset"))
                throw ProjectCodec.bad("deform.offset", "Offset requires vertices");
            Curve curve = Curve.LINEAR;
            if (n.has("curve")) {
                if (i + 1 == array.size())
                    throw ProjectCodec.bad("deform.curve", "Last key has no outgoing curve");
                var c = n.get("curve");
                if (c.isTextual()) {
                    if (!c.asText().equals("stepped"))
                        throw ProjectCodec.bad("deform.curve", "Unknown curve");
                    curve = Curve.STEPPED;
                } else {
                    if (!c.isArray() || c.size() != 4)
                        throw ProjectCodec.bad("deform.curve", "Expected four time/percent controls");
                    double[] v = new double[4];
                    for (int j = 0; j < 4; j++) {
                        if (!c.get(j).isNumber())
                            throw ProjectCodec.bad("deform.curve", "Expected finite controls");
                        v[j] = RuntimeRange.number(c.get(j).doubleValue(), "deform curve control");
                    }
                    double dt = num(array.get(i + 1), "time", 0, "deform") - time;
                    if (dt <= 0)
                        throw ProjectCodec.bad("deform.time", "Keys must increase");
                    curve = new Curve(Curve.Kind.BEZIER, (v[0] - time) / dt, v[1], (v[2] - time) / dt, v[3]);
                }
            }
            keys.add(new Keyframe(EntityId.create(), time, values, Collections.nCopies(size, curve)));
        }
        return keys;
    }

    private List<Keyframe> readKeys(JsonNode array, int dimensions, double defaultValue) throws IOException {
        if (!array.isArray() || array.isEmpty())
            throw ProjectCodec.bad("timeline", "Expected nonempty key array");
        array=expandFlatBezierSegments(array,dimensions,defaultValue);
        List<Keyframe> result = new ArrayList<>();
        double last = -1;
        for (int i = 0; i < array.size(); i++) {
            var k = array.get(i);
            double time = num(k, "time", 0, "key");
            if (time <= last)
                throw ProjectCodec.bad("key.time", "Keys must be strictly increasing");
            last = time;
            List<Double> values = new ArrayList<>();
            List<Curve> curves = new ArrayList<>();
            for (int c = 0; c < dimensions; c++) {
                String key = dimensions == 1 ? "value" : c == 0 ? "x" : "y";
                double value = num(k, key, defaultValue, "key");
                values.add(value);
                var curve = k.get("curve");
                Curve parsed = Curve.LINEAR;
                if (curve != null) {
                    if (i == array.size() - 1)
                        throw ProjectCodec.bad("key.curve", "Last key cannot have an outgoing curve");
                    if (curve.isTextual()) {
                        if (!curve.textValue().equals("stepped"))
                            throw ProjectCodec.bad("key.curve", "Only stepped or numeric Bezier allowed");
                        parsed = Curve.STEPPED;
                    } else {
                        if (!curve.isArray() || curve.size() != dimensions * 4)
                            throw ProjectCodec.bad("key.curve",
                                    "Expected " + dimensions * 4 + " absolute control numbers");
                        double nextTime = num(array.get(i + 1), "time", 0, "next key"),
                                nextValue = num(array.get(i + 1), key, defaultValue, "next key");
                        double dt = nextTime - time, dv = nextValue - value;
                        if (dt <= 0)
                            throw ProjectCodec.bad("key.time", "Keys must be strictly increasing");
                        double[] control = new double[4];
                        for (int n = 0; n < 4; n++) {
                            var number = curve.get(c * 4 + n);
                            if (!number.isNumber())
                                throw ProjectCodec.bad("key.curve", "Expected finite control number");
                            control[n] = RuntimeRange.number(number.doubleValue(), "Bezier control");
                        }
                        if (dv == 0) {
                            if (control[1] != value || control[3] != value)
                                throw ProjectCodec.bad("key.curve",
                                        "Equal endpoints with nonconstant controls cannot be represented");
                            parsed = new Curve(Curve.Kind.BEZIER, (control[0] - time) / dt, 0, (control[2] - time) / dt,
                                    1);
                        } else
                            parsed = new Curve(Curve.Kind.BEZIER, (control[0] - time) / dt, (control[1] - value) / dv,
                                    (control[2] - time) / dt, (control[3] - value) / dv);
                    }
                }
                curves.add(parsed);
            }
            result.add(new Keyframe(EntityId.create(), time, values, curves));
        }
        return result;
    }

    /** Preserve the runtime's ten-chord Bezier evaluation when equal endpoints
     * cannot be expressed as a fraction of their zero value delta. Unioning the
     * per-axis chord times also preserves the other axes exactly. */
    private JsonNode expandFlatBezierSegments(JsonNode input,int dimensions,double fallback) throws IOException {
        var output=json.createArrayNode();
        for(int i=0;i<input.size();i++) {
            var key=input.get(i);var curve=key.path("curve");
            boolean expand=false;
            if(i+1<input.size()&&curve.isArray()&&curve.size()==dimensions*4)for(int c=0;c<dimensions;c++) {
                String field=dimensions==1?"value":c==0?"x":"y";
                double start=num(key,field,fallback,"key"),end=num(input.get(i+1),field,fallback,"key");
                if(start==end&&(curve.path(c*4+1).asDouble()!=start||curve.path(c*4+3).asDouble()!=start))expand=true;
            }
            if(!expand){output.add(key);continue;}
            var next=input.get(i+1);
            double startTime=num(key,"time",0,"key"),endTime=num(next,"time",0,"key");
            if(endTime<=startTime)throw ProjectCodec.bad("key.time","Keys must be strictly increasing");
            var times=new TreeSet<Double>();times.add(startTime);
            double[][] xs=new double[dimensions][11],ys=new double[dimensions][11];
            for(int c=0;c<dimensions;c++) {
                String field=dimensions==1?"value":c==0?"x":"y";
                double start=num(key,field,fallback,"key"),end=num(next,field,fallback,"key");
                double[] controls=new double[4];
                for(int n=0;n<4;n++) {
                    var value=curve.get(c*4+n);
                    if(!value.isNumber())throw ProjectCodec.bad("key.curve","Expected finite control number");
                    controls[n]=RuntimeRange.number(value.doubleValue(),"Bezier control");
                }
                if(controls[0]<startTime||controls[0]>endTime||controls[2]<startTime||controls[2]>endTime)
                    throw ProjectCodec.bad("key.curve","Bezier time controls must lie within the segment");
                xs[c][0]=startTime;ys[c][0]=start;xs[c][10]=endTime;ys[c][10]=end;
                for(int step=1;step<10;step++) {
                    double u=step/10.,v=1-u;
                    xs[c][step]=v*v*v*startTime+3*v*v*u*controls[0]+3*v*u*u*controls[2]+u*u*u*endTime;
                    ys[c][step]=v*v*v*start+3*v*v*u*controls[1]+3*v*u*u*controls[3]+u*u*u*end;
                    times.add(xs[c][step]);
                }
            }
            for(double time:times) {
                var sample=output.addObject().put("time",time);
                for(int c=0;c<dimensions;c++) {
                    int step=1;while(step<10&&time>xs[c][step])step++;
                    double alpha=(time-xs[c][step-1])/(xs[c][step]-xs[c][step-1]);
                    sample.put(dimensions==1?"value":c==0?"x":"y",ys[c][step-1]+alpha*(ys[c][step]-ys[c][step-1]));
                }
            }
            warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.flatBezier"));
        }
        return output;
    }

    private static Transform transform(JsonNode n, String p) throws ProjectFormatException {
        return new Transform(num(n, "x", 0, p), num(n, "y", 0, p), num(n, "rotation", 0, p), num(n, "scaleX", 1, p),
                num(n, "scaleY", 1, p), num(n, "shearX", 0, p), num(n, "shearY", 0, p));
    }

    private static double num(JsonNode n, String key, double fallback, String p) throws ProjectFormatException {
        return n.has(key) ? ProjectCodec.number(n, key, p) : fallback;
    }

    private static boolean bool(JsonNode n, String key, boolean fallback, String p) throws ProjectFormatException {
        return n.has(key) ? ProjectCodec.bool(n, key, p) : fallback;
    }

    private static <T extends Enum<T>> T pathMode(JsonNode n, String key, String fallback, Class<T> type, String p)
            throws ProjectFormatException {
        String text = n.has(key) ? ProjectCodec.text(n, key, p) : fallback;
        try {
            T value = Enum.valueOf(type, text.equals("chainScale") ? "CHAIN_SCALE" : text.toUpperCase(Locale.ROOT));
            String canonical = value.name().equals("CHAIN_SCALE") ? "chainScale"
                    : value.name().toLowerCase(Locale.ROOT);
            if (!canonical.equals(text))
                throw new IllegalArgumentException("case-sensitive mode");
            return value;
        } catch (IllegalArgumentException e) {
            throw ProjectCodec.bad(p + "." + key, "Unsupported path mode: " + text);
        }
    }

    private static void object(JsonNode n, String p) throws ProjectFormatException {
        if (n == null || !n.isObject())
            throw ProjectCodec.bad(p, "Expected object");
    }

    private static void allowed(JsonNode n, String p, List<String> errors, String... keys)
            throws ProjectFormatException {
        object(n, p);
        var set = Set.of(keys);
        n.fieldNames().forEachRemaining(k -> {
            if (!set.contains(k))
                errors.add(p + "." + k);
        });
    }

    /**
     * Traverse every supported level first, collecting all unknown fields instead
     * of dropping them.
     */
    private static void scan(JsonNode root, List<String> errors) throws IOException {
        allowed(root, "$", errors, "skeleton", "bones", "slots", "skins", "animations", "ik", "transform", "path",
                "physics", "events");
        P8RuntimeJson.scan(root, errors);
        if (root.has("physics")) {
            int i = 0;
            for (var p : ProjectCodec.array(root, "physics", "$"))
                allowed(p, "$.physics[" + i++ + "]", errors, "name", "order", "skin", "bone", "x", "y", "rotate",
                        "scaleX", "shearX", "limit", "fps", "inertia", "strength", "damping", "mass", "wind", "gravity",
                        "mix", "inertiaGlobal", "strengthGlobal", "dampingGlobal", "massGlobal", "windGlobal",
                        "gravityGlobal", "mixGlobal");
        }
        if (root.has("path")) {
            int i = 0;
            for (var p : ProjectCodec.array(root, "path", "$"))
                allowed(p, "$.path[" + i++ + "]", errors, "name", "order", "skin", "bones", "target", "positionMode",
                        "spacingMode", "rotateMode", "position", "spacing", "rotation", "mixRotate", "mixX", "mixY");
        }
        if (root.has("ik")) {
            int n = 0;
            for (var ik : ProjectCodec.array(root, "ik", "$"))
                allowed(ik, "$.ik[" + n++ + "]", errors, "name", "order", "skin", "bones", "target", "mix", "softness",
                        "bendPositive", "compress", "stretch", "uniform");
        }
        if (root.has("transform")) {
            int i = 0;
            for (var t : ProjectCodec.array(root, "transform", "$"))
                allowed(t, "$.transform[" + i++ + "]", errors, "name", "order", "skin", "bones", "target", "local",
                        "relative", "rotation", "x", "y", "scaleX", "scaleY", "shearY", "mixRotate", "mixX", "mixY",
                        "mixScaleX", "mixScaleY", "mixShearY");
        }
        allowed(root.get("skeleton"), "$.skeleton", errors, "spine", "images", "referenceScale", "hash", "x", "y", "width", "height", "audio", "fps");
        int i = 0;
        for (var b : ProjectCodec.array(root, "bones", "$")) {
            String p = "$.bones[" + i++ + "]";
            allowed(b, p, errors, "name", "parent", "x", "y", "rotation", "scaleX", "scaleY", "length", "inherit", "color", "shearX", "shearY", "icon", "skin");
            if (b.has("inherit")) try { Bone.Inherit.valueOf(b.get("inherit").textValue()); }
            catch (IllegalArgumentException | NullPointerException invalid) { errors.add(p + ".inherit (invalid mode)"); }
        }
        i = 0;
        for (var s : ProjectCodec.array(root, "slots", "$")) {
            String p = "$.slots[" + i++ + "]";
            allowed(s, p, errors, "name", "bone", "attachment", "blend", "color");
            if (s.has("blend") && !Set.of("normal", "additive").contains(s.get("blend").asText()))
                errors.add(p + ".blend (only normal/additive)");
        }
        i = 0;
        for (var skin : ProjectCodec.array(root, "skins", "$")) {
            String p = "$.skins[" + i++ + "]";
            allowed(skin, p, errors, "name", "attachments", "bones", "ik", "transform", "path", "physics");
            if (!skin.has("attachments")) ((ObjectNode)skin).putObject("attachments");
            object(skin.get("attachments"), p + ".attachments");
            for (var si = skin.get("attachments").fields(); si.hasNext();) {
                var se = si.next();
                object(se.getValue(), p + ".attachments." + se.getKey());
                for (var ai = se.getValue().fields(); ai.hasNext();) {
                    var ae = ai.next();
                    var n = ae.getValue();
                    String q = p + ".attachments." + se.getKey() + "." + ae.getKey();
                    if (n.path("type").asText().equals("boundingbox"))
                        allowed(n, q, errors, "type", "vertexCount", "vertices", "color");
                    else if (n.path("type").asText().equals("clipping"))
                        allowed(n, q, errors, "type", "end", "vertexCount", "vertices", "color");
                    else if (n.path("type").asText().equals("path"))
                        allowed(n, q, errors, "type", "closed", "constantSpeed", "vertexCount", "vertices", "lengths",
                                "color");
                    else if (n.path("type").asText().equals("mesh"))
                        allowed(n, q, errors, "type", "path", "vertices", "uvs", "triangles", "hull", "color", "edges", "width", "height");
                    else
                        allowed(n, q, errors, "type", "path", "x", "y", "rotation", "scaleX", "scaleY", "width",
                                "height", "color");
                    if (n.has("type") && !Set.of("region", "mesh", "clipping", "path", "boundingbox").contains(n.get("type").asText()))
                        errors.add(q + ".type (only region/mesh/clipping/path/boundingbox)");
                    if (n.has("color") && !Set.of("clipping","boundingbox").contains(n.path("type").asText()) && !n.get("color").asText().equalsIgnoreCase("ffffffff"))
                        errors.add(q + ".color (only white)");
                }
            }
        }
        if (root.has("animations")) {
            object(root.get("animations"), "$.animations");
            for (var ai = root.get("animations").fields(); ai.hasNext();) {
                var ae = ai.next();
                String p = "$.animations." + ae.getKey();
                allowed(ae.getValue(), p, errors, "bones", "attachments", "ik", "transform", "path", "physics", "slots",
                        "events", "drawOrder");
                ConstraintTimelineJson.scan(ae.getValue(), p, errors);
                if (ae.getValue().has("attachments")) {
                    var skins = ae.getValue().get("attachments");
                    object(skins, p + ".attachments");
                    for (var si = skins.fields(); si.hasNext();) {
                        var se = si.next();
                        String q = p + ".attachments." + se.getKey();
                        object(se.getValue(), q);
                        for (var slots = se.getValue().fields(); slots.hasNext();) {
                            var slot = slots.next();
                            String r = q + "." + slot.getKey();
                            object(slot.getValue(), r);
                            for (var attachments = slot.getValue().fields(); attachments.hasNext();) {
                                var a = attachments.next();
                                String s = r + "." + a.getKey();
                                allowed(a.getValue(), s, errors, "deform");
                                if (a.getValue().has("deform")) {
                                    var keys = a.getValue().get("deform");
                                    if (!keys.isArray())
                                        throw ProjectCodec.bad(s + ".deform", "Expected key array");
                                    int k = 0;
                                    for (var key : keys)
                                        allowed(key, s + ".deform[" + k++ + "]", errors, "time", "vertices", "offset",
                                                "curve");
                                }
                            }
                        }
                    }
                }
                if (!ae.getValue().has("bones"))
                    continue;
                object(ae.getValue().get("bones"), p + ".bones");
                for (var bi = ae.getValue().get("bones").fields(); bi.hasNext();) {
                    var be = bi.next();
                    String q = p + ".bones." + be.getKey();
                    allowed(be.getValue(), q, errors, "rotate", "translate", "translatex", "translatey", "scale",
                            "scalex", "scaley", "shear", "shearx", "sheary");
                    for (var ti = be.getValue().fields(); ti.hasNext();) {
                        var te = ti.next();
                        if (!List.of("rotate", "translate", "translatex", "translatey", "scale", "scalex", "scaley", "shear", "shearx", "sheary")
                                .contains(te.getKey()))
                            continue;
                        if (!te.getValue().isArray())
                            throw ProjectCodec.bad(q + "." + te.getKey(), "Expected key array");
                        int k = 0;
                        boolean pair = te.getKey().equals("translate") || te.getKey().equals("scale") || te.getKey().equals("shear");
                        for (var key : te.getValue()) {
                            if (pair)
                                allowed(key, q + "." + te.getKey() + "[" + k++ + "]", errors, "time", "x", "y",
                                        "curve");
                            else
                                allowed(key, q + "." + te.getKey() + "[" + k++ + "]", errors, "time", "value", "curve");
                        }
                    }
                }
            }
        }
    }
}
