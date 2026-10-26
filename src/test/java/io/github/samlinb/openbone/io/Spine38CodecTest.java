package io.github.samlinb.openbone.io;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.core.animation.*;
import static org.junit.jupiter.api.Assertions.*;

class Spine38CodecTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    private byte[] sample() throws Exception {
        try (var in = getClass().getResourceAsStream("/spine38/dandao_1.json")) { return in.readAllBytes(); }
    }

    @Test void completeSampleImportsSavesAndExportsBothVersionsWithOriginalAtlasPixels() throws Exception {
        for (String extension : List.of("json", "atlas", "png")) {
            String name = "dandao_1." + extension;
            try (var in = getClass().getResourceAsStream("/spine38/" + name)) {
                Files.copy(in, temp.resolve(name));
            }
        }
        var codec = new SkeletonJsonCodec();
        var d = codec.read(temp.resolve("dandao_1.json"));
        assertEquals(37, d.attachments().size());
        assertEquals(37, d.resources().atlas().size(), () -> codec.warnings().toString());
        assertTrue(codec.warnings().stream().noneMatch(w -> w.contains("missing page") || w.contains("image unavailable") || w.contains("invalid configuration")), () -> codec.warnings().toString());
        byte[] pixels = Files.readAllBytes(temp.resolve("dandao_1.png"));
        assertEquals(3, pixels[25] & 255);
        assertThrows(java.io.IOException.class, () -> RuntimePng.require(pixels));
        for (var attachment : d.attachments()) {
            assertArrayEquals(pixels, Files.readAllBytes(Path.of(attachment.source())));
            var region = d.resources().atlas().get(attachment.id());
            assertEquals(329, region.pageWidth()); assertEquals(329, region.pageHeight());
        }
        var document = new io.github.samlinb.openbone.application.ProjectDocument(io.github.samlinb.openbone.core.model.DocumentSnapshot.EMPTY);
        document.importSkeleton(temp.resolve("dandao_1.json"));
        Path project = temp.resolve("sample.openbone.json"); document.save(project);
        assertEquals(37, new ProjectCodec().read(project).attachments().size());
        for (String version : List.of(SkeletonJsonCodec.VERSION_38, SkeletonJsonCodec.VERSION)) {
            Path directory = temp.resolve("export-" + version);
            Path exported = new ExportPackage().publish(directory, d, temp, version);
            assertEquals(exported, ExportPackage.resolve(directory.resolve("current.json")));
            assertEquals(version, json.readTree(Files.readAllBytes(exported)).path("skeleton").path("spine").asText());
            var loaded = codec.read(exported);
            assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
            assertEquals(35, loaded.bones().size()); assertEquals(28, loaded.slots().size());
            assertEquals(37, loaded.attachments().size());
            for (var before : d.attachments()) {
                var after = loaded.attachments().stream().filter(a -> a.name().equals(before.name())
                        && loaded.slots().stream().anyMatch(s -> s.id().equals(a.slotId())
                                && d.slots().stream().anyMatch(old -> old.id().equals(before.slotId()) && old.name().equals(s.name())))).findFirst().orElseThrow();
                assertEquals(d.resources().atlas().get(before.id()), loaded.resources().atlas().get(after.id()));
                byte[] packed = Files.readAllBytes(Path.of(after.source()));
                RuntimePng.require(packed);
                var expectedPixels = io.github.samlinb.openbone.viewport.ImageAssets.decodePng(pixels);
                var actualPixels = io.github.samlinb.openbone.viewport.ImageAssets.decodePng(packed);
                assertArrayEquals(expectedPixels.getRGB(0, 0, 329, 329, null, 0, 329),
                        actualPixels.getRGB(0, 0, 329, 329, null, 0, 329));
            }
        }
        assertArrayEquals(pixels, Files.readAllBytes(temp.resolve("dandao_1.png")));
    }

    @Test void realExportRetainsBonesAttachmentsAnimationAndMissingImageLinks() throws Exception {
        Path file = temp.resolve("dandao_1.json"); Files.write(file, sample());
        try (var in = getClass().getResourceAsStream("/spine38/dandao_1.atlas")) {
            Files.copy(in, temp.resolve("dandao_1.atlas"));
        }
        var codec = new SkeletonJsonCodec(); var d = codec.read(file);
        assertEquals(35, d.bones().size()); assertEquals(28, d.slots().size());
        assertEquals(37, d.attachments().size()); assertEquals("attack_1", d.animations().get(0).name());
        assertEquals(37, d.resources().atlas().size());
        var bone = d.bones().stream().filter(b -> b.name().equals("attack_hit_xian1")).findFirst().orElseThrow();
        var rotate = d.animations().get(0).timelines().stream().filter(t -> t.boneId().equals(bone.id()) && t.channel() == Channel.ROTATE).findFirst().orElseThrow();
        assertEquals(-142, rotate.keys().get(0).values().get(0));
        var translate = d.animations().get(0).timelines().stream().filter(t -> t.boneId().equals(bone.id()) && t.channel() == Channel.TRANSLATE).findFirst().orElseThrow();
        assertEquals(.133, translate.keys().get(0).curves().get(0).x1(), 1e-12);
        assertEquals(.6, translate.keys().get(0).curves().get(1).y1(), 1e-12);
        assertFalse(d.animations().get(0).discrete().attachments().isEmpty());
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("missing page")));
        assertTrue(codec.warnings().stream().noneMatch(w -> w.contains(".color")));
        var paths = new LinkedHashMap<io.github.samlinb.openbone.core.model.EntityId, String>();
        d.attachments().forEach(a -> paths.put(a.id(), a.name()));
        byte[] exported = codec.encode(d, paths, "", SkeletonJsonCodec.VERSION_38);
        assertEquals(37, codec.decode(exported, temp).attachments().size());
    }

    @Test void filtersBadFieldsReferencesAndKeysWithoutDiscardingValidTracks() throws Exception {
        var root = (ObjectNode)json.readTree(sample());
        ((ObjectNode)root.path("bones").get(0)).put("x", "broken");
        ((ArrayNode)root.path("bones")).addObject().put("name", "bad").put("parent", "missing");
        ((ArrayNode)root.path("slots")).addObject().put("name", "bad").put("bone", "missing");
        var keys = (ArrayNode)root.path("animations").path("attack_1").path("bones").path("attack_hit_xian1").path("translate");
        keys.addObject().put("time", -1).put("x", 999);
        keys.addObject().put("time", .0667).put("x", 999);
        var codec = new SkeletonJsonCodec(); var d = codec.decode(json.writeValueAsBytes(root), temp);
        assertEquals(35, d.bones().size()); assertEquals(28, d.slots().size());
        assertEquals(0, d.bones().get(0).local().x());
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("duplicate time")));
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("invalid key")));
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("missing/cyclic parent")));
    }

    @Test void exportsActual38AnglePairedTracksAndRelativeBezierAndReimports() throws Exception {
        String source = """
            {"skeleton":{"spine":"3.8.75"},"bones":[{"name":"root"}],"slots":[],
            "skins":[{"name":"default","attachments":{}}],
            "animations":{"test":{"bones":{"root":{"rotate":[{"angle":12,"curve":0.2,"c2":0.1,"c3":0.8,"c4":0.9},{"time":1,"angle":72}],
            "translate":[{"x":1,"y":2,"curve":"stepped"},{"time":1,"x":3,"y":4}]}}}}}
            """;
        var codec = new SkeletonJsonCodec(); var d = codec.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8), temp);
        var bytes = codec.encode(d, Map.of(), "", SkeletonJsonCodec.VERSION_38);
        var root = json.readTree(bytes); assertEquals("3.8.75", root.path("skeleton").path("spine").asText());
        var tracks = root.path("animations").path("test").path("bones").path("root");
        assertEquals(12, tracks.path("rotate").get(0).path("angle").asDouble());
        assertEquals(.2, tracks.path("rotate").get(0).path("curve").asDouble(), 1e-12);
        assertTrue(tracks.has("translate")); assertFalse(tracks.has("translatex"));
        var imported = codec.decode(bytes, temp);
        for (int i = 0; i < d.animations().get(0).timelines().size(); i++)
            for (int k = 0; k < 2; k++) {
                var before = d.animations().get(0).timelines().get(i).keys().get(k);
                var after = imported.animations().get(0).timelines().get(i).keys().get(k);
                assertEquals(before.values(), after.values()); assertEquals(before.curves(), after.curves());
            }
        var entry = new ExportPackage().publish(temp.resolve("package"), d, temp, SkeletonJsonCodec.VERSION_38);
        assertEquals(entry, ExportPackage.resolve(temp.resolve("package/current.json")));
        assertEquals("3.8.75", new SkeletonExportReader().read(entry).version());
    }

    @Test void invalidMeshAndUnknownAnimationFieldsAreIsolatedDuringRecovery() throws Exception {
        var root = (ObjectNode)json.readTree(sample());
        var attachments = (ObjectNode)root.path("skins").get(0).path("attachments").path("attack_hit_huaban1");
        attachments.putObject("brokenMesh").put("type", "mesh").putArray("vertices").add(0);
        ((ObjectNode)root.path("animations").path("attack_1").path("bones"))
                .putObject("missingBone").putArray("rotate").addObject().put("angle", 5);
        var codec = new SkeletonJsonCodec(); var d = codec.decode(json.writeValueAsBytes(root), temp);
        assertEquals(37, d.attachments().size()); assertFalse(d.animations().get(0).timelines().isEmpty());
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("brokenMesh") && w.contains("filtered invalid")));
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("missingBone")));
        assertEquals(35, d.bones().size());
    }

    @Test void default38BezierControlAndUnsupportedVersionAreHandledExplicitly() throws Exception {
        var root = (ObjectNode)json.readTree(sample());
        var key = (ObjectNode)root.path("animations").path("attack_1").path("bones").path("attack_hit_xian1").path("translate").get(0);
        key.remove("c3");
        var codec = new SkeletonJsonCodec(); var d = codec.decode(json.writeValueAsBytes(root), temp);
        var bone = d.bones().stream().filter(b -> b.name().equals("attack_hit_xian1")).findFirst().orElseThrow();
        var track = d.animations().get(0).timelines().stream().filter(t -> t.boneId().equals(bone.id()) && t.channel() == Channel.TRANSLATE).findFirst().orElseThrow();
        assertEquals(1, track.keys().get(0).curves().get(0).x2());
        ((ObjectNode)root.path("skeleton")).put("spine", "3.7.94");
        assertThrows(ProjectFormatException.class, () -> codec.decode(json.writeValueAsBytes(root), temp));
    }

    @Test void supportsLegacySkinObjectAndTransformConstraintTimelineRoundTrip() throws Exception {
        String source = """
            {"skeleton":{"spine":"3.8.75"},"bones":[{"name":"root"},{"name":"child","parent":"root"},{"name":"target","parent":"root","x":10}],
            "slots":[],"skins":{"default":{}},"transform":[{"name":"follow","bones":["child"],"target":"target","rotateMix":0,"translateMix":0,"scaleMix":0,"shearMix":0}],
            "animations":{"test":{"transform":{"follow":[{"rotateMix":0,"translateMix":0,"scaleMix":0,"shearMix":0},{"time":1,"rotateMix":0,"translateMix":0,"scaleMix":0,"shearMix":0}]}}}}
            """;
        var codec = new SkeletonJsonCodec();
        var d = codec.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8), temp);
        assertEquals(1, d.constraints().size()); assertEquals(1, d.animations().get(0).constraintTracks().size());
        var bytes = codec.encode(d, Map.of(), "", SkeletonJsonCodec.VERSION_38);
        assertTrue(json.readTree(bytes).path("transform").get(0).has("translateMix"));
        assertTrue(json.readTree(bytes).path("animations").path("test").path("transform").path("follow").get(0).has("rotateMix"));
        assertEquals(1, codec.decode(bytes, temp).animations().get(0).constraintTracks().size());
    }

    @Test void repairingBoneOrderRemapsWeightedVertexIndices() throws Exception {
        var root = (ObjectNode)json.readTree("""
            {"skeleton":{"spine":"3.8.75"},"bones":[{"name":"child","parent":"root"},{"name":"root"}],
            "slots":[{"name":"slot","bone":"child"}],"skins":[{"name":"default","attachments":{"slot":{"mesh":{"type":"mesh",
            "vertices":[1,0,0,0,1,1,0,1,0,1,1,0,0,1,1],"uvs":[0,0,1,0,0,1],"triangles":[0,1,2],"hull":3}}}}]}
            """);
        var warnings = new ArrayList<String>();
        var normalized = Spine38Json.normalize(root, warnings);
        assertEquals("root", normalized.path("bones").get(0).path("name").asText());
        var vertices = normalized.path("skins").get(0).path("attachments").path("slot").path("mesh").path("vertices");
        assertEquals(1, vertices.get(1).asInt()); assertEquals(1, vertices.get(6).asInt()); assertEquals(1, vertices.get(11).asInt());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("remapped bone index")));
    }
}
