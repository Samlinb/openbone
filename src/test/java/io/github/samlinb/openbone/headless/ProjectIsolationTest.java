package io.github.samlinb.openbone.headless;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.io.ProjectCodec;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIsolationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String A = "a".repeat(40), B = "b".repeat(40);
    @TempDir Path temp;
    Path alice, bob;
    IsolatedHeadlessApplication service;
    EntityId bone = EntityId.create(), animation = EntityId.create();
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        alice = Files.createDirectory(temp.resolve("alice"));
        bob = Files.createDirectory(temp.resolve("bob"));
        var model = new DocumentSnapshot(List.of(new Bone(bone,"root",null,Transform.IDENTITY,60)), List.of(), List.of(),
                List.of(new AnimationClip(animation,"idle",List.of())));
        for (Path root : List.of(alice,bob)) Files.write(root.resolve("project.json"), new ProjectCodec().encode(model));
        service = new IsolatedHeadlessApplication(projects(), 0, null);
    }
    List<IsolatedHeadlessApplication.Project> projects() {
        return List.of(new IsolatedHeadlessApplication.Project("alice",alice,alice.resolve("project.json"),A),
                new IsolatedHeadlessApplication.Project("bob",bob,bob.resolve("project.json"),B));
    }
    @AfterEach void stop() { service.close(); }

    HttpResponse<String> rpc(String project, String token, String method, Object params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(service.url(project)))
                .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                .header("Authorization","Bearer " + token).timeout(java.time.Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    JsonNode call(String project, String token, String name, Object args) throws Exception {
        var response = rpc(project,token,"tools/call",Map.of("name",name,"arguments",args));
        assertEquals(200,response.statusCode(),response.body());
        return JSON.readTree(response.body()).path("result");
    }
    JsonNode context(String project, String token) throws Exception {
        return call(project,token,"openbone_get_context",Map.of()).path("structuredContent").path("data");
    }
    Map<String,Object> edit(String revision, double value) {
        return Map.of("requestId","same-id","expectedRevision",revision,"edits",List.of(Map.of("op","put_keyframe",
                "animationId",animation.value(),"boneId",bone.value(),"channel","ROTATE","time",.5,"values",List.of(value))));
    }
    @Test void credentialsBindEveryMethodToOneProject() throws Exception {
        for (String method : List.of("initialize","ping","tools/list","tools/call")) {
            assertEquals(401,rpc("bob",A,method,Map.of()).statusCode());
            assertEquals(401,rpc("alice",B,method,Map.of()).statusCode());
        }
        assertEquals(404,rpc("missing",A,"tools/list",Map.of()).statusCode());
        assertEquals(200,rpc("alice",A,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of())).statusCode());
        assertEquals("alice",context("alice",A).path("view").path("projectId").asText());
        assertEquals("bob",context("bob",B).path("view").path("projectId").asText());
    }
    @Test void concurrentEditsRetriesRevisionsUndoAndFailuresAreIndependent() throws Exception {
        var beforeA = context("alice",A); var beforeB = context("bob",B);
        var argsA = edit(beforeA.path("revision").asText(),30);
        var argsB = edit(beforeB.path("revision").asText(),60);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> call("alice",A,"openbone_apply_edits",argsA));
            var second = pool.submit(() -> call("bob",B,"openbone_apply_edits",argsB));
            for (var result : List.of(first.get(),second.get())) assertFalse(result.path("isError").asBoolean(),result.toString());
            assertEquals(first.get(),call("alice",A,"openbone_apply_edits",argsA));
            assertEquals(second.get(),call("bob",B,"openbone_apply_edits",argsB));
        } finally { pool.shutdownNow(); }
        var afterA = context("alice",A); var afterB = context("bob",B);
        assertEquals(1,afterA.path("undoCount").asInt()); assertEquals(1,afterB.path("undoCount").asInt());
        assertTrue(call("alice",A,"openbone_undo",Map.of("requestId","cross","expectedRevision",afterB.path("revision").asText())).path("isError").asBoolean());
        var undo = Map.of("requestId","undo","expectedRevision",afterA.path("revision").asText());
        var undone = call("alice",A,"openbone_undo",undo);
        assertEquals(undone,call("alice",A,"openbone_undo",undo));
        assertEquals(afterB,context("bob",B));
        assertEquals(1,context("alice",A).path("redoCount").asInt());
        var bad = new LinkedHashMap<String,Object>(edit(context("alice",A).path("revision").asText(),90));
        bad.put("requestId","bad"); bad.put("edits",List.of(Map.of("op","invalid")));
        assertTrue(call("alice",A,"openbone_apply_edits",bad).path("isError").asBoolean());
        assertEquals(1,context("alice",A).path("redoCount").asInt());
    }
    @Test void fileOperationsCannotCrossWorkspaceAndValidSaveLoadExportWork() throws Exception {
        var before = context("alice",A); String revision = before.path("revision").asText();
        for (String name : List.of("openbone_load_project","openbone_save_project")) {
            var args = new LinkedHashMap<String,Object>(Map.of("path",bob.resolve("project.json").toString(),"requestId",name,"expectedRevision",revision));
            if (name.contains("load")) args.put("dirtyPolicy","discard");
            assertTrue(call("alice",A,name,args).path("isError").asBoolean());
        }
        assertTrue(call("alice",A,"openbone_export_spine",Map.of("directory",bob.toString(),"format","JSON","version","4.2.22","requestId","export","expectedRevision",revision)).path("isError").asBoolean());
        assertEquals(before,context("alice",A));
        Path saved = alice.resolve("saved.json");
        assertFalse(call("alice",A,"openbone_save_project",Map.of("path",saved.toString(),"requestId","save","expectedRevision",revision)).path("isError").asBoolean());
        assertTrue(Files.isRegularFile(saved));
        assertFalse(call("alice",A,"openbone_load_project",Map.of("path",saved.toString(),"dirtyPolicy","discard","requestId","load","expectedRevision",revision)).path("isError").asBoolean());
        assertFalse(call("alice",A,"openbone_export_spine",Map.of("directory",alice.resolve("out").toString(),"format","JSON","version","4.2.22","requestId","export-ok","expectedRevision",context("alice",A).path("revision").asText())).path("isError").asBoolean());
        assertTrue(Files.exists(alice.resolve("out/current.json")));
    }
    @Test void attachmentReadsAreCheckedBeforeMutation() throws Exception {
        var before = context("alice",A);
        assertTrue(call("alice",A,"openbone_apply_edits",Map.of("requestId","image","expectedRevision",before.path("revision").asText(),
                "edits",List.of(Map.of("op","update_region","attachmentId",EntityId.create().value(),"pngPath",bob.resolve("secret.png").toString())))).toString().contains("Project access denied"));
        assertEquals(before,context("alice",A));
        EntityId slot=EntityId.create(), attachment=EntityId.create();
        var malicious=new DocumentSnapshot(List.of(new Bone(bone,"root",null,Transform.IDENTITY,60)),
                List.of(new Slot(slot,"body",bone,attachment)),
                List.of(new RegionAttachment(attachment,"body",slot,bob.resolve("secret.png").toString(),Transform.IDENTITY,16,16)),
                List.of(new AnimationClip(animation,"idle",List.of())));
        Path source=alice.resolve("outside-resource.json");Files.write(source,new ProjectCodec().encode(malicious));
        assertTrue(call("alice",A,"openbone_load_project",Map.of("path",source.toString(),"dirtyPolicy","discard",
                "requestId","malicious-load","expectedRevision",before.path("revision").asText())).toString().contains("Project access denied"));
        assertEquals(before,context("alice",A));
    }
    @Test void linksAndTraversalCannotEscape() throws Exception {
        var scope = new ProjectScope("alice",alice);
        assertThrows(java.io.IOException.class,() -> scope.check(alice.resolve("../bob/new/file.json")));
        Path link = alice.resolve("linked");
        try { Files.createSymbolicLink(link,bob); }
        catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("Symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
        assertThrows(java.io.IOException.class,() -> scope.check(link.resolve("new/file.json")));
    }
    @Test void invalidConfigurationAndCloseReleaseAllResources() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new IsolatedHeadlessApplication(List.of(projects().get(0),projects().get(0)),0,null));
        assertThrows(IllegalArgumentException.class,() -> new IsolatedHeadlessApplication(List.of(projects().get(0),new IsolatedHeadlessApplication.Project("other",alice,null,B)),0,null));
        assertThrows(IllegalArgumentException.class,() -> new IsolatedHeadlessApplication(List.of(new IsolatedHeadlessApplication.Project("empty",alice,null,null)),0,null));
        int port = URI.create(service.url()).getPort();
        assertThrows(java.net.BindException.class,() -> new IsolatedHeadlessApplication(projects(),port,null));
        service.close(); service.close();
        try (var next = new IsolatedHeadlessApplication(projects(),port,null)) { assertEquals(port,URI.create(next.url()).getPort()); }
    }

    @Test void configurationRejectsUnknownFieldsDuplicateKeysAndMissingCredentials() throws Exception {
        Path config = temp.resolve("config.json");
        Files.writeString(config,"{\"projects\":[],\"extra\":true}");
        assertThrows(IllegalArgumentException.class,() -> IsolatedHeadlessApplication.readConfig(config));
        Files.writeString(config,"{\"projects\":[],\"projects\":[]}");
        assertThrows(java.io.IOException.class,() -> IsolatedHeadlessApplication.readConfig(config));
        JSON.writeValue(config.toFile(),Map.of("projects",List.of(Map.of("id","alice","root",alice.toString(),"tokenEnv","OPENBONE_TEST_MISSING_75AA"))));
        assertThrows(IllegalArgumentException.class,() -> new IsolatedHeadlessApplication(IsolatedHeadlessApplication.readConfig(config),0,null));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="openbone.verifyRuntime",matches="true")
    void actualGlPreviewsHaveIndependentWorkersImagesAndHistory() throws Exception {
        service.close();
        EntityId slot=EntityId.create(), attachment=EntityId.create();
        int color=0xffff8030;
        for (Path root : List.of(alice,bob)) {
            var pixels=new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<16;y++) for(int x=0;x<16;x++) pixels.setRGB(x,y,color);
            Path texture=root.resolve("texture.png");javax.imageio.ImageIO.write(pixels,"PNG",texture.toFile());
            var model=new DocumentSnapshot(List.of(new Bone(bone,"root",null,Transform.IDENTITY,60)),
                    List.of(new Slot(slot,"body",bone,attachment)),
                    List.of(new RegionAttachment(attachment,"body",slot,texture.toString(),Transform.IDENTITY,160,60)),
                    List.of(new AnimationClip(animation,"idle",List.of())));
            Files.write(root.resolve("project.json"),new ProjectCodec().encode(model));
            color=0xff40b0e0;
        }
        Path runtime=Path.of(System.getProperty("openbone.runtimeJar","runtime-preview/target/openbone-runtime-preview.jar")).toAbsolutePath();
        service=new IsolatedHeadlessApplication(projects(),0,runtime);
        var beforeA=context("alice",A);var beforeB=context("bob",B);
        var imageA=preview("alice",A,beforeA);var imageB=preview("bob",B,beforeB);
        var receiptA=imageA.path("structuredContent").path("data");var receiptB=imageB.path("structuredContent").path("data");
        long pidA=receiptA.path("runtimePid").asLong(),pidB=receiptB.path("runtimePid").asLong();
        assertTrue(pidA>0);assertTrue(pidB>0);assertNotEquals(pidA,pidB);
        assertFalse(receiptA.path("glRenderer").asText().isBlank());
        assertNotEquals(receiptA.path("imageSHA256"),receiptB.path("imageSHA256"));
        assertEquals(beforeA.path("revision"),receiptA.path("revision"));
        assertEquals(beforeB.path("revision"),receiptB.path("revision"));
        assertFalse(call("alice",A,"openbone_apply_edits",edit(beforeA.path("revision").asText(),45)).path("isError").asBoolean());
        assertEquals(beforeB,context("bob",B));
        assertEquals(receiptB.path("imageSHA256"),preview("bob",B,beforeB).path("structuredContent").path("data").path("imageSHA256"));
        assertFalse(call("alice",A,"openbone_undo",Map.of("expectedRevision",context("alice",A).path("revision").asText(),"requestId","undo")).path("isError").asBoolean());
        assertEquals(receiptA.path("imageSHA256"),preview("alice",A,context("alice",A)).path("structuredContent").path("data").path("imageSHA256"));
        Path evidence=Path.of("target/project-isolation-evidence").toAbsolutePath();Files.createDirectories(evidence);
        for (var entry : Map.of("alice",imageA,"bob",imageB).entrySet()) {
            for (var content : entry.getValue().path("content")) if(content.path("type").asText().equals("image"))
                Files.write(evidence.resolve(entry.getKey()+".png"),Base64.getDecoder().decode(content.path("data").asText()));
            JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(entry.getKey()+"-receipt.json").toFile(),entry.getValue().path("structuredContent"));
        }
        service.close();
        for(long pid : new long[]{pidA,pidB}) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    JsonNode preview(String project,String token,JsonNode context) throws Exception {
        var result=call(project,token,"openbone_capture_preview",Map.of("animationId",animation.value(),"time",0,
                "expectedRevision",context.path("revision").asText(),"fit",true));
        assertFalse(result.path("isError").asBoolean(),result.toString());return result;
    }
}
