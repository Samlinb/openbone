package io.github.samlinb.openbone.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class BoneFieldsTest {
    @TempDir Path temp;

    @Test @EnabledIfSystemProperty(named="openbone.spineboy", matches="true")
    void providedSpineboyRetainsBoneFieldsAndSamplesAllAnimations() throws Exception {
        var source = SpineProjectSource.resolve(Path.of("res/spineboy/spineboy-pro.spine"));
        var codec = new SkeletonJsonCodec();
        var d = codec.decode(java.nio.file.Files.readAllBytes(source.json()), source.imageBase());
        assertEquals(64, d.bones().size());
        assertEquals(4, d.bones().stream().filter(b -> b.inherit() == Bone.Inherit.noRotationOrReflection).count());
        assertTrue(d.bones().stream().anyMatch(b -> b.name().equals("rear-foot") && !b.color().equals(Rgba.WHITE)));
        assertTrue(codec.warnings().stream().noneMatch(w -> w.startsWith("$.bones.") &&
                (w.contains(".color: unsupported") || w.contains(".transform: unsupported"))), () -> codec.warnings().toString());
        var projects = new ProjectCodec();
        assertEquals(d, projects.decode(projects.encode(d)));
        for (var clip : d.animations()) for (int frame = 0; frame <= 4; frame++)
            assertEquals(64, io.github.samlinb.openbone.core.animation.AnimationSampler.world(
                    d, clip.id(), clip.duration() * frame / 4, false).bones().size());
    }

    @Test void allModesAndColorsSurviveProjectAndBothExportVersions() throws Exception {
        for (var mode : Bone.Inherit.values()) {
            String source = """
                {"skeleton":{"spine":"3.8.55"},"bones":[{"name":"root","rotation":90},
                {"name":"foot","parent":"root","x":2,"rotation":20,"color":"ff3f00ff","transform":"%s"}],
                "slots":[],"skins":[{"name":"default","attachments":{}}],"animations":{}}
                """.formatted(mode.name());
            var codec = new SkeletonJsonCodec();
            var original = codec.decode(source.getBytes(StandardCharsets.UTF_8), temp);
            assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
            var foot = original.bones().get(1);
            assertEquals(mode, foot.inherit()); assertEquals("ff3f00ff", foot.color().hex());
            assertEquals(foot.color(), foot.withName("renamed").withLocal(Transform.IDENTITY).withParent(null, Transform.IDENTITY).color());
            assertEquals(mode, foot.withName("renamed").withLocal(Transform.IDENTITY).withParent(null, Transform.IDENTITY).inherit());
            var projects = new ProjectCodec();
            var saved = projects.encode(original);
            assertEquals(8, new ObjectMapper().readTree(saved).path("schemaVersion").asInt());
            assertEquals(original, projects.decode(saved));
            for (String version : List.of(SkeletonJsonCodec.VERSION_38, SkeletonJsonCodec.VERSION)) {
                var encoded = codec.encode(original, Map.of(), "", version);
                var tree = new ObjectMapper().readTree(encoded).path("bones").get(1);
                assertEquals(mode.name(), tree.path(version.equals(SkeletonJsonCodec.VERSION_38) ? "transform" : "inherit").asText());
                var loaded = codec.decode(encoded, temp);
                assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
                assertEquals(mode, loaded.bones().get(1).inherit());
                assertEquals(foot.color(), loaded.bones().get(1).color());
                assertEquals(original.evaluateWorld().bones().get(foot.id()).matrix(),
                        loaded.evaluateWorld().bones().get(loaded.bones().get(1).id()).matrix());
            }
        }
    }

    @Test void invalid38FieldsWarnAndUseDefaults() throws Exception {
        var codec = new SkeletonJsonCodec();
        for (String invalid : List.of("null", "42", "\"invalid\"")) {
            var source = "{\"skeleton\":{\"spine\":\"3.8.55\"},\"bones\":[{\"name\":\"root\",\"color\":"+invalid+",\"transform\":"+invalid+"}]}";
            var d = codec.decode(source.getBytes(StandardCharsets.UTF_8), temp);
            assertEquals(Rgba.WHITE, d.bones().get(0).color());
            assertEquals(Bone.Inherit.normal, d.bones().get(0).inherit());
            assertTrue(codec.warnings().stream().anyMatch(w -> w.contains(".color:")));
            assertTrue(codec.warnings().stream().anyMatch(w -> w.contains(".transform:")));
        }
    }

    @Test void parentRotationScaleReflectionAndAnimatedPoseRespectInheritance() {
        for (var mode : Bone.Inherit.values()) {
            var root = new Bone(EntityId.create(), "root", null, new Transform(10, 20, 90, 2, 3), 1);
            var child = new Bone(EntityId.create(), "foot", root.id(), new Transform(2, 0, 0, 1, 1), 1, Rgba.WHITE, mode);
            var d = new DocumentSnapshot(List.of(root, child), List.of(), List.of());
            var matrix = d.evaluateWorld().bones().get(child.id()).matrix();
            assertEquals(10, matrix.tx(), 1e-10); assertEquals(24, matrix.ty(), 1e-10);
            switch (mode) {
                case normal -> { assertEquals(2, matrix.c(), 1e-10); assertEquals(-3, matrix.b(), 1e-10); }
                case onlyTranslation -> { assertEquals(1, matrix.a(), 1e-10); assertEquals(1, matrix.d(), 1e-10); }
                case noRotationOrReflection -> { assertEquals(3, matrix.a(), 1e-10); assertEquals(2, matrix.d(), 1e-10); }
                case noScale, noScaleOrReflection -> { assertEquals(1, matrix.c(), 1e-10); assertEquals(-1, matrix.b(), 1e-10); }
            }
            var reflected = ConstraintSolver.evaluate(d.bones(), Map.of(root.id(), new Transform(0,0,0,-2,3)), List.of()).get(child.id());
            assertEquals(mode == Bone.Inherit.normal || mode == Bone.Inherit.noScale ? -1 : 1,
                    Math.signum(reflected.determinant()));
            var collapsed = ConstraintSolver.evaluate(d.bones(), Map.of(root.id(), new Transform(0,0,0,0,0)), List.of()).get(child.id());
            assertTrue(Double.isFinite(collapsed.determinant()));
        }
    }

    @Test void worldEditsInvertInheritanceWithRotatedScaledAndReflectedParents() {
        for (var mode : Bone.Inherit.values()) for (double parentScale : List.of(-2., 2.))
            for (double localScale : List.of(-3., 3.)) {
                var parent = new Transform(10, 20, 37, parentScale, 4).matrix();
                var local = new Transform(2, -5, 71, localScale, 2);
                var expected = BoneInheritance.world(mode, parent, local, local.matrix());
                var restored = BoneInheritance.local(mode, parent, expected);
                var actual = BoneInheritance.world(mode, parent, restored, restored.matrix());
                assertEquals(expected.a(), actual.a(), 1e-10, mode.name());
                assertEquals(expected.b(), actual.b(), 1e-10, mode.name());
                assertEquals(expected.c(), actual.c(), 1e-10, mode.name());
                assertEquals(expected.d(), actual.d(), 1e-10, mode.name());
                assertEquals(expected.tx(), actual.tx(), 1e-10);
                assertEquals(expected.ty(), actual.ty(), 1e-10);
            }
    }
}
