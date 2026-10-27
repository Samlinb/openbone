package io.github.samlinb.openbone.desktop;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.CommandHistory;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageResourcePanelPerformanceTest {
    @TempDir Path temp;
    @Test void blockedPageDecoderDoesNotBlockRefreshLayoutOrRepeatedPlaybackRefresh()throws Exception {
        Path source=temp.resolve("page.png");ImageIO.write(new BufferedImage(512,1,BufferedImage.TYPE_INT_ARGB),"PNG",source.toFile());
        var bone=new Bone(EntityId.create(),"root",null,Transform.IDENTITY,0);
        var slots=new ArrayList<Slot>();var attachments=new ArrayList<Attachment>();var atlas=new HashMap<EntityId,AtlasRegion>();
        for(int i=0;i<305;i++) {
            var slot=EntityId.create();var id=EntityId.create();
            slots.add(new Slot(slot,"slot-"+i,bone.id(),id));
            attachments.add(new RegionAttachment(id,"image-"+i,slot,source.toString(),Transform.IDENTITY,1,1));
            atlas.put(id,new AtlasRegion(512,1,i,0,1,1,1,1,0,0,false));
        }
        var initial=new DocumentSnapshot(List.of(bone),slots,attachments);
        var model=initial.withResources(new Resources(List.of(),"default",List.of(),atlas));var history=new CommandHistory(model);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();var factories=new AtomicInteger();
        var panelRef=new AtomicReference<ImageResourcePanel>();var failure=new AtomicReference<Exception>();
        try {
            SwingUtilities.invokeAndWait(()-> {
                var panel=new ImageResourcePanel("perf.images",()->history,()->temp,()->true,()->{},failure::set,id->{},()-> {
                    factories.incrementAndGet();
                    return new ImageResources.PreviewCache(){@Override protected BufferedImage read(Path path)throws java.io.IOException {
                        assertFalse(SwingUtilities.isEventDispatchThread());reads.incrementAndGet();entered.countDown();
                        try {if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("test decoder release timed out");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                        return super.read(path);
                    }};
                });panelRef.set(panel);
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var responsive=new CountDownLatch(1);
            SwingUtilities.invokeLater(()-> {
                var panel=panelRef.get();panel.setSize(300,650);ImageResourcePanelTest.layout(panel);
                var list=(JList<?>)ImageResourcePanelTest.find(panel,"perf.images.list");
                for(int i=0;i<100;i++)panel.refresh();
                assertEquals(305,list.getModel().getSize());assertEquals(1,factories.get());responsive.countDown();
            });
            assertTrue(responsive.await(2,TimeUnit.SECONDS),"EDT must remain responsive while PNG decoding is blocked");
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);var done=new AtomicBoolean();
            while(System.nanoTime()<deadline&&!done.get()) {
                SwingUtilities.invokeAndWait(()->done.set(((JLabel)ImageResourcePanelTest.find(panelRef.get(),"perf.images.details")).getText().matches("\\d+ × \\d+.*")));
                if(!done.get())Thread.sleep(10);
            }
            assertTrue(done.get(),"selected image dimensions should appear asynchronously");assertEquals(1,reads.get());assertNull(failure.get());
        } finally {release.countDown();}
    }
}
