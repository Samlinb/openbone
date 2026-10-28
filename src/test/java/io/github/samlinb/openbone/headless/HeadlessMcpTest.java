package io.github.samlinb.openbone.headless;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessMcpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    HeadlessApplication service;
    EntityId bone = EntityId.create(), animation = EntityId.create();

    @BeforeEach void start() throws Exception {
        var model = new DocumentSnapshot(List.of(new Bone(bone,"root",null,Transform.IDENTITY,60)), List.of(), List.of(),
                List.of(new AnimationClip(animation,"idle",List.of())));
        Path source = temp.resolve("source.json");
        Files.write(source,new ProjectCodec().encode(model));
        service = new HeadlessApplication(source,0,null);
    }
    @AfterEach void stop() { service.close(); }

    JsonNode rpc(String method, Object params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(service.url()))
                .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params))))
                .timeout(java.time.Duration.ofSeconds(15)).build();
        var response = HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode());
        return JSON.readTree(response.body()).path("result");
    }
    JsonNode call(String name,Object args) throws Exception { return rpc("tools/call",Map.of("name",name,"arguments",args)); }
    JsonNode context() throws Exception { return call("openbone_get_context",Map.of()).path("structuredContent").path("data"); }
    Map<String,Object> key(String id,String revision,double time) {
        return Map.of("requestId",id,"expectedRevision",revision,"edits",List.of(Map.of("op","put_keyframe",
                "animationId",animation.value(),"boneId",bone.value(),"channel","ROTATE","time",time,"values",List.of(45))));
    }

    @Test void realHttpEditingKeepsTransactionsRetriesAndHistory() throws Exception {
        JsonNode before = context();
        String revision = before.path("revision").asText();
        var args = key("edit",revision,.5);
        var applied = call("openbone_apply_edits",args);
        assertFalse(applied.path("isError").asBoolean(),applied.toString());
        assertEquals(applied,call("openbone_apply_edits",args));
        var after = context();
        assertEquals(1,after.path("undoCount").asInt());
        assertNotEquals(revision,after.path("revision").asText());
        assertTrue(call("openbone_apply_edits",key("stale",revision,1)).path("isError").asBoolean());
        var undo = Map.of("expectedRevision",after.path("revision").asText(),"requestId","undo");
        var undone = call("openbone_undo",undo);
        assertEquals(undone,call("openbone_undo",undo));
        var restored = context();
        assertEquals(0,restored.path("undoCount").asInt());
        assertEquals(1,restored.path("redoCount").asInt());
        var bad = new LinkedHashMap<String,Object>(key("bad",restored.path("revision").asText(),1));
        bad.put("edits",List.of(((List<?>)bad.get("edits")).get(0),Map.of("op","invalid")));
        assertTrue(call("openbone_apply_edits",bad).path("isError").asBoolean());
        assertEquals(restored,context());
        assertFalse(call("openbone_redo",Map.of("expectedRevision",restored.path("revision").asText(),"requestId","redo")).path("isError").asBoolean());
        var current = context();
        Path saved = temp.resolve("saved.json");
        assertFalse(call("openbone_save_project",Map.of("path",saved.toString(),"expectedRevision",current.path("revision").asText(),"requestId","save")).path("isError").asBoolean());
        assertEquals(1,new ProjectCodec().read(saved).animation(animation).timelines().get(0).keys().size());
        var export = call("openbone_export_spine",Map.of("directory",temp.resolve("export").toString(),"version","4.2.22","format","JSON",
                "expectedRevision",context().path("revision").asText(),"requestId","export"));
        assertFalse(export.path("isError").asBoolean(),export.toString());
        assertTrue(Files.isRegularFile(ExportPackage.resolve(temp.resolve("export/current.json"))));
    }

    @Test void advertisesOnlyAvailableToolsAndFailedLoadPreservesState() throws Exception {
        var before = context();
        assertEquals("headless",before.path("view").path("hostMode").asText());
        assertFalse(before.path("capabilities").path("cleanPreview").asBoolean());
        assertFalse(before.path("capabilities").path("playback").asBoolean());
        for (var tool : rpc("tools/list",Map.of()).path("tools"))
            assertFalse(Set.of("openbone_capture_preview","openbone_render_animation","openbone_control_view").contains(tool.path("name").asText()));
        var failure = call("openbone_load_project",Map.of("path",temp.resolve("missing.json").toString(),"dirtyPolicy","discard",
                "expectedRevision",before.path("revision").asText(),"requestId","load"));
        assertTrue(failure.path("isError").asBoolean());
        assertEquals(before,context());
        assertFalse(call("openbone_load_project",Map.of("path",temp.resolve("source.json").toString(),"dirtyPolicy","reject",
                "expectedRevision",before.path("revision").asText(),"requestId","load-valid")).path("isError").asBoolean());
        assertNotEquals(before.path("revision"),context().path("revision"));
    }

    @Test void closeReleasesPortAndOccupiedPortFails() throws Exception {
        int port = URI.create(service.url()).getPort();
        assertThrows(java.net.BindException.class,()->new HeadlessApplication(null,port,null));
        service.close();
        service.close();
        try (var next = new HeadlessApplication(null,port,null)) { assertEquals(port,URI.create(next.url()).getPort()); }
    }
}
