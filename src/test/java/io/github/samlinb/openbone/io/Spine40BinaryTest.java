package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Spine40BinaryTest {
    private static final Path SOURCE = Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel");
    @TempDir Path temp;

    @Test void importsActualXiaoheiAndBothUnitySuffixes() throws Exception {
        for (String suffix : List.of(".skel", ".skel.bytes")) {
            var codec = new SkeletonJsonCodec();
            Path source = SOURCE.resolveSibling("Ninjahouse_xiaohei" + suffix);
            var model = codec.read(source);
            assertEquals(17, model.bones().size());
            assertEquals(9, model.slots().size());
            assertEquals(16, model.attachments().size());
            assertEquals(List.of("idle", "idle2", "move", "walk"), model.animations().stream().map(a -> a.name()).toList());
            assertEquals(16, model.resources().atlas().size());
            assertEquals(List.of(io.github.samlinb.openbone.i18n.M.m("spineImport.nearestFilter"),
                    io.github.samlinb.openbone.i18n.M.m("spineImport.roundedWeights")), codec.warnings());
            for (var attachment : model.attachments()) if (attachment instanceof MeshAttachment mesh)
                for (var row : mesh.weights()) {
                    Influence.validate(row);
                    assertEquals(1, row.stream().mapToDouble(Influence::weight).sum(), 1e-12);
                }
            assertTrue(AtlasSplitter.available(source, model));
            assertTrue(model.attachments().stream().filter(a -> a.source() != null).allMatch(a -> Files.isRegularFile(Path.of(a.source()))));
        }
    }

    @Test void invalidBinaryPreservesDocumentAndRedoHistory() throws Exception {
        var document = new ProjectDocument(DocumentSnapshot.EMPTY);
        document.importSkeleton(SOURCE);
        document.history().execute("Skin", Commands.model(d -> ExtendedEdits.skin(d, "1")));
        document.history().undo();
        var state = document.history().state();
        String revision = document.history().revision();
        assertEquals(1, document.history().redoCount());
        Path bad = temp.resolve("broken.skel");
        byte[] data = Files.readAllBytes(SOURCE);
        Files.write(bad, Arrays.copyOf(data, data.length - 1));
        assertThrows(ProjectFormatException.class, () -> document.importSkeleton(bad));
        assertSame(state, document.history().state());
        assertEquals(revision, document.history().revision());
        assertEquals(1, document.history().redoCount());
        assertTrue(document.history().isDirty());
        assertTrue(document.path().isEmpty());
    }

    @Test void unityAtlasTextFallbackAndSplitPreserveSources() throws Exception {
        Path source = temp.resolve("actor.skel.bytes");
        Files.copy(SOURCE, source);
        Path atlas = temp.resolve("actor.atlas.txt");
        Files.copy(SOURCE.resolveSibling("Ninjahouse_xiaohei.atlas"), atlas);
        Path png = temp.resolve("Ninjahouse_xiaohei.png");
        Files.copy(SOURCE.resolveSibling("Ninjahouse_xiaohei.png"), png);
        byte[] originalAtlas = Files.readAllBytes(atlas), originalPng = Files.readAllBytes(png);
        var model = new SkeletonJsonCodec().read(source);
        assertEquals(16, model.attachments().size());
        assertTrue(AtlasSplitter.available(source, model));
        var split = AtlasSplitter.split(source, model);
        assertTrue(split.document().resources().atlas().isEmpty());
        assertTrue(split.images() > 0);
        assertArrayEquals(originalAtlas, Files.readAllBytes(atlas));
        assertArrayEquals(originalPng, Files.readAllBytes(png));
    }

    @Test void rejectsTruncatedUnsupportedAndTrailingData() throws Exception {
        byte[] data = Files.readAllBytes(SOURCE);
        for (int length : new int[]{0, 1, 8, 12, 30, data.length / 2, data.length - 1})
            assertThrows(ProjectFormatException.class, () -> Spine40Binary.decode(Arrays.copyOf(data, length)));
        var newer = data.clone(); newer[11] = '9';
        assertTrue(assertThrows(ProjectFormatException.class, () -> Spine40Binary.decode(newer)).getMessage().contains("4.9"));
        assertThrows(ProjectFormatException.class, () -> Spine40Binary.decode(Arrays.copyOf(data, data.length + 1)));
    }

    @Test void openResolvedAndPortableRoundtrip() throws Exception {
        var document = new ProjectDocument(DocumentSnapshot.EMPTY);
        document.openResolved(SOURCE);
        assertTrue(document.history().isDirty());
        Path saved = temp.resolve("xiaohei.json");
        document.save(saved);
        var reopened = new ProjectDocument(DocumentSnapshot.EMPTY); reopened.openResolved(saved);
        assertEquals(document.history().state().model().bones(), reopened.history().state().model().bones());
        assertEquals(document.history().state().model().animations(), reopened.history().state().model().animations());
        for (String suffix : List.of(".skel", ".skel.bytes")) {
            Path binary = temp.resolve("reserved" + suffix);
            Files.write(binary, new byte[]{1, 2, 3});
            assertThrows(ProjectFormatException.class, () -> document.save(binary));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(binary));
        }
    }
}
