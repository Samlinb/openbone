package io.github.samlinb.openbone.application;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.core.model.EntityId;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMediaServiceTest {
    @TempDir Path temp;
    static final Path FFMPEG=Path.of("D:/Program Files/Python/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe");
    final EntityId animation=EntityId.create();String prior;
    @BeforeEach void setup(){Assumptions.assumeTrue(Files.isRegularFile(FFMPEG));prior=System.getProperty("openbone.ffmpeg");System.setProperty("openbone.ffmpeg",FFMPEG.toString());}
    @AfterEach void restore(){if(prior==null)System.clearProperty("openbone.ffmpeg");else System.setProperty("openbone.ffmpeg",prior);}
    AnimationMediaService.Request request(Path path,double duration){return new AnimationMediaService.Request(UUID.randomUUID().toString(),"scope:1",animation,path,24,duration,true,duration*.5);}
    static class Frames implements AnimationMediaService.Source {
        volatile String revision="scope:1";volatile boolean identityWrong;final List<Double> times=new CopyOnWriteArrayList<>();
        final List<Boolean> fits=new CopyOnWriteArrayList<>();
        public void checkRevision(String expected){assertFalse(SwingUtilities.isEventDispatchThread());if(!revision.equals(expected))throw new IllegalStateException("REVISION_CONFLICT");}
        public CompletableFuture<AnimationMediaService.Frame> capture(EntityId id,double time,String revision,boolean fit){
            assertFalse(SwingUtilities.isEventDispatchThread());times.add(time);fits.add(fit);
            var image=new BufferedImage(17,11,BufferedImage.TYPE_INT_RGB);int color=0xff000000|((int)(time*1000)%256)<<16|0x4080;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,color);
            return CompletableFuture.completedFuture(new AnimationMediaService.Frame(image,Map.of("animationId",id.value(),"time",time,"revision",identityWrong?"other":revision)));
        }
        public String description(){return "Synthetic unit-test source (not a viewport acceptance result)";}
    }
    Map<String,Object> await(AnimationMediaService service,String id)throws Exception{
        for(int i=0;i<10;i++){var status=service.status(id,5000);if(Set.of("COMPLETE","FAILED","CANCELLED").contains(status.get("status")))return status;}
        throw new AssertionError("media job did not finish");
    }
    @Test void encodesEndpointVerifiesDecodedFramesAndPublishesAtomically()throws Exception{
        try(var service=new AnimationMediaService()){
            var source=new Frames();var request=request(temp.resolve("animation"),.1);
            var start=service.start(request,source);String id=(String)start.get("jobId");
            assertEquals(id,service.start(request,source).get("jobId"));
            var result=await(service,id);assertEquals("COMPLETE",result.get("status"),result.toString());
            assertEquals(4,result.get("frameCount"));assertEquals(4,result.get("decodedFrameCount"));
            assertTrue((Integer)result.get("uniqueFrameCount")>=3);
            assertEquals(.1,source.times.get(source.times.size()-1));
            assertEquals(1,source.fits.stream().filter(Boolean::booleanValue).count());
            assertTrue(Files.size(request.directory().resolve("animation.mp4"))>100);
            assertTrue(Files.isRegularFile(request.directory().resolve("preview.png")));
            assertTrue(Files.readString(request.directory().resolve("proof.json")).contains("\"decodedFrameCount\" : 4"));
            assertThrows(Exception.class,()->service.start(request(request.directory(),.1),source));
        }
    }
    @Test void zeroDurationHoldsForOneSecondWithoutRepeatedGlCaptures()throws Exception{
        try(var service=new AnimationMediaService()){
            var source=new Frames();var start=service.start(request(temp.resolve("pose"),0),source);
            var result=await(service,(String)start.get("jobId"));assertEquals("COMPLETE",result.get("status"),result.toString());
            assertEquals(24,result.get("frameCount"));assertEquals(24,result.get("decodedFrameCount"));
            assertEquals(1,result.get("uniqueFrameCount"));assertEquals(1,source.times.size());
        }
    }
    @Test void cancellationBusyIdentityAndRevisionFailuresNeverPublishOutputs()throws Exception{
        try(var service=new AnimationMediaService()){
            var pending=new CompletableFuture<AnimationMediaService.Frame>();var arrived=new CountDownLatch(1);
            var source=new Frames(){public CompletableFuture<AnimationMediaService.Frame> capture(EntityId id,double time,String revision,boolean fit){arrived.countDown();return pending;}};
            var request=request(temp.resolve("cancel"),1);var start=service.start(request,source);String id=(String)start.get("jobId");
            assertTrue(arrived.await(5,TimeUnit.SECONDS));assertTrue(service.busy());
            assertThrows(Exception.class,()->service.start(request(temp.resolve("other"),1),source));
            service.cancel(id);assertEquals("CANCELLED",await(service,id).get("status"));assertFalse(Files.exists(request.directory()));
            var wrong=new Frames();wrong.identityWrong=true;var failed=service.start(request(temp.resolve("identity"),1),wrong);
            assertEquals("FAILED",await(service,(String)failed.get("jobId")).get("status"));assertFalse(Files.exists(temp.resolve("identity")));
            var changed=new Frames(){int checks;public void checkRevision(String expected){if(++checks>3)revision="scope:2";super.checkRevision(expected);}};
            var conflict=service.start(request(temp.resolve("revision"),1),changed);
            assertEquals("FAILED",await(service,(String)conflict.get("jobId")).get("status"));assertFalse(Files.exists(temp.resolve("revision")));
            try(var children=Files.list(temp)){assertFalse(children.anyMatch(p->p.getFileName().toString().startsWith(".openbone-media-")));}
        }
    }
    @Test void existingDirectoryAndUnconfiguredEncoderRefuseWithoutTouchingFiles()throws Exception{
        try(var service=new AnimationMediaService()){
            Path existing=temp.resolve("existing");Files.createDirectory(existing);Files.writeString(existing.resolve("keep.txt"),"keep");
            assertThrows(Exception.class,()->service.start(request(existing,1),new Frames()));assertEquals("keep",Files.readString(existing.resolve("keep.txt")));
            System.clearProperty("openbone.ffmpeg");assertThrows(Exception.class,()->service.start(request(temp.resolve("unconfigured"),1),new Frames()));assertFalse(Files.exists(temp.resolve("unconfigured")));
            assertThrows(Exception.class,()->service.status("unknown",0));
        }
    }
}
