package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonExportReaderTest {
    @TempDir Path temporary;
    @Test void p1FixtureIsReadableWithUnsupportedFeaturesReported() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("samples/p1/format-4.2.22.json")),"external oracle samples not present");
        var reader=new SkeletonExportReader();
        var fixture=reader.read(Path.of("samples/p1/format-4.2.22.json"));
        assertEquals("4.2.22",fixture.version());
        assertEquals(3,fixture.bones().size()); assertEquals(1,fixture.slots().size()); assertEquals(1,fixture.regions());
        assertTrue(fixture.unsupported().contains("$.animations (P4/P5)"));
        assertThrows(ProjectFormatException.class,()->new ProjectCodec().read(Path.of("samples/p1/format-4.2.22.json")));
    }
    @Test void exportVersionInvalidTopologyAndBinaryAreRejected() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("samples/p1/format-4.2.22.json")),"external oracle samples not present");
        ObjectMapper mapper=new ObjectMapper(); ObjectNode root=(ObjectNode)mapper.readTree(Path.of("samples/p1/format-4.2.22.json").toFile());
        Path file=temporary.resolve("bad.json");
        ((ObjectNode)root.get("skeleton")).put("spine","4.3.26"); mapper.writeValue(file.toFile(),root);
        assertThrows(ProjectFormatException.class,()->new SkeletonExportReader().read(file));
        ((ObjectNode)root.get("skeleton")).put("spine","4.2.22"); ((ObjectNode)root.get("bones").get(0)).put("parent","tip"); mapper.writeValue(file.toFile(),root);
        assertThrows(ProjectFormatException.class,()->new SkeletonExportReader().read(file));
        assertThrows(ProjectFormatException.class,()->new SkeletonExportReader().read(temporary.resolve("native.spine")));
    }
}
