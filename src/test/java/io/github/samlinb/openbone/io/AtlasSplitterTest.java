package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import static org.junit.jupiter.api.Assertions.*;

class AtlasSplitterTest {
    @TempDir Path temp;
    Path fixture() throws Exception {
        for(String extension:List.of("json","atlas","png")) try(var in=getClass().getResourceAsStream("/spine38/dandao_1."+extension)) {
            Files.copy(in,temp.resolve("dandao_1."+extension));
        }
        return temp.resolve("dandao_1.json");
    }
    @Test void detectsOnlyValidNeighborResourcesWithoutWritingAnything() throws Exception {
        var file=fixture(); var d=new SkeletonJsonCodec().read(file);
        assertTrue(AtlasSplitter.available(file,d));
        try(var files=Files.list(temp)) { assertEquals(3,files.count()); }
        assertFalse(AtlasSplitter.available(file,DocumentSnapshot.EMPTY));
        Files.move(temp.resolve("dandao_1.png"),temp.resolve("renamed.png"));
        assertFalse(AtlasSplitter.available(file,d));
        Files.move(temp.resolve("renamed.png"),temp.resolve("dandao_1.png"));
        Files.writeString(temp.resolve("dandao_1.atlas"),"broken atlas");
        assertFalse(AtlasSplitter.available(file,d));
        assertThrows(java.io.IOException.class,()->AtlasSplitter.split(file,d));
        try(var files=Files.list(temp)) { assertEquals(3,files.count()); }
    }

    @Test void dandaoExtractsElevenLogicalImagesAndKeepsAnimationAndSourceFiles() throws Exception {
        var file=fixture(); var d=new SkeletonJsonCodec().read(file);
        var originals=new HashMap<Path,byte[]>();
        for(String extension:List.of("json","atlas","png")) { Path p=temp.resolve("dandao_1."+extension); originals.put(p,Files.readAllBytes(p)); }
        var split=AtlasSplitter.split(file,d); var after=split.document();
        assertEquals(11,split.images()); assertEquals(temp,split.directory().getParent());
        assertTrue(after.resources().atlas().isEmpty());
        assertEquals(d.bones(),after.bones()); assertEquals(d.slots(),after.slots());
        assertEquals(d.animations(),after.animations()); assertEquals(d.constraints(),after.constraints());
        assertEquals(d.resources().skins(),after.resources().skins());
        var page=ImageIO.read(temp.resolve("dandao_1.png").toFile());
        for(int i=0;i<d.attachments().size();i++) {
            var original=d.attachments().get(i); var result=after.attachments().get(i);
            assertEquals(original.id(),result.id());
            var image=ImageIO.read(Path.of(result.source()).toFile()); var region=d.resources().atlas().get(original.id());
            assertEquals(region.originalWidth(),image.getWidth()); assertEquals(region.originalHeight(),image.getHeight());
            for(int y=0;y<region.originalHeight();y++) for(int x=0;x<region.originalWidth();x++) {
                int rx=x-region.offsetX(), ry=y-(region.originalHeight()-region.offsetY()-region.height());
                int expected=rx<0||rx>=region.width()||ry<0||ry>=region.height()?0
                        :page.getRGB(region.x()+(region.rotated()?ry:rx),region.y()+(region.rotated()?region.width()-1-rx:ry));
                assertEquals(expected,image.getRGB(x,y));
            }
            assertEquals(((RegionAttachment)original).local(),((RegionAttachment)result).local());
            assertEquals(((RegionAttachment)original).width(),((RegionAttachment)result).width());
        }
        for(var entry:originals.entrySet()) assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()));
        var history=new CommandHistory(d); history.execute("Split atlas",Commands.model(v->after));
        assertTrue(history.undo());assertEquals(d,history.state().model());assertTrue(history.redo());assertEquals(after,history.state().model());
        var project=new ProjectDocument(after);Path saved=temp.resolve("portable.json");project.savePortable(saved);
        assertTrue(new ProjectCodec().read(saved).resources().atlas().isEmpty());
        var exported=new ExportPackage().publish(temp.resolve("export"),after,temp,SkeletonJsonCodec.VERSION_38);
        assertEquals(37,new SkeletonJsonCodec().read(exported).attachments().size());
    }

    @Test void restoresRotatedTrimmedPixelsAndTransparentPadding() throws Exception {
        var page=new BufferedImage(6,6,BufferedImage.TYPE_INT_ARGB);
        // Logical 2x3 tile, packed as 3x2 with 90-degree rotation.
        int[][] tile={{0xff112233,0xff445566},{0x80778899,0x40aabbcc},{0xffddeeff,0x00123456}};
        for(int y=0;y<3;y++) for(int x=0;x<2;x++) page.setRGB(1+y,1+2-1-x,tile[y][x]);
        var region=new AtlasRegion(6,6,1,1,2,3,5,6,2,1,true);
        var output=AtlasSplitter.unpack(page,region);
        assertEquals(5,output.getWidth()); assertEquals(6,output.getHeight());
        for(int y=0;y<6;y++) for(int x=0;x<5;x++) assertEquals(x>=2&&x<4&&y>=2&&y<5?tile[y-2][x-2]:0,output.getRGB(x,y));
        assertThrows(java.io.IOException.class,()->AtlasSplitter.unpack(new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB),region));
    }

    @Test void meshUvAndDeformStayInLogicalImageCoordinates() throws Exception {
        var file=fixture();var base=new SkeletonJsonCodec().read(file);
        var attachment=(RegionAttachment)base.attachments().get(0);
        var points=List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(0,1));
        var mesh=new MeshAttachment(attachment.id(),attachment.name(),attachment.slotId(),attachment.source(),points,points,3,List.of(0,1,2));
        var attachments=new ArrayList<>(base.attachments());attachments.set(0,mesh);
        var clip=base.animations().get(0);
        var deform=new io.github.samlinb.openbone.core.animation.DeformTimeline(mesh.id(),List.of(
                new io.github.samlinb.openbone.core.animation.Keyframe(EntityId.create(),0,Collections.nCopies(6,0.),Collections.nCopies(6,io.github.samlinb.openbone.core.animation.Curve.LINEAR)),
                new io.github.samlinb.openbone.core.animation.Keyframe(EntityId.create(),.2,List.of(.25,0.,0.,.5,0.,0.),Collections.nCopies(6,io.github.samlinb.openbone.core.animation.Curve.LINEAR))));
        var animated=new io.github.samlinb.openbone.core.animation.AnimationClip(clip.id(),clip.name(),clip.timelines(),List.of(deform),clip.constraintTracks(),clip.discrete());
        var d=new DocumentSnapshot(base.bones(),base.slots(),attachments,List.of(animated),base.constraints(),base.resources());
        var after=AtlasSplitter.split(file,d).document();var result=(MeshAttachment)after.attachments().get(0);
        assertEquals(mesh.uvs(),result.uvs());assertEquals(mesh.vertices(),result.vertices());assertEquals(mesh.triangles(),result.triangles());
        assertEquals(d.animations(),after.animations());assertFalse(after.resources().atlas().containsKey(mesh.id()));
    }

    @Test void repeatedExtractionUsesNewFoldersAndDoesNotOverwritePreviousOutput() throws Exception {
        var file=fixture();var d=new SkeletonJsonCodec().read(file);
        var first=AtlasSplitter.split(file,d); Path image=Path.of(first.document().attachments().get(0).source());
        byte[] original=Files.readAllBytes(image);var second=AtlasSplitter.split(file,d);
        assertNotEquals(first.directory(),second.directory());assertArrayEquals(original,Files.readAllBytes(image));
    }

    @Test void failureAfterWritingAnImageCleansOnlyItsNewDirectory() throws Exception {
        var file=fixture();var d=new SkeletonJsonCodec().read(file);
        var attachments=new ArrayList<>(d.attachments());
        attachments.set(1,MeshEdits.source(attachments.get(1),temp.resolve("missing.png").toString()));
        var broken=new DocumentSnapshot(d.bones(),d.slots(),attachments,d.animations(),d.constraints(),d.resources());
        assertTrue(AtlasSplitter.available(file,broken));
        assertThrows(java.io.IOException.class,()->AtlasSplitter.split(file,broken));
        try(var files=Files.list(temp)) { assertEquals(3,files.count()); }
        assertEquals(37,new SkeletonJsonCodec().read(file).attachments().size());
    }
}
