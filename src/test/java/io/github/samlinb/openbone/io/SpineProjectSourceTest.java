package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.AnimationSampler;
import static org.junit.jupiter.api.Assertions.*;

class SpineProjectSourceTest {
    @TempDir Path temp;
    static final String JSON="""
        {"skeleton":{"spine":"3.8.55","images":"./images/"},"bones":[{"name":"root"}],
        "slots":[{"name":"s","bone":"root","attachment":"tile"}],"skins":[{"name":"default","attachments":{"s":{"tile":{"width":8,"height":6}}}}],"animations":{"idle":{}}}
        """;
    Path fixture() throws Exception {
        Path project=temp.resolve("example.spine");Files.write(project,new byte[]{1,2,3,4});
        Files.createDirectories(temp.resolve("export"));Files.writeString(temp.resolve("export/example.json"),JSON);
        Files.createDirectories(temp.resolve("images"));ImageIO.write(new BufferedImage(8,6,BufferedImage.TYPE_INT_ARGB),"PNG",temp.resolve("images/tile.png").toFile());
        return project;
    }
    @Test void selectingNativeProjectUsesMatchingExportAndProjectImageDirectory() throws Exception {
        var project=fixture();var resolved=SpineProjectSource.resolve(project);
        assertEquals(temp.resolve("export/example.json"),resolved.json());assertEquals(temp,resolved.imageBase());
        var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.openResolved(project);
        assertEquals(1,doc.history().state().model().attachments().size());assertTrue(doc.path().isEmpty());assertTrue(doc.history().isDirty());
        assertEquals(temp.resolve("images/tile.png").toString(),doc.history().state().model().attachments().get(0).source());
        assertTrue(doc.importWarnings().get(0).contains("snapshot"));
        assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(project));
        assertThrows(ProjectFormatException.class,()->new ProjectCodec().read(project));
    }
    @Test void prefersSiblingExportAndKeepsLocalImageRootWhenPresent() throws Exception {
        var project=fixture();Files.writeString(temp.resolve("example.json"),JSON);
        assertEquals(temp.resolve("example.json"),SpineProjectSource.resolve(project).json());
        Files.createDirectories(temp.resolve("export/images"));
        Files.delete(temp.resolve("example.json"));
        assertEquals(temp.resolve("export"),SpineProjectSource.resolve(project).imageBase());
    }
    @Test void missingOrInvalidExportPreservesExistingDocument() throws Exception {
        var project=fixture();var doc=new ProjectDocument(DocumentSnapshot.EMPTY);
        var before=doc.history().state();Files.delete(temp.resolve("export/example.json"));
        assertThrows(ProjectFormatException.class,()->doc.openResolved(project));assertEquals(before,doc.history().state());
        Files.writeString(temp.resolve("export/example.json"),"{\"schemaVersion\":7}");
        assertThrows(ProjectFormatException.class,()->doc.openResolved(project));assertEquals(before,doc.history().state());
    }
    @Test void scopedTopologyCacheNeverAcceptsMutatedGeometry() {
        var vertices=new ArrayList<>(List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(0,1)));
        var triangles=new ArrayList<>(List.of(0,1,2));
        try(var cache=MeshTopology.cacheValidations()) {
            MeshTopology.validate(vertices,3,triangles);
            vertices.set(1,new Vec2(0,0));assertThrows(IllegalArgumentException.class,()->MeshTopology.validate(vertices,3,triangles));
            vertices.set(1,new Vec2(1,0));MeshTopology.validate(vertices,3,triangles);
            triangles.set(0,7);assertThrows(IllegalArgumentException.class,()->MeshTopology.validate(vertices,3,triangles));
        }
        assertThrows(IllegalArgumentException.class,()->MeshTopology.validate(vertices,3,triangles));
    }
    @Test @EnabledIfSystemProperty(named="openbone.spineboy",matches="true")
    void opensProvidedSpineboyProjectAndWritesPortableExample() throws Exception {
        Path project=Path.of("res/spineboy/spineboy-pro.spine").toAbsolutePath();assertTrue(Files.isRegularFile(project));
        byte[] original=Files.readAllBytes(project);var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.openResolved(project);
        var d=doc.history().state().model();assertEquals(64,d.bones().size());assertEquals(52,d.slots().size());
        assertEquals(11,d.animations().size());assertEquals(80,d.attachments().size());
        assertEquals(14,d.constraints().size());
        assertTrue(d.constraints().stream().anyMatch(c -> c.name().equals("aim-rear-arm-transform")));
        assertTrue(doc.importWarnings().stream().noneMatch(w -> w.contains("invalid configuration")
                || w.contains("3.8 recovery")), () -> doc.importWarnings().toString());
        assertTrue(doc.importWarnings().stream().noneMatch(w->w.contains("image unavailable")),()->doc.importWarnings().toString());
        assertTrue(d.attachments().stream().anyMatch(a->a.name().equals("head") && a instanceof MeshAttachment));
        assertTrue(d.attachments().stream().anyMatch(a->a.name().equals("torso") && a instanceof MeshAttachment));
        for(var clip:d.animations()) for(int frame=0;frame<=4;frame++)
            assertFalse(AnimationSampler.world(d,clip.id(),clip.duration()*frame/4,false).bones().isEmpty());
        assertTrue(d.attachments().stream().filter(a->a.source()!=null).allMatch(a->Files.isRegularFile(Path.of(a.source()))));
        Path directory=Path.of("target/spineboy-openbone");Files.createDirectories(directory);
        doc.savePortable(directory.resolve("spineboy-pro.json"));
        Files.copy(project.resolveSibling("license.txt"),directory.resolve("license.txt"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(directory.resolve("import-report.txt"),String.join("\n",doc.importWarnings()));
        assertArrayEquals(original,Files.readAllBytes(project));
        System.out.println("Spineboy snapshot: bones="+d.bones().size()+" slots="+d.slots().size()+" attachments="+d.attachments().size()+" animations="+d.animations().size()+" warnings="+doc.importWarnings().size());
    }
}
