package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** MCP's domain boundary. All editor state access, including reads, runs on EDT. */
public final class EditorMcpTools {
    public interface Host {
        CommandHistory history();
        Map<String, Object> context();
        void prepare();
        void changed();
        CompletableFuture<Preview> capture(EntityId animation, double time, String revision);
        default CompletableFuture<Preview> capture(EntityId animation, double time, String revision, CaptureOptions options) { return capture(animation,time,revision); }
        default ProjectDocument project() { throw new UnsupportedOperationException("Project IO is unavailable for this host"); }
        default void installProject(ProjectDocument document) { throw new UnsupportedOperationException("Project loading is unavailable for this host"); }
        default void control(ViewControl control) { throw new UnsupportedOperationException("View control is unavailable for this host"); }
    }
    public record CaptureOptions(boolean fit, Boolean showBones, Boolean showGrid) {}
    public record ViewControl(String action, EntityId animation, Double time, Boolean loop, Boolean showBones, Boolean showGrid) {}
    public record Preview(BufferedImage image, Map<String, Object> metadata) {}
    private record Receipt(JsonNode arguments, ObjectNode result) {}
    private static final ObjectMapper JSON = mapper();
    private static ObjectMapper mapper() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(EntityId.class, new JsonSerializer<EntityId>() {
            @Override public void serialize(EntityId id, com.fasterxml.jackson.core.JsonGenerator out, SerializerProvider provider) throws java.io.IOException { out.writeString(id.value()); }
        });
        return new ObjectMapper().registerModule(module);
    }
    private final Host host;
    // Bounded, process-local retry receipts. Reusing an ID with different arguments is rejected.
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();

    public EditorMcpTools(Host host) { this.host = Objects.requireNonNull(host); }

    public ArrayNode definitions() {
        ArrayNode tools = JSON.createArrayNode();
        add(tools, "openbone_get_context", "Read the current live project summary, selection, view and revision. Query scene for IDs and keyframes.", schema("{}"), true);
        add(tools, "openbone_query_scene", "Read a page of bones, animations, keyframes, slots, attachments or constraints. IDs are UUID strings. Use revision to keep pages consistent.", schema("""
            {"kind":{"enum":["bones","animations","keyframes","slots","attachments","constraints"]},
             "animationId":{"type":"string"},"boneId":{"type":"string"},"expectedRevision":{"type":"string"},
             "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":200}}
            """, "kind"), true);
        add(tools, "openbone_apply_edits", "Build and edit the live scene atomically in one undo step. Supports bones/reparent, slots/order, local PNG regions, animations and keyframes. Setup transforms are absolute LOCAL values; keyframe translation/rotation/shear are offsets from Setup (degrees); scale is a multiplier. UUIDs are caller-created. PNGs are validated before publication, linked by absolute path, not copied. curve may be linear, stepped or {kind:bezier,x1,y1,x2,y2} fractions of a segment. Retry with the SAME requestId and arguments.", editSchema(), false);
        add(tools, "openbone_capture_preview", "Seek exact seconds, pause and capture the real GL viewport as PNG, max 1024 pixels per side. Optional fit frames visible geometry; showBones/showGrid can hide editing overlays. Options persist in the editor. Requires a visible window. Capture multiple times to inspect motion; no undo history.", schema("""
            {"animationId":{"type":"string"},"time":{"type":"number","minimum":0,"maximum":600},"expectedRevision":{"type":"string"},"fit":{"type":"boolean"},"showBones":{"type":"boolean"},"showGrid":{"type":"boolean"}}
            """, "animationId", "time", "expectedRevision"), false);
        for (String action : List.of("undo", "redo"))
            add(tools, "openbone_" + action, "Perform one " + action + " in the current editor history (including manual edits). Read context first; retry with the same requestId and arguments.", schema("""
                {"expectedRevision":{"type":"string"},"requestId":{"type":"string","minLength":1,"maxLength":128}}
                """, "expectedRevision", "requestId"), false);
        add(tools,"openbone_control_view","Control live playback and camera without editing model/history. action seek requires time; play/seek use animationId or the current animation. frame fits current visible geometry (bones if empty). Configure overlay flags and looping. Uses revision and retry receipt like editing.",schema("""
            {"action":{"enum":["play","pause","seek","frame","configure"]},"animationId":{"type":"string"},"time":{"type":"number","minimum":0,"maximum":600},"loop":{"type":"boolean"},"showBones":{"type":"boolean"},"showGrid":{"type":"boolean"},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","action","expectedRevision","requestId"),false);
        add(tools,"openbone_save_project","Save a captured current project to an absolute .json path, portable by default (copies PNGs via the existing save service). Existing target requires overwrite:true. Preserves live undo history. If manual edits occur during IO, savedRevision identifies the disk snapshot and adopted:false leaves current state dirty. Retry with identical requestId and arguments.",schema("""
            {"path":{"type":"string"},"portable":{"type":"boolean"},"overwrite":{"type":"boolean"},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","path","expectedRevision","requestId"),false);
        add(tools,"openbone_load_project","Load an absolute OpenBone .json project through the existing project service. dirtyPolicy must explicitly be reject or discard; discard authorizes replacing unsaved edits. Validates before replacing current editor. Resets history; failed load preserves current project. Retry with identical requestId and arguments.",schema("""
            {"path":{"type":"string"},"dirtyPolicy":{"enum":["reject","discard"]},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","path","dirtyPolicy","expectedRevision","requestId"),false);
        return tools;
    }

    private static ObjectNode editSchema() {
        ObjectNode s = schema("""
            {"expectedRevision":{"type":"string"},"requestId":{"type":"string","minLength":1,"maxLength":128},
             "label":{"type":"string","minLength":1,"maxLength":128},
             "edits":{"type":"array","minItems":1,"maxItems":500,"items":{"oneOf":[
               {"type":"object","additionalProperties":false,"properties":{"op":{"const":"create_animation"},"animationId":{"type":"string"},"name":{"type":"string","minLength":1,"maxLength":256}},"required":["op","animationId","name"]},
               {"type":"object","additionalProperties":false,"properties":{"op":{"const":"put_keyframe"},"animationId":{"type":"string"},"boneId":{"type":"string"},"channel":{"enum":["TRANSLATE","ROTATE","SCALE","SHEAR"]},"time":{"type":"number","minimum":0,"maximum":600},"values":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"number","minimum":-1000000000,"maximum":1000000000}},"curve":{"enum":["linear","stepped"]}},"required":["op","animationId","boneId","channel","time","values"]},
               {"type":"object","additionalProperties":false,"properties":{"op":{"const":"remove_keyframes"},"animationId":{"type":"string"},"keyIds":{"type":"array","minItems":1,"maxItems":500,"items":{"type":"string"}}},"required":["op","animationId","keyIds"]}
             ]}}}
            """, "expectedRevision", "requestId", "edits");
        var variants=(ArrayNode)s.path("properties").path("edits").path("items").path("oneOf");
        variants.addAll(SceneMcpEdits.schemas(JSON));
        try {
            ((ObjectNode)variants.get(1).path("properties")).set("curve",JSON.readTree("""
                {"oneOf":[{"enum":["linear","stepped"]},{"type":"object","additionalProperties":false,"properties":{"kind":{"const":"bezier"},"x1":{"type":"number","minimum":0,"maximum":1},"y1":{"type":"number"},"x2":{"type":"number","minimum":0,"maximum":1},"y2":{"type":"number"}},"required":["kind","x1","y1","x2","y2"]}]}
                """));
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
        return s;
    }

    private static ObjectNode schema(String properties, String... required) {
        try {
            var s = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
            s.set("properties", JSON.readTree(properties));
            s.set("required", JSON.valueToTree(required));
            return s;
        } catch (Exception impossible) { throw new ExceptionInInitializerError(impossible); }
    }

    private static void add(ArrayNode list, String name, String description, ObjectNode input, boolean readOnly) {
        var tool = list.addObject().put("name", name).put("description", description);
        tool.set("inputSchema", input);
        tool.set("outputSchema", schema("{\"data\":{\"type\":\"object\"}}", "data"));
        tool.putObject("annotations").put("readOnlyHint", readOnly).put("destructiveHint", !readOnly)
                .put("idempotentHint", true).put("openWorldHint", false);
    }

    /** Serialized by the transport; never invoke on EDT (preview awaits the next GL frame). */
    public synchronized ObjectNode call(String name, JsonNode args) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("MCP calls must run off EDT");
        try {
            ObjectNode result;
            switch (name) {
                case "openbone_get_context" -> {
                    fields(args);
                    result = success(onEdt(this::context));
                }
                case "openbone_query_scene" -> result = success(onEdt(() -> query(args)));
                case "openbone_capture_preview" -> result = capture(args);
                case "openbone_apply_edits", "openbone_undo", "openbone_redo" -> result = mutate(name, args);
                case "openbone_control_view", "openbone_save_project", "openbone_load_project" -> result = serviceOperation(name,args);
                default -> throw new IllegalArgumentException("Unknown tool: " + name + ". Call tools/list.");
            }
            return result;
        } catch (Exception failed) {
            Throwable cause = failed;
            while (cause.getCause() != null && (cause instanceof ExecutionException || cause instanceof CompletionException)) cause = cause.getCause();
            var error = JSON.createObjectNode().put("isError", true);
            error.putArray("content").addObject().put("type", "text").put("text",
                    cause instanceof TimeoutException ? "Editor or preview timed out. Read context before retrying; keep the same requestId for a mutation retry." :
                    Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()));
            return error;
        }
    }

    private Map<String, Object> context() {
        var h = host.history();
        var d = h.state().model();
        var data = new LinkedHashMap<String, Object>();
        data.put("revision", h.revision());
        data.put("version",io.github.samlinb.openbone.EditorApplication.version());
        data.put("mcpApiVersion",2);
        data.put("capabilities",Map.of("sceneBuilding",true,"bezier",true,"projectIO",true,"playback",true,"cleanPreview",true,"atomicEdits",true));
        data.put("dirty", h.isDirty());
        data.put("busy", h.isPreviewing());
        data.put("selection", h.state().selection().ids().stream().map(EntityId::value).toList());
        data.put("selectedKeyIds", h.state().keySelection().stream().map(EntityId::value).toList());
        data.put("counts", Map.of("bones", d.bones().size(), "animations", d.animations().size(), "slots", d.slots().size(), "attachments", d.attachments().size(), "constraints", d.constraints().size()));
        data.put("undoCount", h.undoCount()); data.put("redoCount", h.redoCount());
        data.put("undoLabel", h.undoLabel().orElse(""));
        data.put("view", host.context());
        data.put("coordinates", "Local space; x right, y up; seconds and degrees. TRANSLATE/ROTATE/SHEAR offsets from Setup; SCALE factors, 1 = Setup.");
        return data;
    }

    private Map<String, Object> query(JsonNode a) {
        fields(a, "kind", "animationId", "boneId", "expectedRevision", "offset", "limit");
        if (a.has("expectedRevision")) checkRevision(text(a, "expectedRevision", 128));
        var d = host.history().state().model();
        int offset = integer(a, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(a, "limit", 50, 1, 200);
        java.util.stream.Stream<JsonNode> rows;
        long total;
        switch (text(a, "kind", 32)) {
            case "bones" -> {
                total = d.bones().size();
                rows = d.bones().stream().map(b -> {
                var row = JSON.createObjectNode().put("id", b.id().value()).put("name", b.name()).put("length", b.length()).put("inherit", b.inherit().name());
                if (b.parentId() == null) row.putNull("parentId"); else row.put("parentId", b.parentId().value());
                row.set("setup", JSON.valueToTree(b.local())); return row;
                });
            }
            case "animations" -> {
                total = d.animations().size();
                rows = d.animations().stream().map(c -> JSON.valueToTree(Map.of("id", c.id().value(), "name", c.name(), "duration", c.duration(), "keyCount", c.keys().size())));
            }
            case "keyframes" -> {
                EntityId clip = id(a, "animationId");
                EntityId bone = a.has("boneId") ? id(a, "boneId") : null;
                if (bone != null) d.bone(bone);
                var tracks = d.animation(clip).timelines();
                total = tracks.stream().filter(t -> bone == null || bone.equals(t.boneId())).mapToLong(t -> t.keys().size()).sum();
                rows = tracks.stream().filter(t -> bone == null || bone.equals(t.boneId())).flatMap(t -> t.keys().stream()
                        .map(k -> JSON.<JsonNode>valueToTree(Map.of("id", k.id().value(), "boneId", t.boneId().value(), "channel", t.channel().name(), "time", k.time(), "values", k.values(), "curves", k.curves()))));
            }
            case "slots" -> { total = d.slots().size(); rows = d.slots().stream().map(JSON::valueToTree); }
            case "attachments" -> {
                total = d.attachments().size();
                rows = d.attachments().stream().map(t -> JSON.valueToTree(t instanceof RegionAttachment r ? Map.of("id",r.id().value(),"name",r.name(),"slotId",r.slotId().value(),"type","RegionAttachment","source",r.source(),"setup",r.local(),"width",r.width(),"height",r.height()) : Map.of("id",t.id().value(),"name",t.name(),"slotId",t.slotId().value(),"type",t.getClass().getSimpleName())));
            }
            case "constraints" -> { total = d.constraints().size(); rows = d.constraints().stream().map(JSON::valueToTree); }
            default -> throw new IllegalArgumentException("Unknown kind. Use bones, animations, keyframes, slots, attachments or constraints.");
        }
        var items = rows.skip(offset).limit(limit).toList();
        long next = Math.min((long) offset + items.size(), total);
        return Map.of("revision", host.history().revision(), "items", items, "total", total, "offset", offset, "hasMore", next < total, "nextOffset", next);
    }

    private ObjectNode mutate(String name, JsonNode a) throws Exception {
        fields(a, name.equals("openbone_apply_edits") ? new String[]{"expectedRevision", "requestId", "label", "edits"} : new String[]{"expectedRevision", "requestId"});
        String request = text(a, "requestId", 128), revision = text(a, "expectedRevision", 128);
        String receiptId = name + ":" + request;
        var receipt = receipts.get(receiptId);
        if (receipt != null) {
            if (!receipt.arguments().equals(a)) throw new IllegalArgumentException("requestId was already used with different arguments. Use a new requestId.");
            return receipt.result().deepCopy();
        }
        EditCommand command = null;
        String label = "AI keyframe edit";
        if (name.equals("openbone_apply_edits")) {
            if (a.has("label")) label = text(a, "label", 128);
            JsonNode edits = a.get("edits");
            if (edits == null || !edits.isArray() || edits.isEmpty() || edits.size() > 500)
                throw new IllegalArgumentException("edits must contain 1 to 500 operations.");
            DocumentSnapshot next = onEdt(() -> {
                host.prepare(); checkRevision(revision); host.history().requireIdle();
                return host.history().state().model();
            });
            // Expensive pure validation uses an immutable copy off EDT. Recheck at publication.
            for (JsonNode edit : edits) next = edit(next, edit);
            DocumentSnapshot candidate = next;
            command = Commands.model(ignored -> candidate);
        }
        EditCommand compiled = command;
        String operationLabel = label;
        ObjectNode result = onEdt(() -> {
            host.prepare(); checkRevision(revision);
            var h = host.history(); h.requireIdle();
            if (compiled != null) h.execute(operationLabel, compiled);
            else if (name.equals("openbone_undo")) h.undo(); else h.redo();
            host.changed();
            return success(context());
        });
        receipts.put(receiptId, new Receipt(a.deepCopy(), result.deepCopy()));
        if (receipts.size() > 128) receipts.remove(receipts.keySet().iterator().next());
        return result;
    }

    private static DocumentSnapshot edit(DocumentSnapshot d, JsonNode e) throws Exception {
        String op = text(e, "op", 32);
        return switch (op) {
            case "create_animation" -> {
                fields(e, "op", "animationId", "name");
                yield ExtendedEdits.createAnimation(d, id(e, "animationId"), text(e, "name", 256));
            }
            case "put_keyframe" -> {
                fields(e, "op", "animationId", "boneId", "channel", "time", "values", "curve");
                var clip = id(e, "animationId"); var bone = id(e, "boneId");
                var channel = Channel.valueOf(text(e, "channel", 32));
                JsonNode values = e.get("values");
                if (values == null || !values.isArray() || values.size() != channel.components())
                    throw new IllegalArgumentException(channel.name() + " requires " + channel.components() + " values.");
                List<Double> v = new ArrayList<>(); for (JsonNode value : values) v.add(number(value, "values", -1e9, 1e9));
                double time = number(e.get("time"), "time", 0, 600);
                DocumentSnapshot next = AnimationEdits.put(d, clip, bone, channel, time, v);
                if (e.has("curve")) {
                    Curve curve;
                    if(e.get("curve").isObject()) {
                        var c=e.get("curve");fields(c,"kind","x1","y1","x2","y2");
                        if(!text(c,"kind",16).equals("bezier"))throw new IllegalArgumentException("Curve kind must be bezier");
                        curve=new Curve(Curve.Kind.BEZIER,number(c.get("x1"),"x1",0,1),number(c.get("y1"),"y1",-1e9,1e9),number(c.get("x2"),"x2",0,1),number(c.get("y2"),"y2",-1e9,1e9));
                    } else curve = switch (text(e, "curve", 16)) {
                        case "linear" -> Curve.LINEAR; case "stepped" -> Curve.STEPPED;
                        default -> throw new IllegalArgumentException("curve must be linear, stepped or a bezier object.");
                    };
                    var key = next.animation(clip).timelines().stream().filter(t -> t.boneId().equals(bone) && t.channel() == channel)
                            .flatMap(t -> t.keys().stream()).filter(k -> k.time() == time).findFirst().orElseThrow();
                    next = AnimationEdits.curves(next, clip, Set.of(key.id()), Collections.nCopies(channel.components(), curve));
                }
                yield next;
            }
            case "remove_keyframes" -> {
                fields(e, "op", "animationId", "keyIds");
                JsonNode keys = e.get("keyIds");
                if (keys == null || !keys.isArray() || keys.isEmpty() || keys.size() > 500) throw new IllegalArgumentException("keyIds must contain 1 to 500 UUIDs.");
                Set<EntityId> ids = new HashSet<>();
                for (JsonNode key : keys) {
                    if (!key.isTextual() || !ids.add(new EntityId(key.textValue()))) throw new IllegalArgumentException("keyIds must contain unique UUID strings.");
                }
                yield AnimationEdits.remove(d, id(e, "animationId"), ids);
            }
            default -> SceneMcpEdits.apply(d,e,op);
        };
    }

    private ObjectNode capture(JsonNode a) throws Exception {
        fields(a, "animationId", "time", "expectedRevision","fit","showBones","showGrid");
        EntityId clip = id(a, "animationId"); double time = number(a.get("time"), "time", 0, 600);
        String revision = text(a, "expectedRevision", 128);
        var options=new CaptureOptions(SceneMcpEdits.bool(a,"fit",false),optionalBool(a,"showBones"),optionalBool(a,"showGrid"));
        var future = onEdt(() -> { host.prepare(); checkRevision(revision); host.history().requireIdle(); return host.capture(clip, time, revision,options); });
        Preview preview;
        try { preview = future.get(10, TimeUnit.SECONDS); }
        finally { future.cancel(false); }
        BufferedImage image = preview.image();
        double scale = Math.min(1, 1024. / Math.max(image.getWidth(), image.getHeight()));
        if (scale < 1) {
            var small = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            var g = small.createGraphics(); g.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null); g.dispose(); image = small;
        }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", bytes);
        var metadata = new LinkedHashMap<>(preview.metadata());
        metadata.put("width", image.getWidth()); metadata.put("height", image.getHeight());
        ObjectNode result = success(metadata);
        ((ArrayNode) result.get("content")).addObject().put("type", "image").put("mimeType", "image/png").put("data", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        return result;
    }

    public static ObjectNode success(Object data) {
        var structured = JSON.createObjectNode(); structured.set("data", JSON.valueToTree(data));
        var result = JSON.createObjectNode().put("isError", false); result.set("structuredContent", structured);
        result.putArray("content").addObject().put("type", "text").put("text", structured.toString());
        return result;
    }

    private void checkRevision(String revision) {
        if (!host.history().revision().equals(revision)) throw new IllegalStateException("REVISION_CONFLICT: project changed. Read context and query scene again, then use a new requestId.");
    }
    private static Boolean optionalBool(JsonNode a,String key) {return a.has(key)?SceneMcpEdits.bool(a,key,false):null;}

    private ObjectNode serviceOperation(String name,JsonNode a) throws Exception {
        switch(name) {
            case "openbone_control_view" -> fields(a,"action","animationId","time","loop","showBones","showGrid","expectedRevision","requestId");
            case "openbone_save_project" -> fields(a,"path","portable","overwrite","expectedRevision","requestId");
            case "openbone_load_project" -> fields(a,"path","dirtyPolicy","expectedRevision","requestId");
            default -> throw new IllegalArgumentException("Unknown service operation");
        }
        String key=name+":"+text(a,"requestId",128), revision=text(a,"expectedRevision",128);
        Receipt receipt=receipts.get(key);
        if(receipt!=null) {if(!receipt.arguments().equals(a))throw new IllegalArgumentException("requestId already used with different arguments");return receipt.result().deepCopy();}
        ObjectNode result;
        if(name.equals("openbone_control_view")) {
            String action=text(a,"action",32);
            if(!Set.of("play","pause","seek","frame","configure").contains(action))throw new IllegalArgumentException("Invalid view action");
            if(action.equals("seek")&&!a.has("time"))throw new IllegalArgumentException("seek requires time");
            var control=new ViewControl(action,a.has("animationId")?id(a,"animationId"):null,a.has("time")?number(a.get("time"),"time",0,600):null,optionalBool(a,"loop"),optionalBool(a,"showBones"),optionalBool(a,"showGrid"));
            result=onEdt(()->{host.prepare();checkRevision(revision);host.control(control);return success(context());});
        } else {
            Path path=SceneMcpEdits.absolutePath(a,"path");io.github.samlinb.openbone.io.ProjectCodec.requireProjectPath(path);
            boolean loading=name.equals("openbone_load_project");
            String policy=loading?text(a,"dirtyPolicy",16):"reject";
            if(loading&&!Set.of("reject","discard").contains(policy))throw new IllegalArgumentException("dirtyPolicy must be reject or discard");
            DocumentSnapshot snapshot=onEdt(()->{
                host.prepare();checkRevision(revision);
                if(loading&&host.history().isDirty()&&!policy.equals("discard"))throw new IllegalStateException("DIRTY_PROJECT: save first or explicitly use dirtyPolicy:discard");
                return host.history().state().model();
            });
            ProjectDocument prepared=new ProjectDocument(loading?DocumentSnapshot.EMPTY:snapshot);
            if(loading) {
                prepared.openResolved(path);
                result=onEdt(()->{host.prepare();checkRevision(revision);host.installProject(prepared);return success(context());});
            } else {
                boolean overwrite=SceneMcpEdits.bool(a,"overwrite",false), portable=SceneMcpEdits.bool(a,"portable",true);
                if(Files.exists(path)&&!overwrite)throw new IllegalStateException("TARGET_EXISTS: explicitly set overwrite:true or choose a new path");
                if(portable)prepared.savePortable(path);else prepared.save(path);
                result=onEdt(()->{
                    boolean adopted=host.history().revision().equals(revision)&&!host.history().isPreviewing();
                    if(adopted) {host.project().acceptSavedCopy(prepared);host.changed();}
                    var state=new LinkedHashMap<>(context());state.put("savedPath",path.toString());state.put("savedRevision",revision);state.put("adopted",adopted);return success(state);
                });
            }
        }
        receipts.put(key,new Receipt(a.deepCopy(),result.deepCopy()));
        if(receipts.size()>128)receipts.remove(receipts.keySet().iterator().next());
        return result;
    }
    private static <T> T onEdt(Callable<T> work) throws Exception {
        var task = new FutureTask<T>(work); SwingUtilities.invokeLater(task);
        try { return task.get(10, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }
    private static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Arguments must be an object.");
        var names = Set.of(allowed); node.fieldNames().forEachRemaining(k -> { if (!names.contains(k)) throw new IllegalArgumentException("Unknown field: " + k); });
    }
    private static String text(JsonNode a, String key, int max) {
        JsonNode n = a == null ? null : a.get(key);
        if (n == null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length() > max) throw new IllegalArgumentException(key + " must be a nonempty string of at most " + max + " characters.");
        return n.textValue();
    }
    private static EntityId id(JsonNode a, String key) { return new EntityId(text(a, key, 36)); }
    private static int integer(JsonNode a, String key, int fallback, int min, int max) {
        if (!a.has(key)) return fallback;
        var n = a.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < min || n.intValue() > max) throw new IllegalArgumentException(key + " is out of range.");
        return n.intValue();
    }
    private static double number(JsonNode n, String key, double min, double max) {
        if (n == null || !n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max) throw new IllegalArgumentException(key + " must be a finite number between " + min + " and " + max);
        return n.doubleValue();
    }
}
