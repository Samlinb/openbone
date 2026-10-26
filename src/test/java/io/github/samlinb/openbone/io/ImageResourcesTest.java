package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageResourcesTest {
    @TempDir Path temp;
    DocumentSnapshot fixture(boolean split) throws Exception {
        for(String extension:List.of("json","atlas","png")) {
            Path file=temp.resolve("dandao_1."+extension);
            if(!Files.exists(file)) try(var in=getClass().getResourceAsStream("/spine38/"+file.getFileName())) {Files.copy(in,file);}
        }
        Path json=temp.resolve("dandao_1.json");var d=new SkeletonJsonCodec().read(json);
        return split?AtlasSplitter.split(json,d).document():d;
    }
    Path replacement() throws Exception {
        var image=new BufferedImage(13,7,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<7;y++) for(int x=0;x<13;x++) image.setRGB(x,y,0x8033cc44);
        Path file=temp.resolve("replacement.png");ImageIO.write(image,"PNG",file.toFile());return file;
    }

    @Test void listsElevenLogicalImagesBeforeAndAfterSplittingAndPreviewsTheirPixels() throws Exception {
        var packed=fixture(false);var unpacked=fixture(true);
        var before=ImageResources.list(packed,temp);var after=ImageResources.list(unpacked,temp);
        assertEquals(11,before.size());assertEquals(11,after.size());
        assertEquals(21,after.stream().filter(e->e.name().equals("tx/1")).findFirst().orElseThrow().attachments().size());
        for(int i=0;i<before.size();i++) {
            var a=ImageResources.preview(before.get(i));var b=ImageResources.preview(after.get(i));
            assertEquals(a.getWidth(),b.getWidth());assertEquals(a.getHeight(),b.getHeight());
            assertArrayEquals(a.getRGB(0,0,a.getWidth(),a.getHeight(),null,0,a.getWidth()),b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()));
        }
    }

    @Test void replacingSharedImageKeepsAllAnimationsTransformsAndDimensionsAtEverySample() throws Exception {
        var d=fixture(true);var selected=ImageResources.list(d,temp).stream().filter(e->e.name().equals("tx/1")).findFirst().orElseThrow();
        var originalBytes=Files.readAllBytes(selected.key().source());Path replacement=replacement();
        var after=ImageResources.replace(d,temp,selected,replacement);
        assertEquals(d.bones(),after.bones());assertEquals(d.slots(),after.slots());assertEquals(d.animations(),after.animations());assertEquals(d.constraints(),after.constraints());
        for(int i=0;i<d.attachments().size();i++) {
            var before=d.attachments().get(i);var changed=after.attachments().get(i);
            assertEquals(before,MeshEdits.source(changed,before.source()));
            assertEquals(selected.attachments().contains(before.id())?replacement.toString():before.source(),changed.source());
        }
        for(double time:new double[]{0,.0667,.08335,.1,.2,.2333,.2667,.3,.3333})
            assertEquals(AnimationSampler.world(d,d.animations().get(0).id(),time,false),AnimationSampler.world(after,after.animations().get(0).id(),time,false));
        assertArrayEquals(originalBytes,Files.readAllBytes(selected.key().source()));
        var history=new CommandHistory(d);history.execute("Replace PNG",Commands.model(v->after));
        assertTrue(history.undo());assertEquals(d,history.state().model());assertTrue(history.redo());assertEquals(after,history.state().model());
        var project=new ProjectDocument(after);Path file=temp.resolve("saved.json");project.savePortable(file);
        var loaded=new ProjectCodec().read(file);assertEquals(after.animations(),loaded.animations());
        var copied=loaded.attachments().stream().filter(a->a.id().equals(selected.attachments().get(0))).findFirst().orElseThrow();
        assertArrayEquals(Files.readAllBytes(replacement),Files.readAllBytes(ResourcePaths.resolve(temp,copied.source())));
    }

    @Test void replacingOnePackedTileDetachesOnlyItsReferencesFromAtlas() throws Exception {
        var d=fixture(false);var selected=ImageResources.list(d,temp).stream().filter(e->e.name().equals("tx/1")).findFirst().orElseThrow();
        var after=ImageResources.replace(d,temp,selected,replacement());
        assertEquals(16,after.resources().atlas().size());
        for(var a:after.attachments()) assertEquals(!selected.attachments().contains(a.id()),after.resources().atlas().containsKey(a.id()));
        assertEquals(d.animations(),after.animations());assertEquals(d.resources().skins(),after.resources().skins());
    }

    @Test void invalidReplacementAndStaleResourceDoNotMutateProject() throws Exception {
        var d=fixture(true);var selected=ImageResources.list(d,temp).get(0);Path bad=temp.resolve("bad.png");Files.writeString(bad,"bad");
        assertThrows(java.io.IOException.class,()->ImageResources.replace(d,temp,selected,bad));
        Path file=replacement();var after=ImageResources.replace(d,temp,selected,file);
        assertThrows(java.io.IOException.class,()->ImageResources.replace(after,temp,selected,file));
        assertEquals(14,d.animations().get(0).discrete().colors().size());
    }

    @Test void weightedMeshAndDeformArePreservedWhenReplacingImage() throws Exception {
        var base=fixture(true);var r=(RegionAttachment)base.attachments().get(0);
        var converted=MeshEdits.convert(base,r.id());var mesh=(MeshAttachment)converted.attachments().get(0);
        var weighted=mesh.withWeights(mesh.vertices().stream().map(v->List.of(new Influence(base.bones().get(0).id(),v,1))).toList());
        var attachments=new ArrayList<>(converted.attachments());attachments.set(0,weighted);
        var clip=base.animations().get(0);var deform=new DeformTimeline(mesh.id(),List.of(new Keyframe(EntityId.create(),0,
                Collections.nCopies(mesh.vertices().size()*2,.1),Collections.nCopies(mesh.vertices().size()*2,Curve.LINEAR))));
        var animated=new AnimationClip(clip.id(),clip.name(),clip.timelines(),List.of(deform),clip.constraintTracks(),clip.discrete());
        var d=new DocumentSnapshot(base.bones(),base.slots(),attachments,List.of(animated),base.constraints(),base.resources());
        var selected=ImageResources.list(d,temp).stream().filter(e->e.attachments().contains(mesh.id())).findFirst().orElseThrow();
        var after=ImageResources.replace(d,temp,selected,replacement());var changed=(MeshAttachment)after.attachments().get(0);
        assertEquals(weighted,changed.withSource(weighted.source()));assertEquals(d.animations(),after.animations());
    }
}
