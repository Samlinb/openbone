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
public final class EditorMcpTools implements AutoCloseable {
    public interface Host {
        default String accessDescription() { return ""; }
        /** Immutable host policy, executed off EDT before any file access. */
        default void validateArguments(String name, JsonNode arguments) throws Exception {}
        default void loadProject(ProjectDocument target, Path path, io.github.samlinb.openbone.io.SpineImportPolicy.Approval approval) throws Exception {
            target.openResolved(path, approval);
        }
        default boolean supportsPreview() { return true; }
        default boolean supportsViewControl() { return true; }
        default String previewDescription() { return io.github.samlinb.openbone.i18n.M.m("mcp.desktopPreviewDescription"); }
        default String previewSource() { return "OpenBone live GL viewport"; }
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
    public record CaptureOptions(boolean fit, Boolean showBones, Boolean showGrid, boolean fitAnimation,
            io.github.samlinb.openbone.viewport.AnimationFraming.Bounds framingBounds) {
        public CaptureOptions(boolean fit,Boolean showBones,Boolean showGrid,boolean fitAnimation){this(fit,showBones,showGrid,fitAnimation,null);}
        public CaptureOptions(boolean fit,Boolean showBones,Boolean showGrid){this(fit,showBones,showGrid,false);}
    }
    public record ViewControl(String action, EntityId animation, Double time, Boolean loop, Boolean showBones, Boolean showGrid, List<EntityId> animationIds) {}
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
    private final AnimationMediaService media = new AnimationMediaService();
    // Bounded, process-local retry receipts. Reusing an ID with different arguments is rejected.
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();

    public EditorMcpTools(Host host) { this.host = Objects.requireNonNull(host); }

    public ArrayNode definitions() {
        ArrayNode tools = JSON.createArrayNode();
        add(tools, "openbone_get_context", "Read the current live project summary, selection, view and revision. Query scene for IDs and keyframes.", schema("{}"), true);
        add(tools, "openbone_query_scene", AnimationMcpQueries.message("description"), schema("""
            {"kind":{"enum":["bones","animations","keyframes","slots","attachments","constraints","skins"]},
             "animationId":{"type":"string"},"boneId":{"type":"string"},"expectedRevision":{"type":"string"},
             "trackType":{"enum":["bone","non_bone","all","deform","constraint","slot_color","attachment","draw_order","event"]},
             "targetId":{"type":"string"},"channel":{"type":"string","minLength":1,"maxLength":64},
             "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":200}}
            """, "kind"), true);
        add(tools, "openbone_apply_edits", AnimationMcpEdits.message("description"), editSchema(), false);
        add(tools, "openbone_capture_preview", host.previewDescription(), schema("""
            {"animationId":{"type":"string"},"time":{"type":"number","minimum":0,"maximum":600},"expectedRevision":{"type":"string"},"fit":{"type":"boolean"},"showBones":{"type":"boolean"},"showGrid":{"type":"boolean"}}
            """, "animationId", "time", "expectedRevision"), false);
        for (String action : List.of("undo", "redo"))
            add(tools, "openbone_" + action, "Perform one " + action + " in the current editor history (including manual edits). Read context first; retry with the same requestId and arguments.", schema("""
                {"expectedRevision":{"type":"string"},"requestId":{"type":"string","minLength":1,"maxLength":128}}
                """, "expectedRevision", "requestId"), false);
        add(tools,"openbone_control_view","Control live playback and camera without editing model/history. action seek requires time; play/seek use animationId or the current animation. frame fits current visible geometry (bones if empty). Use action sequence with animationIds (ordered, duplicates allowed, 1-128 IDs) to preview consecutive actions without blending; zero-duration actions are skipped and loop repeats the whole sequence. play/pause resume/pause the current sequence; play with explicit animationId or seek exits sequence mode. Configure overlay flags and looping. Uses revision and retry receipt like editing.",schema("""
            {"action":{"enum":["play","pause","seek","frame","configure","sequence"]},"animationId":{"type":"string"},"animationIds":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"string"}},"time":{"type":"number","minimum":0,"maximum":600},"loop":{"type":"boolean"},"showBones":{"type":"boolean"},"showGrid":{"type":"boolean"},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","action","expectedRevision","requestId"),false);
        add(tools,"openbone_save_project","Save a captured current project to an absolute .json path, portable by default (copies PNGs via the existing save service). Existing target requires overwrite:true. Preserves live undo history. If manual edits occur during IO, savedRevision identifies the disk snapshot and adopted:false leaves current state dirty. Retry with identical requestId and arguments.",schema("""
            {"path":{"type":"string"},"portable":{"type":"boolean"},"overwrite":{"type":"boolean"},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","path","expectedRevision","requestId"),false);
        add(tools,"openbone_load_project","Load an OpenBone JSON project or import Spine JSON/binary data through the shared project service. Unknown versions return SPINE_VERSION_RISK before parsing. Present the warning to the human user; only after explicit approval resend with versionRiskApproval from the risk response and a new requestId. Fallback prefers the nearest newer parser of the same format, otherwise the nearest available parser. Forced imports still require structural validation. Binary exports resolve same-named atlas/atlas.txt and PNG pages. dirtyPolicy reject/discard controls unsaved edits. Failed loads preserve the live project. Retry successful operations with identical requestId and arguments.",schema("""
            {"path":{"type":"string"},"dirtyPolicy":{"enum":["reject","discard"]},"expectedRevision":{"type":"string"},"requestId":{"type":"string"},"versionRiskApproval":{"type":"object","additionalProperties":false,"properties":{"sourceHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"parserId":{"enum":["JSON_38","JSON_40","JSON_41","JSON_42","JSON_43","BINARY_38","BINARY_40","BINARY_41","BINARY_42","BINARY_43","NATIVE_38","NATIVE_40","NATIVE_41","NATIVE_42","NATIVE_43"]}},"required":["sourceHash","parserId"]}}
            ""","path","dirtyPolicy","expectedRevision","requestId"),false);
        add(tools,"openbone_export_spine","Export a snapshot of the current project as a portable Spine package. format JSON/BINARY uses independently implemented 3.8,4.0,4.1,4.2,4.3 schemas. NATIVE creates a real .spine via a separately configured licensed Spine CLI; it is not an OpenBone project renamed to .spine. Version-incompatible features fail before publication. Files publish atomically through current.json; no live model/history changes. Read context for expectedRevision and retry identical arguments with the same requestId.",schema("""
            {"directory":{"type":"string"},"version":{"type":"string"},"format":{"enum":["JSON","BINARY","NATIVE"]},"expectedRevision":{"type":"string"},"requestId":{"type":"string"}}
            ""","directory","version","format","expectedRevision","requestId"),false);
        add(tools,"openbone_render_animation",io.github.samlinb.openbone.i18n.M.m("media.renderDescription"),schema("""
            {"directory":{"type":"string"},"animationId":{"type":"string"},"fps":{"type":"integer","minimum":1,"maximum":60},"fit":{"type":"boolean"},"fitTime":{"type":"number","minimum":0,"maximum":600},"expectedRevision":{"type":"string"},"requestId":{"type":"string","minLength":1,"maxLength":128}}
            ""","directory","animationId","expectedRevision","requestId"),false);
        add(tools,"openbone_render_status",io.github.samlinb.openbone.i18n.M.m("media.statusDescription"),schema("""
            {"jobId":{"type":"string"},"waitMs":{"type":"integer","minimum":0,"maximum":5000}}
            ""","jobId"),true);
        add(tools,"openbone_cancel_render",io.github.samlinb.openbone.i18n.M.m("media.cancelDescription"),schema("""
            {"jobId":{"type":"string"}}
            ""","jobId"),false);
        for (int i = tools.size() - 1; i >= 0; i--) if (!available(tools.get(i).path("name").asText())) tools.remove(i);
        String access = host.accessDescription();
        if (!access.isEmpty()) for (JsonNode tool : tools)
            ((ObjectNode) tool).put("description", tool.path("description").asText() + " " + access);
        return tools;
    }

    private boolean available(String name) {
        return switch (name) {
            case "openbone_capture_preview", "openbone_render_animation", "openbone_render_status", "openbone_cancel_render" -> host.supportsPreview();
            case "openbone_control_view" -> host.supportsViewControl();
            default -> true;
        };
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
        variants.addAll(AnimationMcpEdits.schemas(JSON));
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
            host.validateArguments(name, args);
            if (!available(name)) throw new UnsupportedOperationException(io.github.samlinb.openbone.i18n.M.m("headless.unavailable"));
            if(media.busy()&&!Set.of("openbone_get_context","openbone_query_scene","openbone_render_status","openbone_cancel_render","openbone_render_animation").contains(name))
                throw new IllegalStateException(io.github.samlinb.openbone.i18n.M.m("media.busy"));
            ObjectNode result;
            switch (name) {
                case "openbone_get_context" -> {
                    fields(args);
                    result = success(onEdt(this::context));
                }
                case "openbone_query_scene" -> result = success(onEdt(() -> query(args)));
                case "openbone_capture_preview" -> result = capture(args);
                case "openbone_render_animation" -> result = renderAnimation(args);
                case "openbone_render_status" -> {
                    fields(args,"jobId","waitMs");
                    result=success(media.status(text(args,"jobId",128),integer(args,"waitMs",0,0,5000)));
                }
                case "openbone_cancel_render" -> {fields(args,"jobId");result=success(media.cancel(text(args,"jobId",128)));}
                case "openbone_apply_edits", "openbone_undo", "openbone_redo" -> result = mutate(name, args);
                case "openbone_control_view", "openbone_save_project", "openbone_load_project", "openbone_export_spine" -> result = serviceOperation(name,args);
                default -> throw new IllegalArgumentException("Unknown tool: " + name + ". Call tools/list.");
            }
            return result;
        } catch (Exception failed) {
            Throwable cause = failed;
            while (cause.getCause() != null && (cause instanceof ExecutionException || cause instanceof CompletionException)) cause = cause.getCause();
            var error = JSON.createObjectNode().put("isError", true);
            if(cause instanceof io.github.samlinb.openbone.io.SpineVersionRiskException risk){
                var selection=risk.selection();var detail=error.putObject("structuredContent");
                detail.put("code","SPINE_VERSION_RISK").put("requiresConfirmation",true)
                        .put("sourceVersion",selection.sourceVersion()).put("format",selection.format().name())
                        .put("parserId",selection.parser().name()).put("parserVersion",selection.parser().version());
                detail.putObject("versionRiskApproval").put("sourceHash",selection.sourceHash()).put("parserId",selection.parser().name());
            }
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
        data.put("capabilities",Map.of("sceneBuilding",true,"bezier",true,"projectIO",true,"playback",host.supportsViewControl(),"cleanPreview",host.supportsPreview(),"atomicEdits",true,"nonBoneKeyframeQuery",true,"nonBoneKeyframeEdits",true,"animationMedia",host.supportsPreview()));
        data.put("spineIO",Map.of("versions",io.github.samlinb.openbone.io.SpineVersion.exportVersions(),"formats",List.of("JSON","BINARY","NATIVE"),"nativeCliConfigured",io.github.samlinb.openbone.io.SpineCliBridge.configured()));
        data.put("dirty", h.isDirty());
        data.put("activeSkin", d.resources().activeSkin());
        data.put("busy", h.isPreviewing()||media.busy());
        data.put("mediaBusy",media.busy());
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
        fields(a, "kind", "animationId", "boneId", "expectedRevision", "offset", "limit", "trackType", "targetId", "channel");
        if (a.has("expectedRevision")) checkRevision(text(a, "expectedRevision", 128));
        var d = host.history().state().model();
        int offset = integer(a, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(a, "limit", 50, 1, 200);
        if (a.has("trackType") || a.has("targetId") || a.has("channel")) {
            if (!text(a, "kind", 32).equals("keyframes"))
                throw new IllegalArgumentException(AnimationMcpQueries.message("keyframeFilters"));
            return AnimationMcpQueries.query(d, a, host.history().revision(), offset, limit, JSON);
        }
        java.util.stream.Stream<JsonNode> rows;
        long total;
        switch (text(a, "kind", 32)) {
            case "bones" -> {
                total = d.bones().size();
                var activeBones = d.activeBones();
                rows = d.bones().stream().map(b -> {
                var row = JSON.createObjectNode().put("id", b.id().value()).put("name", b.name()).put("length", b.length()).put("inherit", b.inherit().name());
                row.put("skinRequired", d.resources().activation().requiredBones().contains(b.id()));
                row.put("skinActive", activeBones.contains(b.id()));
                if (b.parentId() == null) row.putNull("parentId"); else row.put("parentId", b.parentId().value());
                row.set("setup", JSON.valueToTree(b.local())); return row;
                });
            }
            case "animations" -> {
                total = d.animations().size();
                rows = d.animations().stream().map(c -> AnimationMcpQueries.summary(c, JSON));
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
                rows = d.attachments().stream().map(t -> {
                    if(t instanceof PathAttachment path) {
                        var row=JSON.createObjectNode().put("id",path.id().value()).put("name",path.name())
                                .put("slotId",path.slotId().value()).put("type","PathAttachment")
                                .put("weighted",path.weighted()).put("vertexCount",path.vertices().size())
                                .put("deformSize",path.deformSize()).put("closed",path.closed()).put("constantSpeed",path.constantSpeed());
                        row.set("vertices",JSON.valueToTree(path.vertices()));
                        row.set("weights",JSON.valueToTree(path.weights()));
                        row.set("lengths",JSON.valueToTree(path.lengths()));
                        return row;
                    }
                    return JSON.valueToTree(t instanceof RegionAttachment r ? Map.of("id",r.id().value(),"name",r.name(),"slotId",r.slotId().value(),"type","RegionAttachment","source",r.source(),"setup",r.local(),"width",r.width(),"height",r.height()) : Map.of("id",t.id().value(),"name",t.name(),"slotId",t.slotId().value(),"type",t.getClass().getSimpleName()));
                });
            }
            case "constraints" -> {
                total = d.constraints().size();
                var active = d.activeConstraints(d.constraints()).stream().map(Constraint::id).collect(java.util.stream.Collectors.toSet());
                rows = d.constraints().stream().map(c -> {
                    ObjectNode row = JSON.valueToTree(c);
                    row.put("skinRequired", d.resources().activation().requiredConstraints().contains(c.id()));
                    row.put("skinActive", active.contains(c.id())); return row;
                });
            }
            case "skins" -> {
                total = d.skins().size();
                Map<EntityId,String> setupNames=new HashMap<>();
                Map<EntityId,Attachment> byAttachment=new HashMap<>();
                d.attachments().forEach(attachment->byAttachment.put(attachment.id(),attachment));
                for(var slot:d.slots()) {
                    String setupName=slot.setupAttachmentName();
                    if(setupName==null && slot.activeAttachmentId()!=null)
                        setupName=byAttachment.get(slot.activeAttachmentId()).name();
                    if(setupName!=null)setupNames.put(slot.id(),setupName);
                }
                var defaultSkin=d.skins().stream().filter(s->s.name().equals("default")).findFirst();
                rows = d.skins().stream().map(s -> {
                    var members = d.resources().activation().skins().getOrDefault(s.name(), SkinActivation.Members.EMPTY);
                    var row = JSON.createObjectNode().put("name", s.name()).put("active", s.name().equals(d.resources().activeSkin()));
                    row.put("attachmentCount",s.attachments().values().stream().mapToInt(Map::size).sum());
                    row.put("setupAttachmentMatches",setupNames.entrySet().stream().filter(entry->
                            s.find(entry.getKey(),entry.getValue())!=null || defaultSkin.map(fallback->
                                    fallback.find(entry.getKey(),entry.getValue())!=null).orElse(false)).count());
                    row.set("boneIds", JSON.valueToTree(members.bones().stream().map(EntityId::value).sorted().toList()));
                    row.set("constraintIds", JSON.valueToTree(members.constraints().stream().map(EntityId::value).sorted().toList()));
                    return row;
                });
            }
            default -> throw new IllegalArgumentException("Unknown kind. Use bones, animations, keyframes, slots, attachments, constraints or skins.");
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
            case "put_constraint_keyframe", "put_deform_keyframe" -> AnimationMcpEdits.apply(d, e, op);
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

    private ObjectNode renderAnimation(JsonNode a) throws Exception {
        fields(a,"directory","animationId","fps","fit","fitTime","expectedRevision","requestId");
        String key="openbone_render_animation:"+text(a,"requestId",128);
        Receipt receipt=receipts.get(key);
        if(receipt!=null){if(!receipt.arguments().equals(a))throw new IllegalArgumentException("requestId already used with different arguments");return receipt.result().deepCopy();}
        if(media.busy())throw new IllegalStateException(io.github.samlinb.openbone.i18n.M.m("media.busy"));
        String revision=text(a,"expectedRevision",128);EntityId animation=id(a,"animationId");
        int fps=integer(a,"fps",24,1,60);boolean fit=SceneMcpEdits.bool(a,"fit",true);
        Path directory=SceneMcpEdits.absolutePath(a,"directory");
        DocumentSnapshot snapshot=onEdt(()->{host.prepare();checkRevision(revision);return host.history().state().model();});
        double duration=snapshot.animation(animation).duration();
        double fitTime=a.has("fitTime")?number(a.get("fitTime"),"fitTime",0,duration):duration*.5;
        var request=new AnimationMediaService.Request(text(a,"requestId",128),revision,animation,directory,fps,duration,fit,fitTime);
        var source=new AnimationMediaService.Source(){
            public String description(){return host.previewSource();}
            public String fitStrategy(){return "Fixed camera framed once with 70% viewport occupancy: complete encoded-frame times for up to 256 frames, otherwise 256 uniform samples plus bounded source-key extrema and attachment transitions; CPU bounds evaluated off EDT";}
            public void checkRevision(String expected)throws Exception{
                onEdt(()->{host.prepare();EditorMcpTools.this.checkRevision(expected);return null;});
            }
            public CompletableFuture<AnimationMediaService.Frame> capture(EntityId clip,double time,String expected,boolean frame)throws Exception{
                // The snapshot is immutable: potentially expensive bounds evaluation
                // belongs to the media worker, never the Swing event thread.
                var bounds=frame?io.github.samlinb.openbone.viewport.AnimationFraming.sample(snapshot,clip,fps,time):null;
                var captured=onEdt(()->{host.prepare();EditorMcpTools.this.checkRevision(expected);return host.capture(clip,time,expected,new CaptureOptions(false,false,false,frame&&bounds==null,bounds));});
                var mapped=captured.thenApply(p->new AnimationMediaService.Frame(p.image(),p.metadata()));
                mapped.whenComplete((value,failed)->{if(mapped.isCancelled())captured.cancel(false);});
                return mapped;
            }
        };
        ObjectNode result=success(media.start(request,source));receipts.put(key,new Receipt(a.deepCopy(),result.deepCopy()));
        if(receipts.size()>128)receipts.remove(receipts.keySet().iterator().next());
        return result;
    }

    @Override public void close(){media.close();}

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
            case "openbone_control_view" -> fields(a,"action","animationId","animationIds","time","loop","showBones","showGrid","expectedRevision","requestId");
            case "openbone_save_project" -> fields(a,"path","portable","overwrite","expectedRevision","requestId");
            case "openbone_load_project" -> fields(a,"path","dirtyPolicy","expectedRevision","requestId","versionRiskApproval");
            case "openbone_export_spine" -> fields(a,"directory","version","format","expectedRevision","requestId");
            default -> throw new IllegalArgumentException("Unknown service operation");
        }
        String key=name+":"+text(a,"requestId",128), revision=text(a,"expectedRevision",128);
        Receipt receipt=receipts.get(key);
        if(receipt!=null) {if(!receipt.arguments().equals(a))throw new IllegalArgumentException("requestId already used with different arguments");return receipt.result().deepCopy();}
        ObjectNode result;
        if(name.equals("openbone_export_spine")) {
            Path directory=SceneMcpEdits.absolutePath(a,"directory");String version=text(a,"version",32);
            io.github.samlinb.openbone.io.SpineVersion.target(version);
            var format=io.github.samlinb.openbone.io.ExportPackage.Format.valueOf(text(a,"format",16));
            record ExportState(DocumentSnapshot model,Path imageBase){}
            var snapshot=onEdt(()->{host.prepare();checkRevision(revision);return new ExportState(host.history().state().model(),host.project().path().map(Path::getParent).orElse(Path.of("").toAbsolutePath()));});
            Path exported=new io.github.samlinb.openbone.io.ExportPackage().publish(directory,snapshot.model(),snapshot.imageBase(),version,format);
            result=success(Map.of("path",exported.toString(),"directory",directory.toString(),"sourceRevision",revision,"version",version,"format",format.name()));
        }else if(name.equals("openbone_control_view")) {
            String action=text(a,"action",32);
            if(!Set.of("play","pause","seek","frame","configure","sequence").contains(action))throw new IllegalArgumentException("Invalid view action");
            if(action.equals("seek")&&!a.has("time"))throw new IllegalArgumentException("seek requires time");
            List<EntityId> animationIds = null;
            if (action.equals("sequence")) {
                if (a.has("animationId") || a.has("time")) throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("sequence.fields"));
                var ids = a.get("animationIds");
                if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 128)
                    throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("sequence.requiresIds"));
                var parsed = new ArrayList<EntityId>();
                for (var value : ids) {
                    if (!value.isTextual()) throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("sequence.idStrings"));
                    parsed.add(new EntityId(value.textValue()));
                }
                animationIds = List.copyOf(parsed);
            } else if (a.has("animationIds")) throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("sequence.idsAction"));
            var control=new ViewControl(action,a.has("animationId")?id(a,"animationId"):null,a.has("time")?number(a.get("time"),"time",0,600):null,optionalBool(a,"loop"),optionalBool(a,"showBones"),optionalBool(a,"showGrid"),animationIds);
            result=onEdt(()->{
                // Reject invalid/stale playlists before prepare pauses the current preview.
                if (control.animationIds() != null) {
                    checkRevision(revision);
                    new AnimationSequence(host.history().state().model(), control.animationIds());
                }
                host.prepare();checkRevision(revision);
                host.control(control);return success(context());});
        } else {
            Path path=SceneMcpEdits.absolutePath(a,"path");
            boolean loading=name.equals("openbone_load_project");
            if (!loading || !ProjectDocument.isSpineExport(path)&&!io.github.samlinb.openbone.io.SpineProjectSource.isNative(path)) io.github.samlinb.openbone.io.ProjectCodec.requireProjectPath(path);
            String policy=loading?text(a,"dirtyPolicy",16):"reject";
            if(loading&&!Set.of("reject","discard").contains(policy))throw new IllegalArgumentException("dirtyPolicy must be reject or discard");
            DocumentSnapshot snapshot=onEdt(()->{
                host.prepare();checkRevision(revision);
                if(loading&&host.history().isDirty()&&!policy.equals("discard"))throw new IllegalStateException("DIRTY_PROJECT: save first or explicitly use dirtyPolicy:discard");
                return host.history().state().model();
            });
            ProjectDocument prepared=new ProjectDocument(loading?DocumentSnapshot.EMPTY:snapshot);
            if(loading) {
                io.github.samlinb.openbone.io.SpineImportPolicy.Approval approval=null;
                if(a.has("versionRiskApproval")){
                    var accepted=a.get("versionRiskApproval");fields(accepted,"sourceHash","parserId");
                    String hash=text(accepted,"sourceHash",64),parser=text(accepted,"parserId",32);
                    if(!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid sourceHash");
                    io.github.samlinb.openbone.io.SpineImportPolicy.Parser.valueOf(parser);
                    approval=new io.github.samlinb.openbone.io.SpineImportPolicy.Approval(hash,parser);
                }
                host.loadProject(prepared,path,approval);
                result=onEdt(()->{host.prepare();checkRevision(revision);host.installProject(prepared);
                    var loaded=new LinkedHashMap<>(context());loaded.put("importWarnings",prepared.importWarnings());return success(loaded);});
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
