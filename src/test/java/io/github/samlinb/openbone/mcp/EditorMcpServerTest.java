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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorMcpServerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "openbone_test_token_0123456789abcdef";
    static final EntityId BONE = EntityId.create(), CLIP = EntityId.create();
    final HttpClient http = HttpClient.newHttpClient();
    final TestHost host = new TestHost();
    EditorMcpServer server;
    int sequence;

    static final class TestHost implements EditorMcpTools.Host {
        CommandHistory history = new CommandHistory(new DocumentSnapshot(List.of(new Bone(BONE, "root", null, new Transform(10, 20, 30, 2, 3), 60)), List.of(), List.of(), List.of()));
        int refreshes;
        void edt() { assertTrue(SwingUtilities.isEventDispatchThread()); }
        public CommandHistory history() { edt(); return history; }
        public Map<String, Object> context() { edt(); return Map.of("mode", "setup"); }
        public void prepare() { edt(); history.requireIdle(); }
        public void changed() { edt(); refreshes++; }
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
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", accept);
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
        var tools = rpc("tools/list", Map.of()).path("result").path("tools"); assertEquals(6, tools.size());
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
}
