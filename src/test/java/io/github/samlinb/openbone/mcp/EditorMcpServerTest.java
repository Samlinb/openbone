package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.io.ProjectCodec;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EditorMcpServerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "openbone_test_token_0123456789abcdef";
    static final EntityId BONE = EntityId.create(), CLIP = EntityId.create();
    final HttpClient http = HttpClient.newHttpClient();
    final TestHost host = new TestHost();
    EditorMcpServer server;
    int sequence;
    @TempDir Path temp;

    static final class TestHost implements EditorMcpTools.Host {
        ProjectDocument project = new ProjectDocument(new DocumentSnapshot(List.of(new Bone(BONE, "root", null, new Transform(10, 20, 30, 2, 3), 60)), List.of(), List.of(), List.of()));
        CommandHistory history = project.history();
        int refreshes;
        Runnable prepareHook;
        void edt() { assertTrue(SwingUtilities.isEventDispatchThread()); }
        public CommandHistory history() { edt(); return history; }
        public Map<String, Object> context() { edt(); return Map.of("mode", "setup"); }
        public void prepare() { edt(); history.requireIdle(); if(prepareHook!=null)prepareHook.run(); }
        public void changed() { edt(); refreshes++; }
        public ProjectDocument project() {edt();return project;}
        public void installProject(ProjectDocument next) {edt();project=next;history=next.history();refreshes++;}
        public CompletableFuture<EditorMcpTools.Preview> capture(EntityId animation, double time, String revision) {
            edt(); history.state().model().animation(animation);
            var image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB); image.setRGB(3, 2, 0xff00ff00);
            return CompletableFuture.completedFuture(new EditorMcpTools.Preview(image, Map.of("revision", history.revision(), "time", time)));
        }
    }

    @BeforeEach void start() throws Exception { server = new EditorMcpServer(0, TOKEN, new EditorMcpTools(host)); }
    @AfterEach void close() { server.close(); }

    HttpResponse<String> post(JsonNode body) throws Exception { return request("POST", JSON.writeValueAsString(body), TOKEN, null, "application/json, text/event-stream", null); }
    HttpResponse<String> request(String method, String body, String token, String origin, String accept, String version) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(server.url())).timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", accept);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (origin != null) b.header("Origin", origin);
        if (version != null) b.header("MCP-Protocol-Version", version);
        return http.send(b.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode rpc(String method, Object params) throws Exception {
        var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", ++sequence).put("method", method);
        request.set("params", JSON.valueToTree(params));
        var response = post(request); assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }
    JsonNode call(String name, Object args) throws Exception {
        var response = rpc("tools/call", Map.of("name", name, "arguments", args));
        assertFalse(response.has("error"), response.toString()); return response.get("result");
    }
    JsonNode ok(String name, Object args) throws Exception {
        var result = call(name, args); assertFalse(result.path("isError").asBoolean(), result.toString());
        return result.path("structuredContent").path("data");
    }
    String revision() throws Exception { return ok("openbone_get_context", Map.of()).path("revision").asText(); }
    Map<String, Object> create() { return Map.of("op", "create_animation", "animationId", CLIP.value(), "name", "AI 待机"); }
    Map<String, Object> key(double time, String channel, double... values) {
        return Map.of("op", "put_keyframe", "animationId", CLIP.value(), "boneId", BONE.value(), "channel", channel, "time", time, "values", Arrays.stream(values).boxed().toList());
    }
    Map<String, Object> mutation(String revision, Object... edits) { return Map.of("expectedRevision", revision, "requestId", UUID.randomUUID().toString(), "edits", List.of(edits)); }
    static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }

    @Test void initializationNegotiatesVersionsAndListsWellFormedTools() throws Exception {
        for (String version : List.of("2025-11-25", "2025-06-18", "2025-03-26", "future")) {
            var result = rpc("initialize", Map.of("protocolVersion", version, "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))).path("result");
            assertEquals(version.equals("future") ? EditorMcpServer.PROTOCOL : version, result.path("protocolVersion").asText());
            assertTrue(result.path("capabilities").has("tools"));
        }
        var notification = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        assertEquals(202, post(notification).statusCode());
        var tools = rpc("tools/list", Map.of()).path("result").path("tools"); assertEquals(9, tools.size());
        for (var tool : tools) {
            assertEquals("object", tool.path("inputSchema").path("type").asText());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
            assertTrue(tool.has("outputSchema")); assertFalse(tool.path("annotations").path("openWorldHint").asBoolean());
        }
    }

    @Test void liveReadEditPreviewUndoRedoAndProjectRoundTrip() throws Exception {
        String before = revision();
        var bones = ok("openbone_query_scene", Map.of("kind", "bones", "limit", 1, "expectedRevision", before));
        assertEquals(BONE.value(), bones.path("items").get(0).path("id").asText());
        assertEquals(30, bones.path("items").get(0).path("setup").path("rotation").asDouble());
        var result = ok("openbone_apply_edits", mutation(before, create(), key(0, "ROTATE", 0), key(1, "ROTATE", 40), key(1, "SCALE", 1.5, .5)));
        assertEquals(1, result.path("undoCount").asInt()); assertTrue(result.path("dirty").asBoolean());
        String after = result.path("revision").asText(); assertNotEquals(before, after);
        edt(() -> {
            var d = host.history.state().model(); var pose = AnimationSampler.pose(d, CLIP, 1, false).get(BONE);
            assertEquals(70, pose.rotation()); assertEquals(3, pose.scaleX()); assertEquals(1.5, pose.scaleY());
            assertEquals(d, new ProjectCodec().decode(new ProjectCodec().encode(d))); return null;
        });
        var preview = call("openbone_capture_preview", Map.of("animationId", CLIP.value(), "time", .5, "expectedRevision", after));
        assertFalse(preview.path("isError").asBoolean(), preview.toString());
        var png = preview.path("content").get(1); assertEquals("image/png", png.path("mimeType").asText());
        var image = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(png.path("data").asText())));
        assertEquals(0xff00ff00, image.getRGB(3, 2)); assertEquals(after, revision());
        var undo = ok("openbone_undo", Map.of("requestId", "undo-1", "expectedRevision", after));
        assertFalse(undo.path("dirty").asBoolean()); assertEquals(0, undo.path("counts").path("animations").asInt());
        assertNotEquals(before, undo.path("revision").asText());
        var redo = ok("openbone_redo", Map.of("requestId", "redo-1", "expectedRevision", undo.path("revision").asText()));
        assertEquals(1, redo.path("counts").path("animations").asInt());
    }

    @Test void invalidBatchIsAtomicAndDoesNotDestroyRedo() throws Exception {
        var args = mutation(revision(), create(), key(0, "ROTATE", 0));
        ok("openbone_apply_edits", args); ok("openbone_undo", Map.of("requestId", "u", "expectedRevision", revision()));
        String revision = revision();
        var result = call("openbone_apply_edits", mutation(revision, create(), key(0, "TRANSLATE", 3)));
        assertTrue(result.path("isError").asBoolean()); assertEquals(revision, revision());
        var state = ok("openbone_get_context", Map.of()); assertEquals(1, state.path("redoCount").asInt()); assertFalse(state.path("dirty").asBoolean());
    }

    @Test void exactTimestampReplacementPreservesKeyIdAndAllowsDeleteAndUndo() throws Exception {
        ok("openbone_apply_edits", mutation(revision(), create(), key(0, "ROTATE", 5)));
        var first = ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", CLIP.value())).path("items").get(0);
        String keyId = first.path("id").asText();
        var stepped = new HashMap<>(key(0, "ROTATE", 12)); stepped.put("curve", "stepped");
        ok("openbone_apply_edits", mutation(revision(), stepped));
        var updated = ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", CLIP.value())).path("items");
        assertEquals(1, updated.size()); assertEquals(keyId, updated.get(0).path("id").asText());
        assertEquals("STEPPED", updated.get(0).path("curves").get(0).path("kind").asText());
        ok("openbone_apply_edits", mutation(revision(), Map.of("op", "remove_keyframes", "animationId", CLIP.value(), "keyIds", List.of(keyId))));
        assertEquals(0, ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", CLIP.value())).path("total").asInt());
        ok("openbone_undo", Map.of("requestId", "restore", "expectedRevision", revision()));
        assertEquals(keyId, ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", CLIP.value())).path("items").get(0).path("id").asText());
    }

    @Test void retriesNeverDuplicateWritesOrUndoAndConflictingReuseIsRejected() throws Exception {
        var edit = mutation(revision(), create(), key(0, "ROTATE", 0));
        var first = ok("openbone_apply_edits", edit); assertEquals(first, ok("openbone_apply_edits", edit));
        var conflicting = new HashMap<>(edit); conflicting.put("label", "different");
        assertTrue(call("openbone_apply_edits", conflicting).path("isError").asBoolean());
        var undo = Map.of("requestId", "repeat-undo", "expectedRevision", revision());
        var undone = ok("openbone_undo", undo); assertEquals(undone, ok("openbone_undo", undo));
        assertEquals(1, ok("openbone_get_context", Map.of()).path("redoCount").asInt());
    }

    @Test void manualEditsUndoAndNewProjectsInvalidateOldRevisions() throws Exception {
        String old = revision();
        edt(() -> { host.history.execute("manual", Commands.model(d -> ModelEdits.editBone(d, BONE, b -> b.withLocal(Transform.IDENTITY)))); return null; });
        assertTrue(call("openbone_apply_edits", mutation(old, create())).path("content").get(0).path("text").asText().contains("REVISION_CONFLICT"));
        edt(() -> { host.history.undo(); return null; }); assertNotEquals(old, revision());
        String beforeReset = revision(); edt(() -> { host.history.reset(host.history.state().model()); return null; }); assertNotEquals(beforeReset, revision());
        String beforeNew = revision(); edt(() -> { host.history = new CommandHistory(DocumentSnapshot.EMPTY); return null; }); assertNotEquals(beforeNew, revision());
    }

    @Test void interactivePreviewRefusesRemoteMutation() throws Exception {
        String revision = revision(); edt(() -> { host.history.beginPreview("drag"); return null; });
        assertTrue(call("openbone_apply_edits", mutation(revision, create())).path("isError").asBoolean());
        assertEquals(revision, revision()); edt(() -> { host.history.cancelPreview(); return null; });
    }

    @Test void paginationAndStrictArgumentsReturnActionableErrors() throws Exception {
        assertEquals(0, ok("openbone_query_scene", Map.of("kind", "bones", "offset", 99)).path("items").size());
        assertTrue(call("openbone_query_scene", Map.of("kind", "bones", "limit", 0)).path("isError").asBoolean());
        assertTrue(call("openbone_get_context", Map.of("unexpected", true)).path("isError").asBoolean());
        assertTrue(call("openbone_query_scene", Map.of("kind", "keyframes", "animationId", "bad-id")).path("isError").asBoolean());
        assertTrue(call("openbone_apply_edits", mutation(revision(), create(), key(-1, "ROTATE", 1))).path("isError").asBoolean());
        assertTrue(call("openbone_apply_edits", mutation(revision(), create(), key(0, "SCALE", 1, Double.NaN))).path("isError").asBoolean());
    }

    @Test void explicitPortAndTokenRemainUsableAfterRestartAndConflictsDoNotFallback() throws Exception {
        int port = URI.create(server.url()).getPort();
        String originalUrl = server.url();
        assertThrows(java.net.BindException.class, () -> new EditorMcpServer(port, TOKEN, new EditorMcpTools(host)));
        assertTrue(rpc("ping", Map.of()).has("result"));
        server.close();
        server = new EditorMcpServer(port, TOKEN, new EditorMcpTools(host));
        assertEquals(originalUrl, server.url());
        assertTrue(rpc("ping", Map.of()).has("result"));
        assertEquals(401, request("POST", "{}", "wrong", null, "application/json, text/event-stream", null).statusCode());
    }

    @Test void localConnectionsNeedNoCredentialsUnlessExplicitlyConfigured() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(401, request("POST", ping, null, null, "application/json, text/event-stream", null).statusCode());
        for (String optionalToken : Arrays.asList(null, "")) {
            server.close();
            server = new EditorMcpServer(0, optionalToken, new EditorMcpTools(host));
            var response = request("POST", ping, null, null, "application/json, text/event-stream", null);
            assertEquals(200, response.statusCode());
            assertTrue(JSON.readTree(response.body()).has("result"));
            assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
            assertEquals(403, request("POST", ping, null, "https://evil.example", "application/json, text/event-stream", null).statusCode());
            assertEquals("127.0.0.1", URI.create(server.url()).getHost());
        }
        assertThrows(IllegalArgumentException.class, () -> new EditorMcpServer(0, "short", new EditorMcpTools(host)));
    }

    @Test void transportRejectsUnauthorizedOriginsVersionsAndMalformedMessages() throws Exception {
        assertEquals(401, request("POST", "{}", "wrong", null, "application/json, text/event-stream", null).statusCode());
        assertEquals(403, request("POST", "{}", TOKEN, "https://evil.example", "application/json, text/event-stream", null).statusCode());
        assertEquals(405, request("GET", "", TOKEN, null, "application/json, text/event-stream", null).statusCode());
        assertEquals(406, request("POST", "{}", TOKEN, null, "application/json", null).statusCode());
        assertEquals(400, request("POST", "{}", TOKEN, null, "application/json, text/event-stream", "future").statusCode());
        for (String body : List.of("{", "[]", "null", "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"1.0\"}", "{} {}"))
            assertEquals(400, request("POST", body, TOKEN, null, "application/json, text/event-stream", null).statusCode(), body);
        assertEquals(413, request("POST", " ".repeat(1_048_577), TOKEN, null, "application/json, text/event-stream", null).statusCode());
        assertTrue(rpc("unknown", Map.of()).has("error"));
    }

    @Test void notificationsCannotExecuteEdits() throws Exception {
        String before = revision();
        var n = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "tools/call");
        n.set("params", JSON.valueToTree(Map.of("name", "openbone_apply_edits", "arguments", mutation(before, create()))));
        assertEquals(202, post(n).statusCode()); assertEquals(before, revision());
    }

    Map<String,Object> setup(double x,double y,double angle) {return Map.of("x",x,"y",y,"rotation",angle,"scaleX",1,"scaleY",1);}
    Path png() throws Exception {Path path=temp.resolve("part.png");ImageIO.write(new BufferedImage(20,10,BufferedImage.TYPE_INT_ARGB),"PNG",path.toFile());return path;}
    Map<String,Object> bone(EntityId id,EntityId parent) {
        var op=new HashMap<String,Object>();op.put("op","create_bone");op.put("boneId",id.value());op.put("name",id.value());op.put("parentId",parent==null?null:parent.value());op.put("setup",setup(40,10,0));op.put("length",30);return op;
    }

    @Test void sceneConstructionEditingReparentOrderAndUndoShareTheLiveHistory() throws Exception {
        var child=EntityId.create();var slot=EntityId.create();var region=EntityId.create();var otherSlot=EntityId.create();
        Path png=png();String before=revision();
        var add=mutation(before,bone(child,BONE),Map.of("op","create_slot","slotId",slot.value(),"name","body","boneId",child.value()),
                Map.of("op","create_region","attachmentId",region.value(),"slotId",slot.value(),"name","part","pngPath",png.toString()),
                Map.of("op","create_slot","slotId",otherSlot.value(),"name","other","boneId",BONE.value()));
        var built=ok("openbone_apply_edits",add);assertEquals(2,built.path("counts").path("bones").asInt());assertEquals(1,built.path("undoCount").asInt());
        assertEquals(built,ok("openbone_apply_edits",add));
        var regions=ok("openbone_query_scene",Map.of("kind","attachments")).path("items");assertEquals(20,regions.get(0).path("width").asDouble());
        var origin=edt(()->host.history.state().model().evaluateWorld().bones().get(child).origin());
        var reparent=new HashMap<String,Object>();reparent.put("op","reparent_bone");reparent.put("boneId",child.value());reparent.put("parentId",null);reparent.put("policy","keep_world");
        ok("openbone_apply_edits",mutation(revision(),reparent,Map.of("op","edit_bone","boneId",child.value(),"name","arm","length",80),
                Map.of("op","edit_region","attachmentId",region.value(),"width",90,"height",40,"setup",setup(20,0,45)),
                Map.of("op","set_slot_order","slotIds",List.of(otherSlot.value(),slot.value()))));
        edt(()->{var model=host.history.state().model();assertEquals(origin,model.evaluateWorld().bones().get(child).origin());assertEquals(otherSlot,model.slots().get(0).id());assertEquals("arm",model.bone(child).name());assertEquals(90,((RegionAttachment)model.attachment(region)).width());return null;});
        ok("openbone_undo",Map.of("requestId","undo-scene-edits","expectedRevision",revision()));
        ok("openbone_undo",Map.of("requestId","undo-scene-create","expectedRevision",revision()));
        assertEquals(0,ok("openbone_get_context",Map.of()).path("counts").path("slots").asInt());
        assertFalse(ok("openbone_get_context",Map.of()).path("dirty").asBoolean());
        ok("openbone_redo",Map.of("requestId","redo-scene-create","expectedRevision",revision()));
        assertEquals(2,ok("openbone_get_context",Map.of()).path("counts").path("slots").asInt());
    }

    @Test void missingRelativeAndCorruptPngAndInvalidTopologyLeaveNoPartialScene() throws Exception {
        var child=EntityId.create();var slot=EntityId.create();var region=EntityId.create();
        Path corrupt=temp.resolve("bad.png");Files.writeString(corrupt,"not a png");
        String before=revision();
        for(String source:List.of("relative.png",temp.resolve("missing.png").toString(),corrupt.toString())) {
            var failed=call("openbone_apply_edits",mutation(before,bone(child,BONE),Map.of("op","create_slot","slotId",slot.value(),"name","part","boneId",child.value()),
                    Map.of("op","create_region","attachmentId",region.value(),"slotId",slot.value(),"name","part","pngPath",source)));
            assertTrue(failed.path("isError").asBoolean());assertEquals(before,revision());
        }
        assertTrue(call("openbone_apply_edits",mutation(before,bone(child,BONE),Map.of("op","reparent_bone","boneId",BONE.value(),"parentId",child.value(),"policy","keep_local"))).path("isError").asBoolean());
        assertEquals(before,revision());assertEquals(1,ok("openbone_get_context",Map.of()).path("counts").path("bones").asInt());
    }

    @Test void bezierIsSmoothAndInvalidHandlesAreAtomic() throws Exception {
        var first=new HashMap<>(key(0,"ROTATE",0));first.put("curve",Map.of("kind","bezier","x1",.5,"y1",0,"x2",1,"y2",1));
        ok("openbone_apply_edits",mutation(revision(),create(),first,key(1,"ROTATE",40)));
        double sampled=edt(()->AnimationSampler.pose(host.history.state().model(),CLIP,.5,false).get(BONE).rotation());
        assertTrue(sampled>30&&sampled<50,"Ease-in midpoint must lag the linear midpoint");
        var bad=new HashMap<>(first);bad.put("curve",Map.of("kind","bezier","x1",2,"y1",0,"x2",1,"y2",1));String before=revision();
        assertTrue(call("openbone_apply_edits",mutation(before,bad)).path("isError").asBoolean());assertEquals(before,revision());
    }

    @Test void portableSaveAndExplicitDirtyLoadKeepHistoryAndRoundTripPng() throws Exception {
        var slot=EntityId.create();var region=EntityId.create();
        ok("openbone_apply_edits",mutation(revision(),Map.of("op","create_slot","slotId",slot.value(),"name","body","boneId",BONE.value()),
                Map.of("op","create_region","attachmentId",region.value(),"slotId",slot.value(),"name","part","pngPath",png().toString())));
        String before=revision();Path target=temp.resolve("saved.json");var history=host.history;
        var save=Map.of("path",target.toString(),"expectedRevision",before,"requestId","save-once");
        var saved=ok("openbone_save_project",save);assertTrue(saved.path("adopted").asBoolean());assertFalse(saved.path("dirty").asBoolean());assertSame(history,host.history);assertEquals(1,saved.path("undoCount").asInt());
        assertEquals(saved,ok("openbone_save_project",save));assertTrue(Files.isDirectory(temp.resolve("saved.json.assets")));
        assertTrue(call("openbone_save_project",Map.of("path",target.toString(),"expectedRevision",before,"requestId","other-save")).path("isError").asBoolean());
        ok("openbone_apply_edits",mutation(before,Map.of("op","edit_bone","boneId",BONE.value(),"name","changed")));
        String dirty=revision();
        assertTrue(call("openbone_load_project",Map.of("path",target.toString(),"expectedRevision",dirty,"requestId","reject-dirty","dirtyPolicy","reject")).path("isError").asBoolean());assertEquals(dirty,revision());
        Path corrupt=temp.resolve("corrupt.json");Files.writeString(corrupt,"bad");
        assertTrue(call("openbone_load_project",Map.of("path",corrupt.toString(),"expectedRevision",dirty,"requestId","bad-load","dirtyPolicy","discard")).path("isError").asBoolean());assertEquals(dirty,revision());
        var load=Map.of("path",target.toString(),"expectedRevision",dirty,"requestId","load-once","dirtyPolicy","discard");
        var loaded=ok("openbone_load_project",load);assertFalse(loaded.path("dirty").asBoolean());assertEquals(0,loaded.path("undoCount").asInt());assertNotEquals(dirty,loaded.path("revision").asText());
        assertEquals(loaded,ok("openbone_load_project",load));
        edt(()->{var attachment=(RegionAttachment)host.history.state().model().attachment(region);assertTrue(Files.isRegularFile(Path.of(attachment.source())));assertEquals("root",host.history.state().model().bone(BONE).name());return null;});
    }

    @Test void manualEditDuringWorkerValidationRejectsCandidateAtPublication() throws Exception {
        String before=revision();var count=new java.util.concurrent.atomic.AtomicInteger();
        host.prepareHook=()->{if(count.incrementAndGet()==2)host.history.execute("manual during AI validation",Commands.model(d->ModelEdits.editBone(d,BONE,b->b.withName("manual name"))));};
        var result=call("openbone_apply_edits",mutation(before,create(),key(0,"ROTATE",0)));
        assertTrue(result.path("isError").asBoolean());
        edt(()->{assertEquals("manual name",host.history.state().model().bone(BONE).name());assertTrue(host.history.state().model().animations().isEmpty());assertEquals(1,host.history.undoCount());return null;});
    }
}
