package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Independent, bounded decoder of the Spine 3.8 and 4.0–4.3 export wire families.
 * Translates into the existing editable JSON contract; never edits source files.
 * Format reference: EsotericSoftware/spine-runtimes, matching version branch, SkeletonBinary.
 * No runtime implementation or dependency is bundled.
 */
final class Spine40Binary {
    private final ByteArrayInputStream bytes;
    private final DataInputStream in;
    private final ObjectNode root = JsonNodeFactory.instance.objectNode();
    private final List<String> strings = new ArrayList<>(), bones = new ArrayList<>(), slots = new ArrayList<>();
    private final List<String> skins = new ArrayList<>(), events = new ArrayList<>();
    private final List<ObjectNode> eventData = new ArrayList<>();
    private boolean nonessential;
    private String sourceVersion;
    private SpineVersion family;
    private int budget = 1_000_000;

    record Decoded(String sourceVersion, ObjectNode tree, SpineImportPolicy.Selection selection) {}

    static boolean isBinary(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".skel") || name.endsWith(".skel.bytes");
    }

    static String stem(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        int end = lower.endsWith(".skel.bytes") ? name.length() - 11
                : lower.endsWith(".skel") ? name.length() - 5 : name.lastIndexOf('.');
        return end < 0 ? name : name.substring(0, end);
    }

    static Decoded decode(byte[] data) throws IOException {
        return decode(data,null);
    }

    static Decoded decode(byte[] data, SpineImportPolicy.Approval approval) throws IOException {
        if (data.length > ProjectCodec.MAX_BYTES) throw new ProjectFormatException(m("spineBinary.tooLarge"));
        var selection=SpineImportPolicy.select(data,SpineImportPolicy.Format.BINARY,approval);
        var reader = new Spine40Binary(data);
        reader.family=selection.parser().family();
        try {
            reader.read();
            return new Decoded(reader.sourceVersion, reader.root, selection);
        }
        catch (IOException | IllegalArgumentException | IndexOutOfBoundsException ex) {
            throw new ProjectFormatException(m("spineBinary.invalid", data.length - reader.bytes.available(),
                    ex instanceof EOFException ? m("spineBinary.truncated") : ex.getMessage()), ex);
        }
    }

    private Spine40Binary(byte[] data) { bytes = new ByteArrayInputStream(data); in = new DataInputStream(bytes); }
    private ProjectFormatException unsupported(String feature) { return new ProjectFormatException(m("spineBinary.unsupported", feature)); }
    private int varint() throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            if (shift == 28 && (b & 0xf0) != 0) throw unsupported("varint");
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw unsupported("varint");
    }
    private int count() throws IOException {
        int n = varint();
        if (n < 0 || n > bytes.available() || n > budget) throw unsupported("count/size limit");
        budget -= n;
        return n;
    }
    private int signed() throws IOException { int n = varint(); return (n >>> 1) ^ -(n & 1); }
    private boolean bool() throws IOException {
        int n = in.readUnsignedByte();
        if (n > 1) throw unsupported("boolean");
        return n == 1;
    }
    private double number() throws IOException {
        float n = in.readFloat();
        if (!Float.isFinite(n)) throw unsupported("non-finite number");
        return n;
    }
    private String text() throws IOException {
        int n = varint();
        if (n < 0 || n > 1_000_001 || n - 1 > bytes.available()) throw unsupported("string size");
        if (n == 0) return null;
        byte[] utf8 = in.readNBytes(n - 1);
        if (utf8.length != n - 1) throw new EOFException();
        return new String(utf8, StandardCharsets.UTF_8);
    }
    private String ref() throws IOException { int n = varint(); return n == 0 ? null : at(strings, n - 1); }
    private String required(String s) throws IOException { if (s == null || s.isEmpty()) throw unsupported("empty name"); return s; }
    private <T> T at(List<T> list, int index) throws IOException {
        if (index < 0 || index >= list.size()) throw unsupported("index " + index);
        return list.get(index);
    }
    private String enumValue(String... values) throws IOException { return at(Arrays.asList(values), varint()); }
    private String color() throws IOException { return String.format(Locale.ROOT, "%08x", in.readInt()); }
    private void floats(ObjectNode n, String... fields) throws IOException { for (String f : fields) n.put(f, number()); }
    private void skinRequired(ObjectNode node) throws IOException { node.put("skin",bool()); }
    private ObjectNode named(ArrayNode array, List<String> names) throws IOException {
        String name = required(text()); names.add(name); return array.addObject().put("name", name);
    }
    private ObjectNode read() throws IOException {
        String hash=family.legacy()?text():Long.toString(in.readLong()); String version = text();
        // Version gating and explicit fallback consent were checked against the same bytes before reading the body.
        sourceVersion = version;
        var meta = root.putObject("skeleton").put("spine", SkeletonJsonCodec.VERSION).put("hash", hash);
        floats(meta, "x", "y", "width", "height");
        if(family.atLeast(SpineVersion.V42))meta.put("referenceScale",number());
        nonessential = bool();
        if (nonessential) { meta.put("fps", number()); text();String audio=text();if(audio!=null)meta.put("audio",audio); }
        // Atlas exports may retain the author's images directory. Resolve texture regions beside the export.
        for (int i = 0, n = count(); i < n; i++) strings.add(text());
        var boneArray = root.putArray("bones");
        for (int i = 0, n = count(); i < n; i++) {
            var b = named(boneArray, bones);
            if (i > 0) b.put("parent", at(bones.subList(0, i), varint()));
            floats(b, "rotation", "x", "y", "scaleX", "scaleY", "shearX", "shearY");
            if(family!=SpineVersion.V43)b.put("length",number());
            b.put("inherit", enumValue("normal", "onlyTranslation", "noRotationOrReflection", "noScale", "noScaleOrReflection"));
            if(family==SpineVersion.V43)b.put("length",number());
            skinRequired(b); if (nonessential) {
                b.put("color", color());
                if(family.atLeast(SpineVersion.V42)){text();if(family==SpineVersion.V43){number();number();}bool();}
            }
        }
        var slotArray = root.putArray("slots");
        for (int i = 0, n = count(); i < n; i++) {
            var s = named(slotArray, slots).put("bone", at(bones, varint())).put("color", color());
            if (in.readInt() != -1) throw unsupported("two-color slots");
            String attachment = ref(); if (attachment != null) s.put("attachment", attachment);
            s.put("blend", enumValue("normal", "additive", "multiply", "screen"));
            if(nonessential&&family.atLeast(SpineVersion.V42))bool();
        }
        if(family==SpineVersion.V43)constraints43();
        else {
            constraints("ik"); constraints("transform"); constraints("path");
            if(family==SpineVersion.V42)physics();
        }
        var skinArray = root.putArray("skins");
        int defaultSlots = count();
        if (defaultSlots > 0) skin(skinArray.addObject().put("name", "default"), "default", defaultSlots);
        for (int i = 0, n = count(); i < n; i++) {
            String name = required(family.atLeast(SpineVersion.V42)?text():ref()); var skin = skinArray.addObject().put("name", name);
            if(nonessential&&family.atLeast(SpineVersion.V42))color();
            readMembers(skin,"bones",bones);
            if(family==SpineVersion.V43) {
                var all=new ArrayList<ObjectNode>();for(String kind:List.of("ik","transform","path","physics"))for(var c:root.path(kind))all.add((ObjectNode)c);
                all.sort(Comparator.comparingInt(c->c.path("order").asInt()));
                for(int j=0,size=count();j<size;j++) {
                    var constraint=at(all,varint());
                    for(String kind:List.of("ik","transform","path","physics"))for(var c:root.path(kind))
                        if(c==constraint)skin.withArray(kind).add(c.path("name").asText());
                }
            } else for(String kind:family==SpineVersion.V42?List.of("ik","transform","path","physics"):List.of("ik","transform","path")) {
                var names=new ArrayList<String>();for(var c:root.path(kind))names.add(c.path("name").asText());
                readMembers(skin,kind,names);
            }
            skin(skin, name, count());
        }
        // The editable model always has a default skin. Preserve binary indices separately.
        if (defaultSlots == 0) skinArray.insert(0, JsonNodeFactory.instance.objectNode().put("name", "default").set("attachments", JsonNodeFactory.instance.objectNode()));
        var definitions = root.putObject("events");
        for (int i = 0, n = count(); i < n; i++) {
            String name = required(family.atLeast(SpineVersion.V42)?text():ref()); events.add(name);
            if (definitions.has(name)) throw unsupported("duplicate event " + name);
            var e = JsonNodeFactory.instance.objectNode().put("int", signed()).put("float", number());
            String string=text();e.put("string",string==null?"":string);
            String audio = text();
            if (audio != null) { e.put("audio", audio); floats(e, "volume", "balance"); }
            definitions.set(name, e); eventData.add(e);
        }
        var animations = root.putObject("animations");
        for (int i = 0, n = count(); i < n; i++) {
            String name = required(text());
            if (animations.has(name)) throw unsupported("duplicate animation " + name);
            animation(animations.putObject(name));
        }
        if (bytes.available() != 0) throw unsupported("trailing bytes");
        return root;
    }

    private void constraints(String kind) throws IOException {
        var array = root.putArray(kind);
        for (int i = 0, n = count(); i < n; i++) {
            var c = array.addObject().put("name", required(text())).put("order", varint());
            if(family!=SpineVersion.V42||kind.equals("path"))skinRequired(c); var bs = c.putArray("bones");
            for (int j = 0, size = count(); j < size; j++) bs.add(at(bones, varint()));
            c.put("target", at(kind.equals("path") ? slots : bones, varint()));
            switch (kind) {
                case "ik" -> {
                    if(family==SpineVersion.V42)ikFlags(c,false);
                    else {floats(c, "mix", "softness"); c.put("bendPositive", in.readByte() == 1);
                        c.put("compress", bool()).put("stretch", bool()).put("uniform", bool());}
                }
                case "transform" -> {
                    if(family==SpineVersion.V42){
                        int flags=in.readUnsignedByte();c.put("skin",(flags&1)!=0);
                        c.put("local",(flags&2)!=0).put("relative",(flags&4)!=0);
                        optionalFloats(c,flags,3,"rotation","x","y","scaleX","scaleY");
                        optionalFloats(c,in.readUnsignedByte(),0,"shearY","mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY");
                        for(String mix:List.of("mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY"))if(!c.has(mix))c.put(mix,0);
                    }else{
                        c.put("local", bool()).put("relative", bool());
                        floats(c, "rotation", "x", "y", "scaleX", "scaleY", "shearY");
                        if(family.legacy())oldMix(c,true);else floats(c,"mixRotate", "mixX", "mixY", "mixScaleX", "mixScaleY", "mixShearY");
                    }
                }
                case "path" -> {
                    if(family==SpineVersion.V42){int flags=in.readUnsignedByte();pathModes(c,flags);if((flags&128)!=0)c.put("rotation",number());}
                    else {c.put("positionMode", enumValue("fixed", "percent"));
                        c.put("spacingMode", enumValue("length", "fixed", "percent", "proportional"));
                        c.put("rotateMode", enumValue("tangent", "chain", "chainScale"));c.put("rotation",number());}
                    floats(c,"position","spacing");
                    if(family.legacy())oldMix(c,false);else floats(c,"mixRotate", "mixX", "mixY");
                }
            }
        }
    }

    private void readMembers(ObjectNode skin,String key,List<String> names)throws IOException {
        var result=skin.putArray(key);for(int i=0,size=count();i<size;i++)result.add(at(names,varint()));
    }
    private void skin(ObjectNode skin, String name, int slotCount) throws IOException {
        skins.add(name); var attachments = skin.putObject("attachments");
        for (int i = 0; i < slotCount; i++) {
            String slot = at(slots, varint());
            if (attachments.has(slot)) throw unsupported("duplicate skin slot");
            var all = attachments.putObject(slot);
            for (int j = 0, n = count(); j < n; j++) {
                String key = required(ref());
                if (all.has(key)) throw unsupported("duplicate attachment");
                all.set(key, attachment(key));
            }
        }
    }
    private ObjectNode attachment(String key) throws IOException {
        if(family.atLeast(SpineVersion.V42))return attachment42(key);
        String name = ref(); if (name == null) name = key;
        int type = in.readUnsignedByte(); var a = JsonNodeFactory.instance.objectNode();
        switch (type) {
            case 0 -> {
                String path = ref(); a.put("type", "region").put("path", path == null ? name : path);
                floats(a, "rotation", "x", "y", "scaleX", "scaleY", "width", "height"); a.put("color", color());
                if(family==SpineVersion.V41&&bool())throw unsupported("attachment sequence");
            }
            case 2 -> {
                String path = ref(); a.put("type", "mesh").put("path", path == null ? name : path).put("color", color());
                int size = count();
                if (size > io.github.samlinb.openbone.core.math.MeshTopology.MAX_VERTICES) throw unsupported("mesh vertex limit");
                floatArray(a.putArray("uvs"), size * 2);
                var triangles = a.putArray("triangles");
                for (int i = 0, n = count(); i < n; i++) triangles.add(in.readUnsignedShort());
                vertices(a, size, true); a.put("hull", count());
                orientTriangles(triangles, size, a.path("hull").asInt());
                if(family==SpineVersion.V41&&bool())throw unsupported("attachment sequence");
                if (nonessential) {
                    var edges = a.putArray("edges");
                    for (int i = 0, n = count(); i < n; i++) edges.add(in.readUnsignedShort());
                    floats(a, "width", "height");
                }
            }
            case 1, 4, 6 -> {
                a.put("type", type == 1 ? "boundingbox" : type == 4 ? "path" : "clipping");
                if (type == 4) a.put("closed", bool()).put("constantSpeed", bool());
                if (type == 6) a.put("end", at(slots, varint()));
                int size = count(); a.put("vertexCount", size); vertices(a, size, type==4);
                if (type == 4) floatArray(a.putArray("lengths"), size / 3);
                if (nonessential) { String c = color(); if (type != 4) a.put("color", c); }
            }
            default -> throw unsupported("attachment type " + type + " (linked mesh/point)");
        }
        return a;
    }

    /** Runtime rasterization permits mixed winding. The editor requires directed
     * faces consistent with hull order. Propagate orientation through shared edges;
     * this changes neither indices/weights nor the rendered triangle surfaces. */
    private void orientTriangles(ArrayNode triangles, int vertices, int hull) throws IOException {
        if (hull < 3 || hull > vertices || triangles.size() % 3 != 0) throw unsupported("mesh topology");
        record Edge(int from, int to, int face) {}
        var edges = new HashMap<Long, List<Edge>>();
        for (int i = 0; i < triangles.size(); i += 3) for (int j = 0; j < 3; j++) {
            int from = triangles.get(i + j).intValue(), to = triangles.get(i + (j + 1) % 3).intValue();
            if (from == to || from >= vertices || to >= vertices) throw unsupported("mesh index");
            long key = ((long)Math.min(from, to) << 32) | Math.max(from, to);
            var row = edges.computeIfAbsent(key, k -> new ArrayList<>()); row.add(new Edge(from, to, i / 3));
            if (row.size() > 2) throw unsupported("non-manifold mesh");
        }
        int[] orientation = new int[triangles.size() / 3]; var pending = new ArrayDeque<Integer>();
        for (int i = 0; i < hull; i++) {
            int next = (i + 1) % hull;
            var border = edges.get(((long)Math.min(i, next) << 32) | Math.max(i, next));
            if (border == null || border.size() != 1) throw unsupported("mesh hull");
            var edge = border.get(0); int sign = edge.from() == i ? 1 : -1;
            if (orientation[edge.face()] != 0 && orientation[edge.face()] != sign) throw unsupported("mesh orientation");
            orientation[edge.face()] = sign; pending.add(edge.face());
        }
        while (!pending.isEmpty()) {
            int face = pending.removeFirst();
            for (int j = 0; j < 3; j++) {
                int from = triangles.get(face * 3 + j).intValue(), to = triangles.get(face * 3 + (j + 1) % 3).intValue();
                var row = edges.get(((long)Math.min(from, to) << 32) | Math.max(from, to));
                for (var edge : row) if (edge.face() != face) {
                    int sign = orientation[face] * (edge.from() == from ? -1 : 1);
                    if (orientation[edge.face()] == 0) { orientation[edge.face()] = sign; pending.add(edge.face()); }
                    else if (orientation[edge.face()] != sign) throw unsupported("mesh orientation");
                }
            }
        }
        for (int i = 0; i < orientation.length; i++) {
            if (orientation[i] == 0) throw unsupported("disconnected mesh");
            if (orientation[i] < 0) {
                var second = triangles.get(i * 3 + 1); triangles.set(i * 3 + 1, triangles.get(i * 3 + 2)); triangles.set(i * 3 + 2, second);
            }
        }
    }
    private void floatArray(ArrayNode array, int n) throws IOException { for (int i = 0; i < n; i++) array.add(number()); }
    private void vertices(ObjectNode a, int size, boolean allowWeights) throws IOException {
        var v = a.putArray("vertices"); boolean weighted = bool();
        if (!weighted) { floatArray(v, size * 2); return; }
        if (!allowWeights) throw unsupported("weighted " + a.path("type").asText());
        for (int i = 0; i < size; i++) {
            int n = count(); v.add(n);
            for (int j = 0; j < n; j++) { int b = varint(); at(bones, b); v.add(b); floatArray(v, 3); }
        }
    }

    private ObjectNode child(ObjectNode parent, String key) {
        if (!parent.has(key)) return parent.putObject(key);
        return (ObjectNode) parent.get(key);
    }
    private void animation(ObjectNode a) throws IOException {
        if(!family.legacy())count(); // Declared timeline capacity, not a byte offset.
        for (int i = 0, n = count(); i < n; i++) {
            var slot = child(child(a, "slots"), at(slots, varint()));
            for (int j = 0, size = count(); j < size; j++) {
                int type = in.readUnsignedByte(), frames = count();
                if (type == 0) {
                    var keys = slot.putArray("attachment");
                    for (int f = 0; f < frames; f++) keys.addObject().put("time", number()).put("name", ref());
                } else {
                    if(family.legacy()&&type!=1)throw unsupported("two-color timeline");
                    String field = switch (type) { case 1 -> "rgba"; case 2 -> "rgb"; case 5 -> "alpha"; default -> throw unsupported("two-color timeline"); };
                    int dims = type == 1 ? 4 : type == 2 ? 3 : 1;
                    if(!family.legacy())count(); track(slot.putArray(field), frames, type == 5 ? new String[]{"value"} : new String[]{"color"}, dims, true);
                }
            }
        }
        String[] types = {"rotate", "translate", "translatex", "translatey", "scale", "scalex", "scaley", "shear", "shearx", "sheary"};
        for (int i = 0, n = count(); i < n; i++) {
            var bone = child(child(a, "bones"), at(bones, varint()));
            for (int j = 0, size = count(); j < size; j++) {
                int type = in.readUnsignedByte(), frames = count(); if(!family.legacy())count();
                if(family.legacy()){if(type>3)throw unsupported("bone timeline "+type);type=new int[]{0,1,4,7}[type];}
                String name = at(Arrays.asList(types), type);
                String[] fields = type == 1 || type == 4 || type == 7 ? new String[]{"x", "y"} : new String[]{"value"};
                if (bone.has(name)) throw unsupported("duplicate bone timeline");
                track(bone.putArray(name), frames, fields, fields.length, false);
            }
        }
        constraintTimelines(a);
        if(family==SpineVersion.V43&&count()!=0)throw unsupported("slider timelines");
        for (int i = 0, n = count(); i < n; i++) {
            var skin = child(child(a, "attachments"), at(skins, varint()));
            for (int j = 0, size = count(); j < size; j++) {
                var slot = child(skin, at(slots, varint()));
                for (int k = 0, size2 = count(); k < size2; k++) {
                    var keys = child(slot, required(ref())).putArray("deform");
                    if(family.atLeast(SpineVersion.V41)&&in.readUnsignedByte()!=0)throw unsupported("attachment sequence timeline");
                    int frames = count(); if(!family.legacy())count(); double time = number();
                    for (int f = 0; f < frames; f++) {
                        var key = keys.addObject().put("time", time); int values = count();
                        if (values > 0) { key.put("offset", count()); floatArray(key.putArray("vertices"), values); }
                        if (f + 1 < frames) {if(family.legacy()){curve(key,1);time=number();}else{time = number(); curve(key, 1);} }
                    }
                    if(family.legacy())absoluteCurves(keys,new String[]{"progress"},1,false);
                }
            }
        }
        int orders = count();
        if (orders > 0) {
            var keys = a.putArray("drawOrder");
            for (int i = 0; i < orders; i++) {
                var key = keys.addObject().put("time", number()); var offsets = key.putArray("offsets");
                for (int j = 0, n = count(); j < n; j++) offsets.addObject().put("slot", at(slots, varint())).put("offset", varint());
            }
        }
        if(family==SpineVersion.V43&&count()!=0)throw unsupported("draw-order folder timelines");
        int eventCount = count();
        if (eventCount > 0) {
            var keys = a.putArray("events");
            for (int i = 0; i < eventCount; i++) {
                var key = keys.addObject().put("time", number()); int index = varint();
                var def = at(eventData, index);
                key.put("name", at(events, index)).put("int", signed()).put("float", number());
                String string=family.atLeast(SpineVersion.V42)?text():bool()?text():null;
                key.put("string",string==null?def.path("string").asText(""):string);
                if (def.has("audio")) floats(key, "volume", "balance");
            }
        }
        if(nonessential&&family==SpineVersion.V43)color();
    }
    private ObjectNode frame(String[] fields, int dimensions, boolean packed) throws IOException {
        var key = JsonNodeFactory.instance.objectNode().put("time", number());
        if (packed && dimensions > 1) {
            StringBuilder color = new StringBuilder();
            for (int i = 0; i < dimensions; i++) color.append(String.format(Locale.ROOT, "%02x", in.readUnsignedByte()));
            key.put("color", color.toString());
        } else for (String field : fields) key.put(field, packed ? in.readUnsignedByte() / 255.0 : number());
        return key;
    }
    private void track(ArrayNode keys, int frames, String[] fields, int dimensions, boolean packed) throws IOException {
        if (frames == 0) throw unsupported("empty timeline");
        if(family.legacy()) {
            for(int f=0;f<frames;f++){var key=frame(fields,dimensions,packed);keys.add(key);if(f+1<frames)curve(key,1);}
            absoluteCurves(keys,fields,dimensions,packed);return;
        }
        ObjectNode previous = frame(fields, dimensions, packed); keys.add(previous);
        for (int f = 1; f < frames; f++) {
            ObjectNode next = frame(fields, dimensions, packed); curve(previous, dimensions); keys.add(next); previous = next;
        }
    }
    private void curve(ObjectNode key, int dimensions) throws IOException {
        int type = in.readUnsignedByte();
        if (type == 1) key.put("curve", "stepped");
        else if (type == 2) floatArray(key.putArray("curve"), dimensions * 4);
        else if (type != 0) throw unsupported("curve type " + type);
    }

    private void absoluteCurves(ArrayNode keys,String[] fields,int dimensions,boolean packed) {
        for(int i=0;i+1<keys.size();i++) {
            var key=(ObjectNode)keys.get(i);var next=keys.get(i+1);var relative=key.get("curve");
            if(relative==null||!relative.isArray())continue;
            var curve=key.putArray("curve");double time=key.path("time").asDouble(),dt=next.path("time").asDouble()-time;
            for(int c=0;c<dimensions;c++) {
                double from,to;
                if(packed&&dimensions>1){from=Integer.parseInt(key.path("color").asText().substring(c*2,c*2+2),16)/255.;to=Integer.parseInt(next.path("color").asText().substring(c*2,c*2+2),16)/255.;}
                else {from=key.path(fields[Math.min(c,fields.length-1)]).asDouble();to=next.path(fields[Math.min(c,fields.length-1)]).asDouble(fields.length==1&&fields[0].equals("progress")?1:0);}
                curve.add(time+relative.get(0).asDouble()*dt).add(from+relative.get(1).asDouble()*(to-from))
                    .add(time+relative.get(2).asDouble()*dt).add(from+relative.get(3).asDouble()*(to-from));
            }
        }
    }

    private void optionalFloats(ObjectNode node,int flags,int start,String... names)throws IOException {
        for(int i=0;i<names.length;i++)if((flags&(1<<(start+i)))!=0)node.put(names[i],number());
    }
    private String constraintName(String kind,int index)throws IOException {
        if(family==SpineVersion.V43) {
            for(String type:List.of("ik","transform","path","physics"))for(var c:root.path(type))if(c.path("order").asInt()==index){if(!type.equals(kind))throw unsupported("constraint type/index");return c.path("name").asText();}
            throw unsupported("constraint index "+index);
        }
        var items=root.path(kind);if(index<0||index>=items.size())throw unsupported("constraint index "+index);return items.get(index).path("name").asText();
    }
    private void constraintTimelines(ObjectNode animation)throws IOException {
        for(int i=0,n=count();i<n;i++) {
            String name=constraintName("ik",varint());int frames=count();if(frames==0)throw unsupported("empty IK timeline");if(!family.legacy())count();
            var keys=child(animation,"ik").putArray(name);
            for(int f=0;f<frames;f++) {
                ObjectNode key;
                if(family.atLeast(SpineVersion.V42)) {
                    int flags=in.readUnsignedByte();key=keys.addObject().put("time",number()).put("mix",(flags&1)!=0?((flags&2)!=0?number():1):0).put("softness",(flags&4)!=0?number():0)
                        .put("bendPositive",(flags&8)!=0).put("compress",(flags&16)!=0).put("stretch",(flags&32)!=0);
                    if(f>0){var previous=(ObjectNode)keys.get(f-1);if((flags&64)!=0)previous.put("curve","stepped");else if((flags&128)!=0)floatArray(previous.putArray("curve"),8);}
                } else {
                    key=frame(new String[]{"mix","softness"},2,false);keys.add(key);
                    if(!family.legacy()&&f>0)curve((ObjectNode)keys.get(f-1),2);
                    key.put("bendPositive",in.readByte()==1).put("compress",bool()).put("stretch",bool());
                    if(family.legacy()&&f+1<frames)curve(key,1);
                }
            }
            if(family.legacy())absoluteCurves(keys,new String[]{"mix","softness"},2,false);
        }
        for(int i=0,n=count();i<n;i++) {
            String name=constraintName("transform",varint());int frames=count();if(!family.legacy())count();
            var keys=child(animation,"transform").putArray(name);
            String[] fields=family.legacy()?new String[]{"mixRotate","mixX","mixScaleX","mixShearY"}:new String[]{"mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY"};
            track(keys,frames,fields,fields.length,false);if(family.legacy())expandMix(keys,true);
        }
        for(int i=0,n=count();i<n;i++) {
            var groups=child(child(animation,"path"),constraintName("path",varint()));
            for(int j=0,size=count();j<size;j++) {
                int type=in.readUnsignedByte(),frames=count();if(!family.legacy())count();
                String field=at(List.of("position","spacing","mix"),type);
                String[] fields=type==2?(family.legacy()?new String[]{"mixRotate","mixX"}:new String[]{"mixRotate","mixX","mixY"}):new String[]{"value"};
                var keys=groups.putArray(field);track(keys,frames,fields,fields.length,false);if(family.legacy()&&type==2)expandMix(keys,false);
            }
        }
        if(family.atLeast(SpineVersion.V42))for(int i=0,n=count();i<n;i++) {
            int index=varint()-1;if(index<0)throw unsupported("global physics timelines");
            var groups=child(child(animation,"physics"),constraintName("physics",index));
            for(int j=0,size=count();j<size;j++) {
                int type=in.readUnsignedByte(),frames=count();String name=at(List.of("inertia","strength","damping","invalid","mass","wind","gravity","mix","reset"),type);
                if(type==3||type==8)throw unsupported("physics reset/channel");count();track(groups.putArray(name),frames,new String[]{"value"},1,false);
            }
        }
    }
    private void expandMix(ArrayNode keys,boolean transform) {
        for(var key:keys){var n=(ObjectNode)key;n.set("mixY",n.path("mixX"));if(transform)n.set("mixScaleY",n.path("mixScaleX"));
            if(n.path("curve").isArray()) {
                var old=n.get("curve");var curve=n.putArray("curve");int[] map=transform?new int[]{0,1,1,2,2,3}:new int[]{0,1,1};
                for(int channel:map)for(int c=0;c<4;c++)curve.add(old.get(channel*4+c));
            }
        }
    }
    private void oldMix(ObjectNode node,boolean transform)throws IOException {
        node.put("mixRotate",number());double translate=number();node.put("mixX",translate).put("mixY",translate);
        if(transform){double scale=number();node.put("mixScaleX",scale).put("mixScaleY",scale).put("mixShearY",number());}
    }
    private void pathModes(ObjectNode node,int flags)throws IOException {
        node.put("positionMode",at(List.of("fixed","percent"),flags&1));
        node.put("spacingMode",at(List.of("length","fixed","percent","proportional"),(flags>>1)&3));
        node.put("rotateMode",at(List.of("tangent","chain","chainScale"),(flags>>3)&3));
    }
    private void ikFlags(ObjectNode node,boolean v43)throws IOException {
        int flags=in.readUnsignedByte();node.put("skin",(flags&1)!=0);
        if(v43&&(flags&2)!=0)throw unsupported("4.3 IK scaleY mode");
        node.put("bendPositive",v43?(flags&4)==0:(flags&2)!=0).put("compress",(flags&(v43?8:4))!=0).put("stretch",(flags&(v43?16:8))!=0);
        if(!v43)node.put("uniform",(flags&16)!=0);
        node.put("mix",(flags&32)!=0?((flags&64)!=0?number():1):0);
        node.put("softness",(flags&128)!=0?number():0);
    }
    private void physics()throws IOException {
        var array=root.putArray("physics");for(int i=0,n=count();i<n;i++) {
            var c=array.addObject().put("name",required(text())).put("order",varint()).put("bone",at(bones,varint()));physicsFields(c);
        }
    }
    private void physicsFields(ObjectNode c)throws IOException {
        int flags=in.readUnsignedByte();c.put("skin",(flags&1)!=0);
        optionalFloats(c,flags,1,"x","y","rotate","scaleX","shearX");
        if(family==SpineVersion.V43&&c.path("scaleX").asDouble()<0)throw unsupported("4.3 physics scaleY mode");
        c.put("limit",(flags&64)!=0?number():5000).put("fps",in.readUnsignedByte());
        floats(c,"inertia","strength","damping");c.put("mass",(flags&128)!=0?1/number():1);floats(c,"wind","gravity");
        int globals=in.readUnsignedByte();if((globals&127)!=0)throw unsupported("global physics parameters");
        c.put("mix",(globals&128)!=0?number():1);
    }
    private void constraints43()throws IOException {
        for(int i=0,n=count();i<n;i++) {
            String name=required(text());int type=in.readUnsignedByte();
            String kind=switch(type){case 0->"ik";case 1->"path";case 2->"transform";case 3->"physics";default->throw unsupported("4.3 slider constraint");};
            var c=root.withArray(kind).addObject().put("name",name).put("order",i);
            if(type==3){c.put("bone",at(bones,varint()));physicsFields(c);continue;}
            var ids=c.putArray("bones");for(int j=0,size=count();j<size;j++)ids.add(at(bones,varint()));
            c.put("target",at(type==1?slots:bones,varint()));
            if(type==0)ikFlags(c,true);
            else if(type==1){int flags=in.readUnsignedByte();c.put("skin",(flags&1)!=0);pathModes(c,flags>>1);if((flags&128)!=0)c.put("rotation",number());floats(c,"position","spacing","mixRotate","mixX","mixY");}
            else {
                int flags=in.readUnsignedByte();c.put("skin",(flags&1)!=0);if((flags&16)!=0||((flags&2)!=0)!=((flags&4)!=0))throw unsupported("4.3 skin/clamp/mixed local transform");
                c.put("local",(flags&2)!=0).put("relative",(flags&8)!=0);boolean[] active=new boolean[6];
                for(int j=0,size=flags>>5;j<size;j++) {
                    int from=in.readUnsignedByte();double offset=number();int count=in.readUnsignedByte();
                    if(from>=6||offset!=0||count!=1)throw unsupported("4.3 remapped transform property");
                    int to=in.readUnsignedByte();double toOffset=number(),max=number(),scale=number();
                    // max only participates when clamp is enabled (rejected above).
                    if(to!=from||toOffset!=0||scale!=1||active[from])throw unsupported("4.3 cross-property transform");active[from]=true;
                }
                optionalFloats(c,in.readUnsignedByte(),0,"rotation","x","y","scaleX","scaleY","shearY");
                String[] mixes={"mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY"};int mixFlags=in.readUnsignedByte();
                for(int j=0;j<6;j++){double value=(mixFlags&(1<<j))!=0?number():1;c.put(mixes[j],active[j]?value:0);}
            }
        }
    }
    private ObjectNode attachment42(String key)throws IOException {
        int flags=in.readUnsignedByte(),type=flags&7;String name=(flags&8)!=0?required(ref()):key;
        var a=JsonNodeFactory.instance.objectNode();
        if(type==0||type==2) {
            String path=(flags&16)!=0?ref():name;a.put("path",path==null?name:path).put("color",(flags&32)!=0?color():"ffffffff");
            if((flags&64)!=0)throw unsupported("attachment sequence");
        }
        switch(type) {
            case 0->{a.put("type","region").put("rotation",(flags&128)!=0?number():0);floats(a,"x","y","scaleX","scaleY","width","height");}
            case 2->{
                a.put("type","mesh").put("hull",count());boolean weighted=(flags&128)!=0;int size=count();
                verticesKnown(a,size,true,weighted);floatArray(a.putArray("uvs"),size*2);
                int faces=(size*2-a.path("hull").asInt()-2)*3;
                if(faces<0||faces>budget)throw unsupported("triangle count");budget-=faces;
                var triangles=a.putArray("triangles");for(int i=0;i<faces;i++)triangles.add(shortIndex());
                orientTriangles(triangles,size,a.path("hull").asInt());
                if(family==SpineVersion.V43 && count()!=0)throw unsupported("cross-slot mesh timelines");
                if(nonessential){var edges=a.putArray("edges");for(int i=0,n=count();i<n;i++)edges.add(shortIndex());floats(a,"width","height");}
            }
            case 1,4,6->{
                a.put("type",type==1?"boundingbox":type==4?"path":"clipping");
                if(type==4)a.put("closed",(flags&16)!=0).put("constantSpeed",(flags&32)!=0);
                if(type==6)a.put("end",at(slots,varint()));
                int size=count();a.put("vertexCount",size);verticesKnown(a,size,type==4,(flags&(type==4?64:16))!=0);
                if(type==4)floatArray(a.putArray("lengths"),size/3);
                if(nonessential){String color=color();if(type!=4)a.put("color",color);}
            }
            default->throw unsupported("attachment type "+type+" (linked mesh/point)");
        }
        return a;
    }
    private int shortIndex() throws IOException {
        int value=varint();if(value<0 || value>65535)throw unsupported("mesh uint16 index");return value;
    }
    private void verticesKnown(ObjectNode a,int size,boolean allowWeights,boolean weighted)throws IOException {
        if(size<0||size>io.github.samlinb.openbone.core.math.MeshTopology.MAX_VERTICES)throw unsupported("vertex count");
        var values=a.putArray("vertices");
        if(!weighted){floatArray(values,size*2);return;}
        if(!allowWeights)throw unsupported("weighted "+a.path("type").asText());
        int expected=family==SpineVersion.V43?count():-1,entries=0;
        for(int i=0;i<size;i++){int n=count();entries+=1+n;values.add(n);for(int j=0;j<n;j++){int b=varint();at(bones,b);values.add(b);floatArray(values,3);}}
        if(expected>=0 && entries!=expected)throw unsupported("weighted bone array length");
    }
}
