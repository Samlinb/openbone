package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.EntityId;
import java.nio.file.Path;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMcpEditsTest {
    @TempDir Path temp;
    EditorMcpServerTest protocol;
    AnimationMcpQueryTest fixture;
    final EntityId created = EntityId.create();
    final Map<String, Object> bezier = Map.of("kind", "bezier", "x1", .25, "y1", .1, "x2", .75, "y2", .9);

    @BeforeEach void start() throws Exception {
        fixture = new AnimationMcpQueryTest();
        protocol = new EditorMcpServerTest(); protocol.temp = temp;
        SwingUtilities.invokeAndWait(() -> protocol.host.installProject(new ProjectDocument(fixture.fixture())));
        protocol.start();
    }
    @AfterEach void stop() { if (protocol != null && protocol.server != null) protocol.close(); }

    private Map<String, Object> deform(double time, double value) {
        return Map.of("op", "put_deform_keyframe", "animationId", created.value(), "attachmentId", fixture.path.value(),
                "time", time, "values", Collections.nCopies(12, value), "curve", bezier);
    }
    private Map<String, Object> constraint(double time, double mix) {
        return Map.of("op", "put_constraint_keyframe", "animationId", created.value(), "constraintId", fixture.constraint.value(),
                "channel", "IK", "time", time, "values", List.of(mix, 0., 1., 0., 0.), "curve", bezier);
    }
    private Map<String, Object> create() { return Map.of("op", "create_animation", "animationId", created.value(), "name", "authored"); }
    private JsonNode keys(String type) throws Exception {
        return protocol.ok("openbone_query_scene", Map.of("kind", "keyframes", "animationId", created.value(),
                "trackType", type, "expectedRevision", protocol.revision(), "limit", 200));
    }
    private JsonNode apply(String request, List<?> edits) throws Exception {
        return protocol.ok("openbone_apply_edits", Map.of("requestId", request, "expectedRevision", protocol.revision(), "edits", edits));
    }

    @Test void mixedBatchIsOneUndoStepWithExactRetryAndHeterogeneousRemoval() throws Exception {
        var before = protocol.ok("openbone_get_context", Map.of());
        var edits = List.of(create(), constraint(0, 0), constraint(1, 1), deform(0, 0), deform(1, 4));
        var args = Map.of("requestId", "author", "expectedRevision", protocol.revision(), "edits", edits);
        var after = protocol.ok("openbone_apply_edits", args);
        assertEquals(after, protocol.ok("openbone_apply_edits", args));
        assertEquals(1, after.path("undoCount").asInt());
        assertEquals(4, keys("all").path("total").asInt());
        var expected = keys("all");
        assertEquals(12, keys("deform").path("items").get(0).path("values").size());
        var ik = keys("constraint").path("items").get(0);
        assertEquals("BEZIER", ik.path("curves").get(0).path("kind").asText());
        assertEquals("STEPPED", ik.path("curves").get(2).path("kind").asText());
        var undoArgs = Map.of("requestId", "author-undo", "expectedRevision", protocol.revision());
        var undone = protocol.ok("openbone_undo", undoArgs);
        assertEquals(undone, protocol.ok("openbone_undo", undoArgs));
        assertEquals(before.path("counts"), undone.path("counts"));
        var redone = protocol.ok("openbone_redo", Map.of("requestId", "author-redo", "expectedRevision", protocol.revision()));
        assertEquals(1, redone.path("undoCount").asInt());
        assertEquals(expected.path("items"), keys("all").path("items"));
        var ids = new ArrayList<String>();
        keys("all").path("items").forEach(k -> ids.add(k.path("id").asText()));
        apply("remove-all", List.of(Map.of("op", "remove_keyframes", "animationId", created.value(), "keyIds", ids)));
        assertEquals(0, keys("all").path("total").asInt());
        assertTrue(protocol.call("openbone_apply_edits", Map.of("requestId", "stale", "expectedRevision", before.path("revision").asText(), "edits", edits)).path("isError").asBoolean());
    }

    @Test void replacementPreservesIdAndOmittedCurvesAndFailedBatchPreservesRedo() throws Exception {
        apply("initial", List.of(create(), deform(0, 0), constraint(0, 0)));
        var initialDeform = keys("deform").path("items").get(0);
        var initialConstraint = keys("constraint").path("items").get(0);
        var replaceDeform = new LinkedHashMap<>(deform(0, 2)); replaceDeform.remove("curve");
        var replaceConstraint = new LinkedHashMap<>(constraint(0, .5)); replaceConstraint.remove("curve");
        apply("replace", List.of(replaceDeform, replaceConstraint));
        var replaced = keys("deform").path("items").get(0);
        assertEquals(initialDeform.path("id"), replaced.path("id"));
        assertEquals(initialDeform.path("curves"), replaced.path("curves"));
        assertEquals(initialConstraint.path("curves"), keys("constraint").path("items").get(0).path("curves"));
        protocol.ok("openbone_undo", Map.of("requestId", "replace-undo", "expectedRevision", protocol.revision()));
        var before = protocol.ok("openbone_get_context", Map.of());
        var badLength = new LinkedHashMap<>(deform(1, 2)); badLength.put("values", List.of(0., 0.));
        var badFlag = new LinkedHashMap<>(constraint(1, .5)); badFlag.put("values", List.of(.5, 0., .5, 0., 0.));
        var badCurve = new LinkedHashMap<>(deform(1, 2)); badCurve.put("curve", Map.of("kind", "bezier", "x1", 2., "x2", .7, "y1", 0., "y2", 1.));
        var extra = new LinkedHashMap<>(deform(1, 2)); extra.put("world", true);
        var wrongChannel = new LinkedHashMap<>(constraint(1, .5)); wrongChannel.put("channel", "PATH_MIX");
        var badTime = new LinkedHashMap<>(deform(1, 2)); badTime.put("time", -1.);
        for (var bad : List.of(badLength, badFlag, badCurve, extra, wrongChannel, badTime)) {
            var result = protocol.call("openbone_apply_edits", Map.of("requestId", "invalid-" + bad.hashCode(),
                    "expectedRevision", protocol.revision(), "edits", List.of(deform(.25, 1), bad)));
            assertTrue(result.path("isError").asBoolean(), bad.toString());
            assertEquals(before, protocol.ok("openbone_get_context", Map.of()));
            assertEquals(1, keys("deform").path("total").asInt());
        }
        protocol.ok("openbone_redo", Map.of("requestId", "replace-redo", "expectedRevision", protocol.revision()));
        assertEquals(2., keys("deform").path("items").get(0).path("values").get(0).asDouble());
    }

    @Test void portableSaveRoundtripPreservesAuthoredNonBoneKeysAndDescriptionsAreLocalized() throws Exception {
        apply("initial", List.of(create(), deform(0, 0), deform(1, 3), constraint(0, 0)));
        var expected = keys("all").path("items");
        String path = temp.resolve("authored.json").toString();
        protocol.ok("openbone_save_project", Map.of("path", path, "expectedRevision", protocol.revision(), "requestId", "save"));
        protocol.ok("openbone_load_project", Map.of("path", path, "dirtyPolicy", "reject", "expectedRevision", protocol.revision(), "requestId", "load"));
        assertEquals(expected, keys("all").path("items"));
        var base = ResourceBundle.getBundle("i18n.mcp_animation_edits", Locale.ENGLISH);
        for (var locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            var localized = ResourceBundle.getBundle("i18n.mcp_animation_edits", locale);
            assertEquals(base.keySet(), localized.keySet());
            assertNotEquals(base.getString("description"), localized.getString("description"));
        }
        assertTrue(protocol.ok("openbone_get_context", Map.of()).path("capabilities").path("nonBoneKeyframeEdits").asBoolean());
    }
}
