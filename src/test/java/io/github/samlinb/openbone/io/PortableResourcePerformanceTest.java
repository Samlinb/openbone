package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;
import static org.junit.jupiter.api.Assertions.*;

class PortableResourcePerformanceTest {
    @TempDir Path temp;
    private DocumentSnapshot model(List<String> sources) {
        var bone=new Bone(EntityId.create(),"root",null,Transform.IDENTITY,0);var slot=EntityId.create();
        var attachments=new ArrayList<Attachment>();
        for(int i=0;i<sources.size();i++)attachments.add(new RegionAttachment(EntityId.create(),"image-"+i,slot,sources.get(i),Transform.IDENTITY,1,1));
        return new DocumentSnapshot(List.of(bone),List.of(new Slot(slot,"images",bone.id(),attachments.get(0).id())),attachments);
    }
    private Path png(String name,int color)throws Exception {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)image.setRGB(x,y,color);
        Path path=temp.resolve(name);ImageIO.write(image,"PNG",path.toFile());return path;
    }

    @Test void threeHundredFiveReferencesDecodeAndPublishOnePngSnapshot()throws Exception {
        Path source=png("page.png",0x804488cc),duplicate=temp.resolve("duplicate.png");Files.copy(source,duplicate);
        var sources=new ArrayList<String>();for(int i=0;i<304;i++)sources.add(i%2==0?"./page.png":source.toString());sources.add(duplicate.toString());
        var original=model(sources);byte[] pixels=Files.readAllBytes(source);var validations=new AtomicInteger();
        Path target=temp.resolve("actor.openbone.json");
        var portable=ResourcePaths.portable(original,temp,target,bytes->{validations.incrementAndGet();assertArrayEquals(pixels,bytes);ImageAssets.decodePng(bytes);});
        assertEquals(1,validations.get());assertEquals(305,portable.attachments().size());
        assertEquals(1,portable.attachments().stream().map(Attachment::source).distinct().count());
        try(var files=Files.list(temp.resolve("actor.openbone.json.assets"))){assertEquals(1,files.count());}
        Files.write(target,new ProjectCodec().encode(portable));var restored=new ProjectCodec().read(target);
        for(var attachment:restored.attachments())assertArrayEquals(pixels,Files.readAllBytes(ResourcePaths.resolve(temp,attachment.source())));
        assertEquals(original.bones(),restored.bones());assertEquals(original.animations(),restored.animations());
        assertArrayEquals(pixels,Files.readAllBytes(source));assertArrayEquals(pixels,Files.readAllBytes(duplicate));
    }

    @Test void eachSaveRevalidatesChangedSourcesAndRejectsCorruptedContentAddressedTargets()throws Exception {
        Path source=png("changing.png",0xff112233),target=temp.resolve("changing.json");var original=model(List.of(source.toString()));var validations=new AtomicInteger();
        var first=ResourcePaths.portable(original,temp,target,bytes->{validations.incrementAndGet();ImageAssets.decodePng(bytes);});
        png("changing.png",0xff445566);
        var second=ResourcePaths.portable(original,temp,target,bytes->{validations.incrementAndGet();ImageAssets.decodePng(bytes);});
        assertEquals(2,validations.get());assertNotEquals(first.attachments().get(0).source(),second.attachments().get(0).source());
        Path copied=ResourcePaths.resolve(temp,second.attachments().get(0).source());Files.writeString(copied,"corrupt");
        assertThrows(java.io.IOException.class,()->ResourcePaths.portable(original,temp,target));
    }

    @Test void invalidLaterImagePreservesExistingProjectAndLiveDocumentState()throws Exception {
        Path source=png("good.png",0xff123456),target=temp.resolve("existing.json"),invalid=temp.resolve("bad.png");Files.writeString(invalid,"not a PNG");
        new ProjectDocument(model(List.of(source.toString()))).savePortable(target);byte[] existing=Files.readAllBytes(target),original=Files.readAllBytes(source);
        var document=new ProjectDocument(model(List.of(source.toString(),invalid.toString())));var state=document.history().state();
        String revision=document.history().revision();boolean dirty=document.history().isDirty();var path=document.path();
        assertThrows(java.io.IOException.class,()->document.savePortable(target));
        assertArrayEquals(existing,Files.readAllBytes(target));assertArrayEquals(original,Files.readAllBytes(source));
        assertSame(state,document.history().state());assertEquals(revision,document.history().revision());assertEquals(dirty,document.history().isDirty());assertEquals(path,document.path());
    }
}
