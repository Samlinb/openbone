package io.github.samlinb.openbone.io;

import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.Vec2;
import static org.junit.jupiter.api.Assertions.*;

class SpineMetadataTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    final SkeletonJsonCodec codec = new SkeletonJsonCodec();
    final String source = """
        {"skeleton":{"spine":"3.8.55","hash":"original-hash","x":-10,"y":-20,"width":30,"height":40,"audio":"./audio/","fps":30},
        "bones":[{"name":"root"}],"slots":[{"name":"slot","bone":"root","attachment":"mesh"}],
        "skins":[{"name":"default","attachments":{"slot":{"mesh":{"type":"mesh","path":"mesh",
        "vertices":[0,0,10,0,10,10,0,10],"uvs":[0,1,1,1,1,0,0,0],"triangles":[0,1,2,2,3,0],"hull":4,
        "edges":[0,2,2,4,4,6,6,0],"width":16,"height":16}}}}],"animations":{}}
        """;

    DocumentSnapshot sample() throws Exception {
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB), "png", temp.resolve("mesh.png").toFile());
        return codec.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8), temp);
    }

    @Test void metadataSurvivesSaveReopenPortableSaveAndBothExports() throws Exception {
        var d = sample(); assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
        var original = json.readTree(source);
        var projects = new ProjectCodec(); var encoded = projects.encode(d);
        assertEquals(13, json.readTree(encoded).path("schemaVersion").asInt());
        assertEquals(d, projects.decode(encoded)); assertArrayEquals(encoded, projects.encode(projects.decode(encoded)));
        var doc = new ProjectDocument(d); Path project = temp.resolve("saved.json"); doc.savePortable(project);
        var reopened = new ProjectDocument(DocumentSnapshot.EMPTY); reopened.open(project);
        assertEquals(d.resources().metadata(), reopened.history().state().model().resources().metadata());
        for (String version : List.of(SkeletonJsonCodec.VERSION_38, SkeletonJsonCodec.VERSION)) {
            var bytes = codec.encode(d, Map.of(d.attachments().get(0).id(), "mesh"), "", version);
            var tree = json.readTree(bytes);
            for (String key : List.of("hash","x","y","width","height","audio","fps"))
                assertEquals(original.path("skeleton").path(key).asText(), tree.path("skeleton").path(key).asText().replaceAll("\\.0$", ""));
            var loaded = codec.decode(bytes, temp);
            assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
            assertEquals(d.resources().metadata().numbers(), loaded.resources().metadata().numbers());
            assertEquals(d.resources().metadata().text(), loaded.resources().metadata().text());
            assertEquals(d.resources().metadata().meshes().values().iterator().next(), loaded.resources().metadata().meshes().values().iterator().next());
        }
    }

    @Test void sourceChangesAndVertexMovesKeepMetadataWhileRuntimeTopologyChangesAreRejected() throws Exception {
        var d = sample(); var id = d.attachments().get(0).id(); var metadata = d.resources().metadata();
        var moved = MeshEdits.vertex(d, id, 0, new Vec2(-1,0), new Vec2(0,1));
        assertEquals(metadata, moved.resources().metadata());
        var original=((MeshAttachment)d.attachments().get(0)).vertices();
        assertThrows(IllegalArgumentException.class,()->MeshEdits.insert(d, id, 1, new Vec2(5,-1), new Vec2(.5,1), true));
        assertEquals(original,((MeshAttachment)d.attachments().get(0)).vertices());
        assertEquals(metadata,d.resources().metadata());
        assertEquals(metadata, ExtendedEdits.addSkin(d, "extra").resources().metadata());
    }

    @Test void malformedEdgesAreReportedWithoutDiscardingMesh() throws Exception {
        sample();
        for (String edges : List.of("[0,999]", "[0,1]", "[0]", "null", "[0,2.5]")) {
            var root = (ObjectNode)json.readTree(source);
            ((ObjectNode)root.path("skins").get(0).path("attachments").path("slot").path("mesh")).set("edges", json.readTree(edges));
            var d = codec.decode(json.writeValueAsBytes(root), temp);
            assertEquals(1, d.attachments().size());
            assertTrue(codec.warnings().stream().anyMatch(w -> w.contains(".edges: invalid")), () -> codec.warnings().toString());
            assertNull(d.resources().metadata().meshes().values().iterator().next().edges());
        }
    }

    @Test @EnabledIfSystemProperty(named="openbone.spineboy", matches="true")
    void realSpineboyNoLongerFiltersSkeletonOrMeshMetadata() throws Exception {
        var path = SpineProjectSource.resolve(Path.of("res/spineboy/spineboy-pro.spine"));
        var original = json.readTree(Files.readAllBytes(path.json()));
        var d = codec.decode(Files.readAllBytes(path.json()), path.imageBase());
        assertTrue(codec.warnings().stream().noneMatch(w -> w.startsWith("$.skeleton.") && w.contains("unsupported field")
                || w.matches(".*\\.(edges|width|height): unsupported field.*")), () -> codec.warnings().toString());
        assertEquals(original.path("skeleton").path("hash").asText(), d.resources().metadata().text().get("hash"));
        assertEquals(10, d.resources().metadata().meshes().size());
        for (var a : d.attachments()) if (a instanceof MeshAttachment) {
            String slot = d.slots().stream().filter(s -> s.id().equals(a.slotId())).findFirst().orElseThrow().name();
            var n = original.path("skins").get(0).path("attachments").path(slot).path(a.name());
            var info = d.resources().metadata().meshes().get(a.id());
            assertEquals(json.convertValue(n.path("edges"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>(){}), info.edges());
            assertEquals(n.path("width").asDouble(), info.width()); assertEquals(n.path("height").asDouble(), info.height());
        }
        assertEquals(d, new ProjectCodec().decode(new ProjectCodec().encode(d)));
    }
}
