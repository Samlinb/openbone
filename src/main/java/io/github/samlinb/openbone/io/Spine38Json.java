package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;

/** Independent translation of the 3.8 JSON schema to the editor's supported subset. */
final class Spine38Json {
    static final String VERSION = "3.8.75";
    private Spine38Json() {}

    static void fields(ObjectNode n, String p, List<String> warnings, String... names) {
        Set<String> keep = Set.of(names);
        var keys = new ArrayList<String>();
        n.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) if (!keep.contains(k)) {
            warnings.add(p + "." + k + ": unsupported field filtered");
            n.remove(k);
        }
    }

    private static void numbers(ObjectNode n, String p, List<String> warnings, String... names) {
        for (String k : names) if (n.has(k) && (!n.get(k).isNumber()
                || !Double.isFinite(n.get(k).doubleValue()) || Math.abs(n.get(k).doubleValue()) > RuntimeRange.LIMIT)) {
            warnings.add(p + "." + k + ": invalid number; using default");
            n.remove(k);
        }
    }

    private static void rename(ObjectNode n, String from, String to) {
        if (n.has(from)) n.set(to, n.remove(from));
    }

    static ObjectNode normalize(JsonNode input, List<String> warnings) throws ProjectFormatException {
        if (!(input instanceof ObjectNode)) throw ProjectCodec.bad("$", "Expected object");
        ObjectNode root = input.deepCopy();
        fields(root, "$", warnings, "skeleton", "bones", "slots", "skins", "animations", "ik", "transform", "path", "events");
        ObjectNode meta = (ObjectNode)root.get("skeleton");
        fields(meta, "$.skeleton", warnings, "spine", "images");
        meta.put("spine", SkeletonJsonCodec.VERSION);
        if (meta.has("images") && !meta.get("images").isTextual()) meta.remove("images");
        String images = meta.path("images").asText();
        if (images.contains(":") || images.contains("\\") || images.startsWith("/") || Arrays.asList(images.split("/")).contains("..")) {
            warnings.add("$.skeleton.images: unsafe image directory filtered; using source directory"); meta.remove("images");
        }
        Set<String> bones = new LinkedHashSet<>(), slots = new LinkedHashSet<>();
        ArrayNode boneArray = root.putArray("bones");
        var pending = new ArrayList<ObjectNode>();
        for (var b : input.path("bones")) {
            if (!b.isObject() || !validName(b.path("name"))) { warnings.add("$.bones: invalid bone filtered"); continue; }
            ObjectNode n = b.deepCopy();
            if (n.path("transform").asText().equals("normal")) n.remove("transform");
            fields(n, "$.bones." + n.path("name").asText(), warnings,
                    "name", "parent", "x", "y", "rotation", "scaleX", "scaleY", "length");
            numbers(n, "$.bones." + n.path("name").asText(), warnings, "x", "y", "rotation", "scaleX", "scaleY", "length");
            if (n.path("length").asDouble() < 0) n.remove("length");
            pending.add(n);
        }
        boolean progress;
        do {
            progress = false;
            for (var it = pending.iterator(); it.hasNext();) {
                var n = it.next(); String name = n.path("name").asText();
                if (bones.contains(name)) { warnings.add("$.bones." + name + ": duplicate filtered"); it.remove(); continue; }
                if (n.has("parent") && !bones.contains(n.path("parent").asText())) continue;
                if (!n.has("parent") && !boneArray.isEmpty()) {
                    n.put("parent", boneArray.get(0).path("name").asText());
                    warnings.add("$.bones." + name + ": additional root attached to first root");
                }
                boneArray.add(n); bones.add(name); it.remove(); progress = true;
            }
        } while (progress && !pending.isEmpty());
        for (var b : pending) warnings.add("$.bones." + b.path("name").asText() + ": missing/cyclic parent; bone filtered");
        if (boneArray.isEmpty()) throw ProjectCodec.bad("$.bones", "No valid root bone");
        var slotArray = root.putArray("slots");
        for (var s : input.path("slots")) {
            if (!s.isObject() || !validName(s.path("name")) || !bones.contains(s.path("bone").asText())
                    || !slots.add(s.path("name").asText())) { warnings.add("$.slots: invalid/duplicate slot filtered"); continue; }
            ObjectNode n = s.deepCopy();
            fields(n, "$.slots." + n.path("name").asText(), warnings, "name", "bone", "attachment", "blend", "color");
            if(n.has("color")) try { Rgba.parse(n.get("color").textValue()); }
            catch(IllegalArgumentException invalid) { warnings.add("$.slots."+n.path("name").asText()+".color: invalid setup color filtered"); n.remove("color"); }
            if (n.has("blend") && !Set.of("normal", "additive").contains(n.path("blend").asText())) {
                warnings.add("$.slots." + n.path("name").asText() + ".blend: unsupported blend; using normal"); n.remove("blend");
            }
            if (n.has("attachment") && !validName(n.get("attachment"))) n.remove("attachment");
            slotArray.add(n);
        }
        var skins = root.get("skins");
        if (skins != null && skins.isObject()) {
            var array = root.putArray("skins");
            skins.fields().forEachRemaining(e -> array.addObject().put("name", e.getKey()).set("attachments", e.getValue()));
        }
        if (!root.path("skins").isArray() || root.path("skins").isEmpty())
            root.putArray("skins").addObject().put("name", "default").putObject("attachments");
        var skinNames = new HashSet<String>();
        for (var it = root.path("skins").iterator(); it.hasNext();) {
            var skin = it.next();
            if (!skin.isObject() || !validName(skin.path("name")) || !skinNames.add(skin.path("name").asText())) {
                warnings.add("$.skins: invalid/duplicate skin filtered"); it.remove();
            }
        }
        if (!skinNames.contains("default")) {
            var skin = JsonNodeFactory.instance.objectNode().put("name", "default"); skin.putObject("attachments");
            ((ArrayNode)root.path("skins")).insert(0, skin);
            warnings.add("$.skins: added empty default skin");
        }
        for (var s : root.path("skins")) {
            if (!(s instanceof ObjectNode skin)) throw ProjectCodec.bad("$.skins", "Expected skin object");
            fields(skin, "$.skins", warnings, "name", "attachments");
            if (!skin.path("attachments").isObject()) skin.putObject("attachments");
            var all = (ObjectNode)skin.get("attachments");
            for (var it = all.fields(); it.hasNext();) {
                var e = it.next();
                if (!slots.contains(e.getKey()) || !e.getValue().isObject()) {
                    warnings.add("$.skins." + e.getKey() + ": invalid slot filtered"); it.remove(); continue;
                }
                for (var ai = e.getValue().fields(); ai.hasNext();) {
                    var a = ai.next(); String p = "$.skins." + skin.path("name").asText() + "." + e.getKey() + "." + a.getKey();
                    if (!(a.getValue() instanceof ObjectNode n) || !validName(TextNode.valueOf(a.getKey()))) {
                        warnings.add(p + ": invalid attachment filtered"); ai.remove(); continue;
                    }
                    String type = n.path("type").asText("region");
                    switch (type) {
                        case "region" -> fields(n, p, warnings, "type", "path", "x", "y", "rotation", "scaleX", "scaleY", "width", "height");
                        case "mesh" -> fields(n, p, warnings, "type", "path", "vertices", "uvs", "triangles", "hull");
                        case "clipping" -> fields(n, p, warnings, "type", "end", "vertexCount", "vertices");
                        case "path" -> fields(n, p, warnings, "type", "closed", "constantSpeed", "vertexCount", "vertices", "lengths");
                        default -> { warnings.add(p + ": unsupported attachment " + type + " filtered"); ai.remove(); continue; }
                    }
                    numbers(n, p, warnings, "x", "y", "rotation", "scaleX", "scaleY", "width", "height");
                    if (type.equals("mesh")) remapWeights(n, input.path("bones"), boneArray, p, warnings);
                    if (n.has("width") && n.path("width").asDouble() <= 0) n.remove("width");
                    if (n.has("height") && n.path("height").asDouble() <= 0) n.remove("height");
                }
            }
        }
        // Setup attachment references are optional. Keep the slot even if its attachment was filtered.
        for (var s : slotArray) if (s.has("attachment") && !hasAttachment(root, s.path("name").asText(), s.path("attachment").asText())) {
            warnings.add("$.slots." + s.path("name").asText() + ".attachment: unknown attachment filtered"); ((ObjectNode)s).remove("attachment");
        }
        for (String kind : List.of("ik", "transform", "path")) if (root.has(kind)) {
            var source = root.remove(kind); var out = root.putArray(kind);
            for (var c : source) {
                if (!(c instanceof ObjectNode n) || !validName(c.path("name")) || !c.path("bones").isArray()
                        || !(kind.equals("path") ? slots : bones).contains(c.path("target").asText())) {
                    warnings.add("$." + kind + ": invalid constraint filtered"); continue;
                }
                boolean valid = true; for (var b : n.path("bones")) valid &= bones.contains(b.asText());
                if (!valid || n.path("bones").isEmpty()) { warnings.add("$." + kind + ": missing bone; constraint filtered"); continue; }
                mixes(n, false);
                if (kind.equals("ik")) fields(n, "$.ik", warnings, "name", "order", "bones", "target", "mix", "softness", "bendPositive", "compress", "stretch", "uniform");
                else if (kind.equals("transform")) fields(n, "$.transform", warnings, "name", "order", "bones", "target", "local", "relative", "rotation", "x", "y", "scaleX", "scaleY", "shearY", "mixRotate", "mixX", "mixY", "mixScaleX", "mixScaleY", "mixShearY");
                else fields(n, "$.path", warnings, "name", "order", "bones", "target", "positionMode", "spacingMode", "rotateMode", "position", "spacing", "rotation", "mixRotate", "mixX", "mixY");
                out.add(n);
            }
        }
        if (!root.path("animations").isObject()) root.putObject("animations");
        for (var it = root.path("animations").fields(); it.hasNext();) {
            var e = it.next(); String p = "$.animations." + e.getKey();
            if (!(e.getValue() instanceof ObjectNode anim) || !validName(TextNode.valueOf(e.getKey()))) { warnings.add(p + ": invalid animation filtered"); it.remove(); continue; }
            fields(anim, p, warnings, "bones", "slots", "events", "drawOrder", "ik", "transform", "path", "deform");
            if (anim.has("bones")) {
                if (!anim.get("bones").isObject()) { warnings.add(p + ".bones: invalid object filtered"); anim.remove("bones"); }
                else for (var bi = anim.get("bones").fields(); bi.hasNext();) {
                    var b = bi.next();
                    if (!bones.contains(b.getKey()) || !(b.getValue() instanceof ObjectNode bt)) { warnings.add(p + ".bones." + b.getKey() + ": unknown bone filtered"); bi.remove(); continue; }
                    fields(bt, p + ".bones." + b.getKey(), warnings, "rotate", "translate", "scale");
                    for (var ti = bt.fields(); ti.hasNext();) {
                        var t = ti.next(); String q = p + ".bones." + b.getKey() + "." + t.getKey();
                        if (!t.getValue().isArray()) { warnings.add(q + ": invalid timeline filtered"); ti.remove(); continue; }
                        var keys = cleanKeys(t.getValue(), q, warnings);
                        if (keys.isEmpty()) { ti.remove(); continue; }
                        for (var k : keys) {
                            if (t.getKey().equals("rotate")) rename((ObjectNode)k, "angle", "value");
                            fields((ObjectNode)k, q, warnings, "time", "value", "x", "y", "curve", "c2", "c3", "c4");
                            numbers((ObjectNode)k, q, warnings, "value", "x", "y");
                        }
                        convertCurves(keys, t.getKey().equals("rotate") ? new String[]{"value"} : new String[]{"x", "y"}, t.getKey().equals("scale") ? 1 : 0, q, warnings);
                        bt.set(t.getKey(), keys);
                    }
                }
            }
            if (anim.has("slots")) {
                if (!anim.get("slots").isObject()) anim.remove("slots");
                else for (var si = anim.get("slots").fields(); si.hasNext();) {
                    var s = si.next(); String q = p + ".slots." + s.getKey();
                    if (!slots.contains(s.getKey()) || !(s.getValue() instanceof ObjectNode row)) { warnings.add(q + ": invalid slot filtered"); si.remove(); continue; }
                    fields(row, q, warnings, "attachment", "color");
                    if(row.has("color")) {
                        var colors=cleanKeys(row.remove("color"),q+".color",warnings);
                        for(var ci=colors.iterator();ci.hasNext();) {
                            var k=(ObjectNode)ci.next();
                            fields(k,q+".color",warnings,"time","color","curve","c2","c3","c4");
                            try {
                                var color=Rgba.parse(k.path("color").textValue());
                                k.put("r",color.r()).put("g",color.g()).put("b",color.b()).put("a",color.a());
                            } catch(IllegalArgumentException invalid) { warnings.add(q+".color: invalid color key filtered"); ci.remove(); }
                        }
                        convertCurves(colors,new String[]{"r","g","b","a"},0,q+".color",warnings);
                        for(var k:colors) ((ObjectNode)k).remove(List.of("r","g","b","a"));
                        if(!colors.isEmpty()) row.set("rgba",colors);
                    }
                    var keys = cleanKeys(row.path("attachment"), q, warnings);
                    for (var ki = keys.iterator(); ki.hasNext();) {
                        var k = (ObjectNode)ki.next(); fields(k, q, warnings, "time", "name");
                        if (k.hasNonNull("name") && (!k.get("name").isTextual() || !hasAttachment(root, s.getKey(), k.path("name").asText()))) {
                            warnings.add(q + ".attachment: unknown attachment key filtered"); ki.remove();
                        }
                    }
                    if (keys.isEmpty()) { row.remove("attachment"); if(!row.has("rgba")) si.remove(); }
                    else row.set("attachment", keys);
                }
            }
            // 3.8 uses a direct deform array; 4.2 wraps each attachment in {deform: ...}.
            if (anim.has("deform")) {
                JsonNode deform = anim.remove("deform"); var target = anim.putObject("attachments");
                deform.fields().forEachRemaining(s -> s.getValue().fields().forEachRemaining(slot -> slot.getValue().fields().forEachRemaining(a -> {
                    var keys = cleanKeys(a.getValue(), p + ".deform", warnings);
                    convertCurves(keys, new String[0], 0, p + ".deform", warnings);
                    target.withObject("/" + escape(s.getKey())).withObject("/" + escape(slot.getKey())).putObject(a.getKey()).set("deform", keys);
                })));
            }
            for (String kind : List.of("ik", "transform", "path")) if (anim.has(kind)) {
                if (!anim.get(kind).isObject()) { warnings.add(p + "." + kind + ": invalid timeline group filtered"); anim.remove(kind); continue; }
                for (var ci = anim.get(kind).fields(); ci.hasNext();) {
                    var c = ci.next(); String q = p + "." + kind + "." + c.getKey();
                    boolean exists = false;
                    for (var setup : root.path(kind)) exists |= setup.path("name").asText().equals(c.getKey());
                    if (!exists) { warnings.add(q + ": unknown constraint filtered"); ci.remove(); continue; }
                    if (!kind.equals("path")) {
                        var keys = constraintKeys(c.getValue(), kind.equals("ik") ? ConstraintChannel.IK : ConstraintChannel.TRANSFORM_MIX, q, warnings);
                        ((ObjectNode)anim.get(kind)).set(c.getKey(), keys);
                    } else if (c.getValue() instanceof ObjectNode groups) {
                        fields(groups, q, warnings, "position", "spacing", "mix");
                        for (var gi = groups.fields(); gi.hasNext();) {
                            var g = gi.next();
                            var channel = g.getKey().equals("position") ? ConstraintChannel.PATH_POSITION : g.getKey().equals("spacing") ? ConstraintChannel.PATH_SPACING : ConstraintChannel.PATH_MIX;
                            groups.set(g.getKey(), constraintKeys(g.getValue(), channel, q + "." + g.getKey(), warnings));
                        }
                    } else { warnings.add(q + ": invalid path timeline filtered"); ci.remove(); }
                }
            }
        }
        return root;
    }

    private static boolean validName(JsonNode n) { return n.isTextual() && !n.asText().isBlank() && n.asText().equals(n.asText().trim()) && n.asText().length() <= 256; }
    private static void remapWeights(ObjectNode mesh, JsonNode originalBones, ArrayNode bones, String p, List<String> warnings) {
        if (!(mesh.get("vertices") instanceof ArrayNode vertices) || !mesh.path("uvs").isArray()
                || vertices.size() == mesh.path("uvs").size()) return;
        var indices = new HashMap<String, Integer>();
        for (int i = 0; i < bones.size(); i++) indices.put(bones.get(i).path("name").asText(), i);
        int cursor = 0;
        while (cursor < vertices.size()) {
            var count = vertices.get(cursor++);
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 1 || count.asInt() > 4
                    || vertices.size() - cursor < count.asInt() * 4) return; // The decoder reports malformed vertex rows.
            for (int i = 0; i < count.asInt(); i++, cursor += 4) {
                var index = vertices.get(cursor);
                if (!index.isIntegralNumber() || !index.canConvertToInt()) return;
                int old = index.asInt();
                int mapped = old >= 0 && old < originalBones.size()
                        ? indices.getOrDefault(originalBones.get(old).path("name").asText(), -1) : -1;
                if (mapped != old) {
                    vertices.set(cursor, JsonNodeFactory.instance.numberNode(mapped));
                    warnings.add(p + ".vertices: remapped bone index " + old + " to " + mapped);
                }
            }
        }
    }
    private static ArrayNode constraintKeys(JsonNode source, ConstraintChannel channel, String p, List<String> warnings) {
        var keys = cleanKeys(source, p, warnings);
        String[] names = ConstraintTimelineJson.fields(channel);
        for (var key : keys) {
            var n = (ObjectNode)key;
            mixes(n, false);
            if (channel == ConstraintChannel.PATH_POSITION) rename(n, "position", "value");
            if (channel == ConstraintChannel.PATH_SPACING) rename(n, "spacing", "value");
            var allowed = new ArrayList<>(List.of(names)); allowed.addAll(List.of("time", "curve", "c2", "c3", "c4"));
            fields(n, p, warnings, allowed.toArray(String[]::new));
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                if (channel.discrete(i)) {
                    if (n.has(name) && !n.get(name).isBoolean()) { warnings.add(p + "." + name + ": invalid boolean; using default"); n.remove(name); }
                } else {
                    numbers(n, p, warnings, name);
                    if (!n.has(name)) n.put(name, channel == ConstraintChannel.IK ? i == 0 ? 1 : 0
                            : channel == ConstraintChannel.PATH_POSITION || channel == ConstraintChannel.PATH_SPACING ? 0 : 1);
                }
            }
        }
        convertCurves(keys, channel == ConstraintChannel.IK ? new String[]{"mix", "softness"} : names, 0, p, warnings);
        return keys;
    }
    private static String escape(String s) { return s.replace("~", "~0").replace("/", "~1"); }
    private static boolean hasAttachment(JsonNode root, String slot, String name) {
        for (var skin : root.path("skins")) if (skin.path("attachments").path(slot).has(name)) return true;
        return false;
    }
    private static ArrayNode cleanKeys(JsonNode source, String p, List<String> warnings) {
        var times = new TreeMap<Double, ObjectNode>();
        if (!source.isArray()) {
            if (!source.isMissingNode()) warnings.add(p + ": invalid key array filtered");
            return JsonNodeFactory.instance.arrayNode();
        }
        for (var k : source) {
            double t = k.path("time").asDouble(0);
            if (!(k instanceof ObjectNode) || k.has("time") && !k.get("time").isNumber() || !Double.isFinite(t) || t < 0 || t > RuntimeRange.LIMIT) {
                warnings.add(p + ": invalid key filtered"); continue;
            }
            if (times.putIfAbsent(t == 0 ? 0 : t, k.deepCopy()) != null) warnings.add(p + ": duplicate time filtered: " + t);
        }
        var out = JsonNodeFactory.instance.arrayNode(); times.values().forEach(out::add); return out;
    }

    private static void convertCurves(ArrayNode keys, String[] components, double fallback, String p, List<String> warnings) {
        for (int i = 0; i < keys.size(); i++) {
            var k = (ObjectNode)keys.get(i); JsonNode curve = k.remove("curve");
            numbers(k, p, warnings, "c2", "c3", "c4");
            double[] c = {curve != null && curve.isNumber() ? curve.asDouble() : .25, k.path("c2").asDouble(0), k.path("c3").asDouble(1), k.path("c4").asDouble(1)};
            k.remove(List.of("c2", "c3", "c4"));
            if (curve == null || i == keys.size() - 1) continue;
            if (curve.isTextual() && curve.asText().equals("stepped")) { k.put("curve", "stepped"); continue; }
            if (curve.isArray() && curve.size() == 4) for (int j = 0; j < 4; j++) c[j] = curve.get(j).asDouble(Double.NaN);
            boolean valid = curve.isNumber() || curve.isArray() && curve.size() == 4;
            for (double v : c) valid &= Double.isFinite(v);
            valid &= c[0] >= 0 && c[0] <= c[2] && c[2] <= 1;
            if (!valid) { warnings.add(p + ".curve: invalid curve; using linear"); continue; }
            var next = keys.get(i + 1); double time = k.path("time").asDouble(), dt = next.path("time").asDouble() - time;
            var out = k.putArray("curve");
            if (components.length == 0) out.add(time + dt*c[0]).add(c[1]).add(time + dt*c[2]).add(c[3]);
            else for (String component : components) {
                double value = k.path(component).asDouble(fallback), dv = next.path(component).asDouble(fallback) - value;
                out.add(time + dt*c[0]).add(value + dv*c[1]).add(time + dt*c[2]).add(value + dv*c[3]);
            }
        }
    }

    private static void mixes(ObjectNode n, boolean reverse) {
        if (!reverse) {
            rename(n, "rotateMix", "mixRotate"); rename(n, "shearMix", "mixShearY");
            if (n.has("translateMix")) { var v = n.remove("translateMix"); n.set("mixX", v); n.set("mixY", v); }
            if (n.has("scaleMix")) { var v = n.remove("scaleMix"); n.set("mixScaleX", v); n.set("mixScaleY", v); }
        } else {
            rename(n, "mixRotate", "rotateMix"); rename(n, "mixShearY", "shearMix");
            collapse(n, "mixX", "mixY", "translateMix"); collapse(n, "mixScaleX", "mixScaleY", "scaleMix");
        }
    }
    private static void collapse(ObjectNode n, String x, String y, String to) {
        if (!n.has(x)) return;
        if (n.has(y) && n.path(x).asDouble() != n.path(y).asDouble()) throw new IllegalArgumentException("3.8 requires equal " + x + "/" + y);
        rename(n, x, to); n.remove(y);
    }

    static void export(ObjectNode root, DocumentSnapshot d) throws ProjectFormatException {
        if (d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint)) throw ProjectCodec.bad("$.physics", "Spine 3.8 has no physics constraints");
        if (d.constraints().stream().anyMatch(c -> c instanceof PathConstraint p && p.spacingMode() == PathConstraint.SpacingMode.PROPORTIONAL))
            throw ProjectCodec.bad("$.path.spacingMode", "Spine 3.8 does not support proportional spacing");
        ((ObjectNode)root.get("skeleton")).put("spine", VERSION);
        root.path("bones").forEach(b -> { ((ObjectNode)b).remove("inherit"); ((ObjectNode)b).put("transform", "normal"); });
        for (var clip : d.animations()) {
            var anim = (ObjectNode)root.path("animations").get(clip.name()); var bones = anim.putObject("bones");
            SlotColorJson.export38(anim,d,clip.discrete().colors());
            for (var track : clip.timelines()) {
                var row = bones.withObject("/" + escape(d.bone(track.boneId()).name()));
                String type = track.channel() == Channel.ROTATE ? "rotate" : track.channel() == Channel.SCALE ? "scale" : "translate";
                var keys = row.putArray(type);
                for (int i = 0; i < track.keys().size(); i++) {
                    var k = track.keys().get(i); var n = keys.addObject().put("time", k.time());
                    if (track.channel() == Channel.ROTATE) n.put("angle", k.values().get(0));
                    else n.put("x", k.values().get(0)).put("y", k.values().get(1));
                    if (i + 1 < track.keys().size()) {
                        var next = track.keys().get(i + 1);
                        Curve shared = null;
                        for (int j = 0; j < track.channel().components(); j++) {
                            if (k.values().get(j).equals(next.values().get(j))) continue;
                            var c = k.curves().get(j);
                            if (shared != null && !shared.equals(c)) throw ProjectCodec.bad("$.animations." + clip.name(), "Spine 3.8 requires the same curve for both axes");
                            shared = c;
                        }
                        if (shared != null) relativeCurve(n, shared);
                    }
                }
            }
            if (anim.has("attachments")) {
                var source = anim.remove("attachments"); var out = anim.putObject("deform");
                source.fields().forEachRemaining(s -> s.getValue().fields().forEachRemaining(slot -> slot.getValue().fields().forEachRemaining(a -> {
                    var keys = (ArrayNode)a.getValue().path("deform");
                    for (int i=0;i+1<keys.size();i++) {
                        var n=(ObjectNode)keys.get(i); var curve=n.get("curve");
                        if(curve!=null && curve.isArray()) {
                            double t=n.path("time").asDouble(), dt=keys.get(i+1).path("time").asDouble()-t;
                            n.put("curve",(curve.get(0).asDouble()-t)/dt).put("c2",curve.get(1).asDouble()).put("c3",(curve.get(2).asDouble()-t)/dt).put("c4",curve.get(3).asDouble());
                        }
                    }
                    out.withObject("/"+escape(s.getKey())).withObject("/"+escape(slot.getKey())).set(a.getKey(),keys);
                })));
            }
            for (var track : clip.constraintTracks()) {
                var channel = track.channel();
                var setup = d.constraints().stream().filter(c -> c.id().equals(track.constraintId())).findFirst().orElseThrow();
                JsonNode group = anim.path(ConstraintTimelineJson.section(channel)).path(setup.name());
                if (channel == ConstraintChannel.PATH_POSITION || channel == ConstraintChannel.PATH_SPACING || channel == ConstraintChannel.PATH_MIX)
                    group = group.path(ConstraintTimelineJson.group(channel));
                var keys = (ArrayNode)group;
                for (int i = 0; i < keys.size(); i++) {
                    var n = (ObjectNode)keys.get(i); n.remove("curve");
                    if (i + 1 < keys.size()) {
                        var key = track.keys().get(i); var next = track.keys().get(i + 1);
                        Curve shared = null;
                        for (int j = 0; j < channel.components(); j++) {
                            if (channel.discrete(j) || key.values().get(j).equals(next.values().get(j))) continue;
                            Curve c = key.curves().get(j);
                            if (shared != null && !shared.equals(c)) throw ProjectCodec.bad("$.animations." + clip.name(), "Spine 3.8 requires a shared constraint curve");
                            shared = c;
                        }
                        if (shared != null) relativeCurve(n, shared);
                    }
                    try { mixes(n, true); }
                    catch (IllegalArgumentException invalid) { throw new ProjectFormatException(invalid.getMessage(), invalid); }
                    if (channel == ConstraintChannel.PATH_POSITION) rename(n, "value", "position");
                    if (channel == ConstraintChannel.PATH_SPACING) rename(n, "value", "spacing");
                }
            }
        }
        try { for(String kind:List.of("transform","path")) root.path(kind).forEach(n -> mixes((ObjectNode)n,true)); }
        catch(IllegalArgumentException e) { throw new ProjectFormatException(e.getMessage(),e); }
    }
    private static void relativeCurve(ObjectNode n, Curve c) {
        if(c.kind()==Curve.Kind.STEPPED) n.put("curve","stepped");
        if(c.kind()==Curve.Kind.BEZIER) n.put("curve",c.x1()).put("c2",c.y1()).put("c3",c.x2()).put("c4",c.y2());
    }
}
