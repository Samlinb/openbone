package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMcpQueryTest {
    @TempDir Path temp;
    EditorMcpServerTest protocol;
    final EntityId bone = EntityId.create(), target = EntityId.create(), slot = EntityId.create(),
            path = EntityId.create(), constraint = EntityId.create(), event = EntityId.create(), clip = EntityId.create();

    @BeforeEach void start() throws Exception {
        protocol = new EditorMcpServerTest();
        protocol.temp = temp;
        SwingUtilities.invokeAndWait(() -> protocol.host.installProject(new ProjectDocument(fixture())));
        protocol.start();
    }

    @AfterEach void stop() { if (protocol != null && protocol.server != null) protocol.close(); }

    private Keyframe key(double time, Double... values) {
        return new Keyframe(EntityId.create(), time, List.of(values), Collections.nCopies(values.length, Curve.LINEAR));
    }

    DocumentSnapshot fixture() {
        var points = List.of(new Vec2(-10, 0), new Vec2(0, 0), new Vec2(3, 0),
                new Vec2(7, 0), new Vec2(10, 0), new Vec2(20, 0));
        var weights = points.stream().map(p -> List.of(new Influence(bone, p, 1))).toList();
        var attachment = new PathAttachment(path, "curve", slot, Collections.nCopies(6, new Vec2(0, 0)),
                false, true, List.of(10., 20.), weights);
        var ik = new IkConstraint(constraint, "arm-ik", 0, true, List.of(bone), target,
                1, 0, true, false, false, false);
        var ikKeys = List.of(new Keyframe(EntityId.create(), 0, List.of(1., 0., 1., 0., 0.),
                List.of(Curve.LINEAR, Curve.LINEAR, Curve.STEPPED, Curve.STEPPED, Curve.STEPPED)),
                new Keyframe(EntityId.create(), 1, List.of(0., 0., 0., 0., 0.),
                        List.of(Curve.LINEAR, Curve.LINEAR, Curve.STEPPED, Curve.STEPPED, Curve.STEPPED)));
        var discrete = new DiscreteTracks(
                List.of(new DiscreteTracks.AttachmentKey(EntityId.create(), 0, slot, "curve"),
                        new DiscreteTracks.AttachmentKey(EntityId.create(), 1, slot, null)),
                List.of(new DiscreteTracks.OrderKey(EntityId.create(), .5, List.of(slot))),
                List.of(new DiscreteTracks.EventKey(EntityId.create(), .5, event, 7, 2.5, "step", .8, -.2)),
                List.of(new SlotColorTimeline(slot, SlotColorTimeline.Channel.RGBA,
                        List.of(key(0, 1., 1., 1., 1.), key(1, .2, .3, .4, .5)))));
        var animation = new AnimationClip(clip, "all-tracks",
                List.of(new BoneTimeline(bone, Channel.ROTATE, List.of(key(0, 15.)))),
                List.of(new DeformTimeline(path, List.of(key(0, Collections.nCopies(12, 0.).toArray(Double[]::new)),
                        key(1, Collections.nCopies(12, 5.).toArray(Double[]::new))))),
                List.of(new ConstraintTimeline(constraint, ConstraintChannel.IK, ikKeys)), discrete);
        return new DocumentSnapshot(List.of(new Bone(bone, "root", null, Transform.IDENTITY, 10),
                new Bone(target, "target", null, new Transform(10, 0, 0, 1, 1), 0)),
                List.of(new Slot(slot, "shape", bone, path)), List.of(attachment), List.of(animation), List.of(ik),
                new Resources(List.of(), "default", List.of(new EventDefinition(event, "footstep", 0, 0, "", "", 1, 0)), Map.of()));
    }

    private JsonNode query(Map<String, Object> filters) throws Exception {
        var args = new LinkedHashMap<String, Object>(Map.of("kind", "keyframes", "animationId", clip.value(),
                "expectedRevision", protocol.revision()));
        args.putAll(filters);
        return protocol.ok("openbone_query_scene", args);
    }

    @Test void allFamiliesHaveCompleteStablePaginationAndPreserveLegacyDefault() throws Exception {
        assertEquals(1, query(Map.of()).path("total").asInt());
        var all = query(Map.of("trackType", "all", "limit", 200));
        assertEquals(11, all.path("total").asInt());
        var items = new ArrayList<JsonNode>();
        for (int offset = 0; offset < 11;) {
            var page = query(Map.of("trackType", "all", "limit", 3, "offset", offset));
            page.path("items").forEach(items::add);
            offset = page.path("nextOffset").asInt();
            assertEquals(offset < 11, page.path("hasMore").asBoolean());
        }
        assertEquals(all.path("items"), protocol.JSON.valueToTree(items));
        assertEquals(11, items.stream().map(k -> k.path("id").asText()).distinct().count());
        assertEquals(Set.of("bone", "deform", "constraint", "slot_color", "attachment", "draw_order", "event"),
                items.stream().map(k -> k.path("trackType").asText()).collect(java.util.stream.Collectors.toSet()));
        var beyond = query(Map.of("trackType", "all", "offset", Integer.MAX_VALUE));
        assertTrue(beyond.path("items").isEmpty()); assertFalse(beyond.path("hasMore").asBoolean());
        assertEquals(11, beyond.path("nextOffset").asInt());
        var summary = protocol.ok("openbone_query_scene", Map.of("kind", "animations")).path("items").get(0);
        assertEquals(5, summary.path("keyCount").asInt()); // Legacy count excludes DiscreteTracks.
        assertEquals(11, summary.path("totalKeyCount").asInt());
        assertEquals(2, summary.path("keyCounts").path("deform").asInt());
        assertTrue(protocol.ok("openbone_get_context", Map.of()).path("capabilities").path("nonBoneKeyframeQuery").asBoolean());
        var definitions = protocol.rpc("tools/list", Map.of()).path("result").path("tools");
        for (var definition : definitions) if (definition.path("name").asText().equals("openbone_query_scene"))
            assertTrue(definition.path("inputSchema").path("properties").has("trackType"));
    }

    @Test void filtersRetainDeformCoordinatesConstraintComponentsAndDiscretePayloads() throws Exception {
        assertEquals(10, query(Map.of("trackType", "non_bone")).path("total").asInt());
        var deform = query(Map.of("trackType", "all", "targetId", path.value(), "channel", "DEFORM"));
        assertEquals(2, deform.path("total").asInt());
        var row = deform.path("items").get(1);
        assertEquals("curve", row.path("targetName").asText());
        assertEquals("influence_bone_local", row.path("space").asText());
        assertEquals("setup_offsets", row.path("valueSemantics").asText());
        assertEquals(12, row.path("values").size()); assertEquals(12, row.path("curves").size());
        assertEquals(5., row.path("values").get(11).asDouble());
        assertEquals(6, row.path("valueBindings").size());
        assertEquals(10, row.path("valueBindings").get(5).path("valueOffset").asInt());
        assertEquals(bone.value(), row.path("valueBindings").get(0).path("boneId").asText());
        var ik = query(Map.of("trackType", "constraint", "targetId", constraint.value())).path("items").get(0);
        assertEquals("absolute", ik.path("valueSemantics").asText());
        assertEquals(List.of("mix", "softness", "bendPositive", "compress", "stretch"),
                protocol.JSON.convertValue(ik.path("components"), List.class));
        assertEquals(List.of(2, 3, 4), protocol.JSON.convertValue(ik.path("discreteComponents"), List.class));
        assertEquals("STEPPED", ik.path("curves").get(2).path("kind").asText());
        var hidden = query(Map.of("trackType", "attachment")).path("items").get(1);
        assertTrue(hidden.path("name").isNull()); assertFalse(hidden.has("curves"));
        assertEquals("stepped", hidden.path("interpolation").asText());
        var order = query(Map.of("trackType", "draw_order", "targetId", clip.value())).path("items").get(0);
        assertEquals(slot.value(), order.path("slots").get(0).asText());
        var emitted = query(Map.of("trackType", "event", "targetId", event.value())).path("items").get(0);
        assertEquals("instant", emitted.path("interpolation").asText());
        assertEquals(7, emitted.path("intValue").asInt()); assertEquals("step", emitted.path("stringValue").asText());
        assertEquals(2.5, emitted.path("floatValue").asDouble()); assertEquals(-.2, emitted.path("balance").asDouble());
        assertEquals(1, query(Map.of("trackType", "all", "boneId", bone.value())).path("total").asInt());
    }

    @Test void rejectedAndSuccessfulReadsPreserveHistoryDirtyStateAndRedo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var history = protocol.host.history;
            history.execute("rotate", Commands.model(d -> AnimationEdits.put(d, clip, bone, Channel.ROTATE, .5, List.of(20.))));
            history.undo();
        });
        var before = protocol.ok("openbone_get_context", Map.of());
        var query = new LinkedHashMap<String, Object>(Map.of("kind", "keyframes", "animationId", clip.value(), "trackType", "all"));
        for (var invalid : List.of(Map.of("trackType", "unknown"), Map.of("targetId", "bad-id"),
                Map.of("targetId", EntityId.create().value()), Map.of("channel", "nonsense"),
                Map.of("trackType", "non_bone", "boneId", bone.value()), Map.of("limit", 0),
                Map.of("targetId", target.value(), "boneId", bone.value()), Map.of("expectedRevision", "stale"),
                Map.of("kind", "bones"), Map.of("channel", 1), Map.of("targetId", ""))) {
            var args = new LinkedHashMap<String, Object>(query); args.putAll(invalid);
            assertTrue(protocol.call("openbone_query_scene", args).path("isError").asBoolean(), args.toString());
        }
        query(Map.of("trackType", "all"));
        assertEquals(before, protocol.ok("openbone_get_context", Map.of()));
        assertEquals(1, before.path("redoCount").asInt());
        protocol.ok("openbone_redo", Map.of("expectedRevision", before.path("revision").asText(), "requestId", "query-redo"));
        query.put("expectedRevision", before.path("revision").asText());
        assertTrue(protocol.call("openbone_query_scene", query).path("isError").asBoolean());
    }

    @Test void stretchymanReturnsThePreviouslyHiddenThirtyThreeKeys() throws Exception {
        Path source = Path.of("src/test/resources/spine43/stretchyman/stretchyman-pro.json").toAbsolutePath();
        var risk = assertThrows(SpineVersionRiskException.class, () -> SpineImportPolicy.select(Files.readAllBytes(source), SpineImportPolicy.Format.JSON, null));
        var project = new ProjectDocument(DocumentSnapshot.EMPTY);
        project.openResolved(source, risk.selection().approval());
        SwingUtilities.invokeAndWait(() -> protocol.host.installProject(project));
        var animation = project.history().state().model().animations().stream().filter(a -> a.name().equals("sneak")).findFirst().orElseThrow();
        var args = new LinkedHashMap<String, Object>(Map.of("kind", "keyframes", "animationId", animation.id().value(),
                "trackType", "non_bone", "limit", 200, "expectedRevision", protocol.revision()));
        var remaining = protocol.ok("openbone_query_scene", args);
        assertEquals(33, remaining.path("total").asInt());
        assertEquals(33, remaining.path("items").size());
        assertEquals(31, remaining.path("animationKeyCounts").path("deform").asInt());
        assertEquals(2, remaining.path("animationKeyCounts").path("constraint").asInt());
        var byTarget = new HashMap<String, Integer>();
        for (var row : remaining.path("items")) {
            byTarget.merge(row.path("targetName").asText(), 1, Integer::sum);
            if (row.path("trackType").asText().equals("constraint")) {
                assertEquals("IK", row.path("channel").asText());
                assertEquals(0., row.path("values").get(0).asDouble());
            } else {
                assertEquals(24, row.path("values").size());
                assertEquals(12, row.path("valueBindings").size());
                for (int component : List.of(2, 3, 20, 21))
                    assertEquals(0., row.path("values").get(component).asDouble());
            }
        }
        assertEquals(Map.of("back-leg-path", 13, "front-leg-path", 18, "back-arm-ik", 1, "front-arm-ik", 1), byTarget);
        args.put("trackType", "bone");
        assertEquals(334, protocol.ok("openbone_query_scene", args).path("total").asInt());
        args.put("trackType", "all");
        assertEquals(367, protocol.ok("openbone_query_scene", args).path("total").asInt());
        var returned = new HashSet<String>();
        remaining.path("items").forEach(k -> returned.add(k.path("id").asText()));
        assertEquals(animation.deforms().stream().mapToLong(t -> t.keys().size()).sum()
                + animation.constraintTracks().stream().mapToLong(t -> t.keys().size()).sum(), returned.size());
    }

    @Test void protocolQueryTextHasAllSupportedLanguages() {
        var base = ResourceBundle.getBundle("i18n.mcp_queries", Locale.ENGLISH);
        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            var localized = ResourceBundle.getBundle("i18n.mcp_queries", locale);
            assertEquals(base.keySet(), localized.keySet());
            assertNotEquals(base.getString("description"), localized.getString("description"));
        }
    }
}
