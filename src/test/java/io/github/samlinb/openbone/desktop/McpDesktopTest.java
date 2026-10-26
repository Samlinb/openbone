package io.github.samlinb.openbone.desktop;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import io.github.samlinb.openbone.mcp.EditorMcpServer;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual visible desktop + HTTP + production GL capture, independent of OS screenshots. */
@EnabledIfSystemProperty(named = "openbone.verifyGl", matches = "true")
class McpDesktopTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    EditorFrame frame;
    String url, token;
    int sequence;

    @Test void currentProjectKeyframesGlPreviewUndoAndShutdown() throws Exception {
        String oldState = System.getProperty("openbone.userState"), oldPort = System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState", temp.resolve("state").toString()); System.setProperty("openbone.mcp.port", "0");
        EntityId bone = EntityId.create(), slot = EntityId.create(), attachment = EntityId.create(), clip = EntityId.create();
        var texture = new BufferedImage(48, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 48; x++) texture.setRGB(x, y, y < 12 ? 0xff22dd44 : 0xffdd2244);
        Path png = temp.resolve("texture.png"); ImageIO.write(texture, "PNG", png.toFile());
        var model = new DocumentSnapshot(List.of(new Bone(bone, "root", null, Transform.IDENTITY, 80)),
                List.of(new Slot(slot, "body", bone, attachment)),
                List.of(new RegionAttachment(attachment, "body", slot, png.toString(), new Transform(45, 0, 0, 1, 1), 90, 48)),
                List.of(new AnimationClip(clip, "idle", List.of())));
        Path project = temp.resolve("project.json"); Files.write(project, new ProjectCodec().encode(model));
        byte[] original = Files.readAllBytes(project);
        try {
            edt(() -> {
                frame = new EditorFrame(project); frame.setSize(1200, 850); frame.showEditor();
                url = ((EditorMcpServer) field("mcpServer")).url(); token = (String) field("mcpToken"); return null;
            });
            var initialized = rpc("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "desktop-test", "version", "1")));
            assertFalse(initialized.has("error"), initialized.toString());
            var context = call("openbone_get_context", Map.of());
            String before = data(context).path("revision").asText();
            assertEquals(project.toAbsolutePath().toString(), data(context).path("view").path("projectPath").asText());
            var edits = List.of(Map.of("op", "put_keyframe", "animationId", clip.value(), "boneId", bone.value(), "channel", "ROTATE", "time", 0, "values", List.of(0)),
                    Map.of("op", "put_keyframe", "animationId", clip.value(), "boneId", bone.value(), "channel", "ROTATE", "time", 1, "values", List.of(90)));
            var edited = data(call("openbone_apply_edits", Map.of("expectedRevision", before, "requestId", "desktop-edit", "edits", edits)));
            String revision = edited.path("revision").asText(); assertEquals(1, edited.path("undoCount").asInt());
            assertTrue(edited.path("dirty").asBoolean());
            var atZero = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", 0, "expectedRevision", revision));
            var atHalf = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", .5, "expectedRevision", revision));
            var atEnd = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", 1, "expectedRevision", revision));
            var start = image(atZero); var end = image(atEnd);
            assertEquals(revision, data(atHalf).path("revision").asText());
            assertEquals(.5, data(atHalf).path("time").asDouble());
            assertEquals(1, data(call("openbone_get_context", Map.of())).path("view").path("time").asDouble());
            int changed = 0, green = 0, red = 0;
            for (int y = 0; y < start.getHeight(); y++) for (int x = 0; x < start.getWidth(); x++) {
                if (start.getRGB(x, y) != end.getRGB(x, y)) changed++;
                int c = start.getRGB(x, y);
                if (((c >>> 8) & 255) > 150 && ((c >>> 16) & 255) < 80) green++;
                if (((c >>> 16) & 255) > 150 && ((c >>> 8) & 255) < 80) red++;
            }
            assertTrue(changed > 500, "Rendered motion must change the actual viewport pixels");
            assertTrue(green > 100 && red > 100, "Actual attachment texture must be present, not just bones or a blank image");
            var tip = data(atHalf).path("bones").get(0).path("tip");
            assertEquals(80 / Math.sqrt(2), tip.path("x").asDouble(), 1e-6);
            assertEquals(80 / Math.sqrt(2), tip.path("y").asDouble(), 1e-6);
            Path evidence = Path.of("target/mcp-desktop"); Files.createDirectories(evidence);
            ImageIO.write(start, "PNG", evidence.resolve("t0.png").toFile()); ImageIO.write(image(atHalf), "PNG", evidence.resolve("t05.png").toFile()); ImageIO.write(end, "PNG", evidence.resolve("t1.png").toFile());
            if (System.getProperty("openbone.mcp.sdkPython") != null) {
                var process = new ProcessBuilder(System.getProperty("openbone.mcp.sdkPython"), "scripts/mcp_sdk_smoke.py").redirectErrorStream(true);
                process.environment().put("OPENBONE_MCP_TEST_URL", url);
                process.environment().put("OPENBONE_MCP_TEST_TOKEN", token);
                process.environment().put("OPENBONE_MCP_TEST_ALLOW_EDITS", "disposable-fixture");
                process.environment().put("OPENBONE_MCP_SDK_PATH", Path.of(System.getProperty("openbone.mcp.sdkPath", "target/mcp-client-deps")).toAbsolutePath().toString());
                var log = evidence.resolve("sdk-smoke.log"); process.redirectOutput(log.toFile());
                var running = process.start();
                if (!running.waitFor(45, TimeUnit.SECONDS)) { running.destroyForcibly(); fail("Official SDK smoke timed out"); }
                assertEquals(0, running.exitValue(), Files.readString(log));
                assertTrue(Files.readString(log).contains("Official Python MCP SDK smoke passed"));
                revision = data(call("openbone_get_context", Map.of())).path("revision").asText();
            }
            var undone = data(call("openbone_undo", Map.of("expectedRevision", revision, "requestId", "desktop-undo")));
            assertFalse(undone.path("dirty").asBoolean()); assertEquals(0, undone.path("undoCount").asInt());
            var keys = data(call("openbone_query_scene", Map.of("kind", "keyframes", "animationId", clip.value())));
            assertEquals(0, keys.path("total").asInt());
            assertArrayEquals(original, Files.readAllBytes(project), "MCP edits must not silently save the original project");
            assertEquals(model, edt(() -> frame.history().state().model()));
            edt(() -> { invoke("closeRequested"); return null; });
            assertThrows(java.io.IOException.class, () -> rpc("ping", Map.of()), "Closing the editor must stop its HTTP service");
        } finally {
            if (frame != null) edt(() -> {
                if (frame.isDisplayable()) { frame.history().markSaved(); invoke("closeRequested"); }
                return null;
            });
            restore("openbone.userState", oldState); restore("openbone.mcp.port", oldPort);
        }
    }
    void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    Object field(String name) throws Exception { var f = EditorFrame.class.getDeclaredField(name); f.setAccessible(true); return f.get(frame); }
    void invoke(String name) throws Exception { var m = EditorFrame.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(frame); }
    static <T> T edt(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    JsonNode rpc(String method, Object params) throws Exception {
        var body = json.createObjectNode().put("jsonrpc", "2.0").put("id", ++sequence).put("method", method); body.set("params", json.valueToTree(params));
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode()); return json.readTree(response.body());
    }
    JsonNode call(String tool, Object args) throws Exception {
        var response = rpc("tools/call", Map.of("name", tool, "arguments", args)); assertFalse(response.has("error"), response.toString());
        var result = response.path("result"); assertFalse(result.path("isError").asBoolean(), result.toString()); return result;
    }
    JsonNode data(JsonNode result) { return result.path("structuredContent").path("data"); }
    BufferedImage image(JsonNode result) throws Exception {
        var content = result.path("content").get(1); assertEquals("image/png", content.path("mimeType").asText());
        return ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(content.path("data").asText())));
    }
}
