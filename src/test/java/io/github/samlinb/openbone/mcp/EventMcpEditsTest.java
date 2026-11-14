package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.samlinb.openbone.core.animation.EventSampler;
import io.github.samlinb.openbone.core.model.EntityId;
import io.github.samlinb.openbone.io.ProjectCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EventMcpEditsTest {
    @TempDir Path temp;
    EditorMcpServerTest protocol;
    final EntityId clip = EntityId.create(), event = EntityId.create();

    @BeforeEach void start() throws Exception {
        protocol = new EditorMcpServerTest(); protocol.temp = temp; protocol.start();
    }
    @AfterEach void stop() { if (protocol != null && protocol.server != null) protocol.close(); }

    Map<String, Object> definition() {
        return Map.of("op", "put_event_definition", "eventId", event.value(), "name", "hit",
                "intValue", 7, "floatValue", 2.5, "stringValue", "wave", "volume", 1., "balance", 0.);
    }
    Map<String, Object> key(EntityId id, double time) {
        return Map.of("op", "put_event_keyframe", "animationId", clip.value(), "eventId", event.value(),
                "keyId", id.value(), "time", time);
    }
    JsonNode apply(String request, List<?> edits) throws Exception {
        return protocol.ok("openbone_apply_edits", Map.of("requestId", request,
                "expectedRevision", protocol.revision(), "edits", edits));
    }
    void create() throws Exception {
        apply("create", List.of(Map.of("op", "create_animation", "animationId", clip.value(), "name", "field"), definition()));
    }
    JsonNode keys(String type) throws Exception {
        return protocol.ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", clip.value(),
                "trackType", type, "limit", 200, "expectedRevision", protocol.revision())).path("items");
    }

    @Test void authoredHitEventsRoundTripThroughHttpHistoryNativeProjectAndSpine38() throws Exception {
        var before = protocol.ok("openbone_get_context", Map.of());
        var ids = List.of(EntityId.create(), EntityId.create(), EntityId.create());
        var edits = List.of(Map.of("op", "create_animation", "animationId", clip.value(), "name", "field"),
                definition(), key(ids.get(0), .2667), key(ids.get(1), 1.3333), key(ids.get(2), 2.4));
        var args = Map.of("requestId", "three-hits", "expectedRevision", protocol.revision(), "edits", edits);
        var after = protocol.ok("openbone_apply_edits", args);
        assertEquals(after, protocol.ok("openbone_apply_edits", args));
        assertEquals(1, after.path("undoCount").asInt());
        assertTrue(after.path("capabilities").path("eventKeyframeEdits").asBoolean());
        var expected = keys("event"); assertEquals(3, expected.size());
        assertEquals("hit", expected.get(0).path("targetName").asText());
        assertEquals(7, expected.get(0).path("intValue").asInt());
        var definitions = protocol.ok("openbone_query_scene", Map.of("kind", "events", "limit", 1));
        assertEquals(event.value(), definitions.path("items").get(0).path("id").asText());
        assertFalse(definitions.path("hasMore").asBoolean());
        assertTrue(protocol.ok("openbone_query_scene", Map.of("kind", "events", "offset", 9)).path("items").isEmpty());

        protocol.ok("openbone_undo", Map.of("requestId", "undo", "expectedRevision", protocol.revision()));
        assertEquals(before.path("counts"), protocol.ok("openbone_get_context", Map.of()).path("counts"));
        protocol.ok("openbone_redo", Map.of("requestId", "redo", "expectedRevision", protocol.revision()));
        assertEquals(expected, keys("event"));
        assertTrue(protocol.call("openbone_apply_edits", Map.of("requestId", "stale", "expectedRevision",
                before.path("revision").asText(), "edits", edits)).path("isError").asBoolean());

        Path project = temp.resolve("events.json");
        protocol.ok("openbone_save_project", Map.of("path", project.toString(), "portable", true,
                "requestId", "save", "expectedRevision", protocol.revision()));
        protocol.ok("openbone_load_project", Map.of("path", project.toString(), "dirtyPolicy", "reject",
                "requestId", "load", "expectedRevision", protocol.revision()));
        assertEquals(expected, keys("event"));
        var model = new ProjectCodec().read(project);
        assertEquals(3, EventSampler.between(model.animation(clip), 0, 3.6, false, false).size());
        assertTrue(EventSampler.between(model.animation(clip), 3.6, 3.6, false, false).isEmpty());
        var exported = protocol.ok("openbone_export_spine", Map.of("directory", temp.resolve("runtime").toString(),
                "version", "3.8.99", "format", "JSON", "requestId", "export", "expectedRevision", protocol.revision()));
        var runtime = protocol.JSON.readTree(Files.readAllBytes(Path.of(exported.path("path").asText())));
        assertEquals("3.8.99", runtime.path("skeleton").path("spine").asText());
        assertTrue(runtime.path("events").has("hit"));
        var emitted = runtime.path("animations").path("field").path("events");
        assertEquals(3, emitted.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(.2667, 1.3333, 2.4).get(i), emitted.get(i).path("time").asDouble(), 1e-6);
            assertEquals("hit", emitted.get(i).path("name").asText());
        }
    }

    @Test void equalTimeEventsHaveExplicitIdentityStableOrderAndIndependentPayload() throws Exception {
        create(); var first = EntityId.create(); var second = EntityId.create();
        var a = new LinkedHashMap<>(key(first, .5)); a.put("intValue", 11);
        var b = new LinkedHashMap<>(key(second, .5)); b.put("intValue", 22);
        apply("two-at-same-time", List.of(a, b));
        var update = new LinkedHashMap<>(key(first, .5)); update.put("intValue", 33);
        apply("replace-first", List.of(update));
        var rows = keys("event"); assertEquals(2, rows.size());
        assertEquals(first.value(), rows.get(0).path("id").asText());
        assertEquals(33, rows.get(0).path("intValue").asInt());
        assertEquals(22, rows.get(1).path("intValue").asInt());
        var defaults = Map.of("op", "put_event_definition", "eventId", event.value(), "name", "impact", "intValue", 99);
        apply("change-defaults", List.of(defaults, key(first, .5), key(EntityId.create(), .7)));
        rows = keys("event"); assertEquals(33, rows.get(0).path("intValue").asInt());
        assertEquals(99, rows.get(2).path("intValue").asInt());
        assertEquals("wave", rows.get(2).path("stringValue").asText());
        assertEquals(1., rows.get(2).path("volume").asDouble());
        apply("canonical-zero", List.of(key(first, -0.0)));
        assertEquals(0., keys("event").get(0).path("time").asDouble());
    }

    @Test void invalidBatchPreservesRevisionRedoAndRejectsCoercionAndForeignIds() throws Exception {
        create(); var id = EntityId.create(); apply("one", List.of(key(id, .2)));
        protocol.ok("openbone_undo", Map.of("requestId", "undo-one", "expectedRevision", protocol.revision()));
        var before = protocol.ok("openbone_get_context", Map.of());
        var invalid = new ArrayList<Map<String, Object>>();
        for (var item : List.of(Map.entry("volume", 2), Map.entry("volume", .8), Map.entry("balance", -.2), Map.entry("balance", -2), Map.entry("intValue", 1.2),
                Map.entry("intValue", 2147483648L), Map.entry("floatValue", "NaN"), Map.entry("time", -1),
                Map.entry("eventId", EntityId.create().value()), Map.entry("keyId", event.value()),
                Map.entry("curve", "linear"), Map.entry("stringValue", "x".repeat(4097)))) {
            var bad = new LinkedHashMap<String, Object>(key(EntityId.create(), .3)); bad.put(item.getKey(), item.getValue()); invalid.add(bad);
        }
        var missing = new LinkedHashMap<>(key(EntityId.create(), .3)); missing.remove("keyId"); invalid.add(missing);
        for (int i = 0; i < invalid.size(); i++) {
            var result = protocol.call("openbone_apply_edits", Map.of("requestId", "invalid-" + i,
                    "expectedRevision", protocol.revision(), "edits", List.of(key(EntityId.create(), .1), invalid.get(i))));
            assertTrue(result.path("isError").asBoolean(), result.toString());
            assertEquals(before, protocol.ok("openbone_get_context", Map.of()));
            assertEquals(0, keys("event").size());
        }
        protocol.ok("openbone_redo", Map.of("requestId", "redo-one", "expectedRevision", protocol.revision()));
        assertEquals(id.value(), keys("event").get(0).path("id").asText());
    }

    @Test void mixedKeyRemovalIsAtomicUndoableAndSchemaIsLocalized() throws Exception {
        create(); var id = EntityId.create();
        apply("mixed", List.of(key(id, .2), Map.of("op", "put_keyframe", "animationId", clip.value(),
                "boneId", EditorMcpServerTest.BONE.value(), "channel", "ROTATE", "time", .2, "values", List.of(10))));
        var all = keys("all"); assertEquals(2, all.size());
        var ids = new ArrayList<String>(); all.forEach(k -> ids.add(k.path("id").asText()));
        var before = protocol.ok("openbone_get_context", Map.of());
        var badIds = new ArrayList<>(ids); badIds.add(EntityId.create().value());
        assertTrue(protocol.call("openbone_apply_edits", Map.of("requestId", "remove-invalid", "expectedRevision", protocol.revision(),
                "edits", List.of(Map.of("op", "remove_keyframes", "animationId", clip.value(), "keyIds", badIds)))).path("isError").asBoolean());
        assertEquals(before, protocol.ok("openbone_get_context", Map.of()));
        apply("remove-mixed", List.of(Map.of("op", "remove_keyframes", "animationId", clip.value(), "keyIds", ids)));
        assertEquals(0, keys("all").size());
        protocol.ok("openbone_undo", Map.of("requestId", "undo-remove", "expectedRevision", protocol.revision()));
        assertEquals(all, keys("all"));
        var definitions = protocol.rpc("tools/list", Map.of()).path("result").path("tools");
        boolean eventOps = false, eventsQuery = false;
        for (var t : definitions) {
            if (t.path("name").asText().equals("openbone_apply_edits"))
                eventOps = t.path("inputSchema").toString().contains("put_event_definition") && t.path("inputSchema").toString().contains("put_event_keyframe");
            if (t.path("name").asText().equals("openbone_query_scene")) eventsQuery = t.path("inputSchema").toString().contains("events");
        }
        assertTrue(eventOps); assertTrue(eventsQuery);
        var base = ResourceBundle.getBundle("i18n.mcp_event_edits", Locale.ENGLISH);
        for (var locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            var localized = ResourceBundle.getBundle("i18n.mcp_event_edits", locale);
            assertEquals(base.keySet(), localized.keySet());
            assertNotEquals(base.getString("description"), localized.getString("description"));
        }
    }
}
