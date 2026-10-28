package io.github.samlinb.openbone.headless;

import io.github.samlinb.openbone.application.AnimationEdits;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP -> export -> separate JVM -> real GL -> image -> undo chain. */
@EnabledIfSystemProperty(named="openbone.verifyRuntime",matches="true")
class HeadlessRuntimeTest {
    @TempDir Path temp;

    @Test void exportedRuntimePreviewTracksRevisionAndUndo() throws Exception {
        EntityId bone=EntityId.create(),slot=EntityId.create(),attachment=EntityId.create(),animation=EntityId.create();
        var texture=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)texture.setRGB(x,y,x<8?0xffff8030:0xff40b0e0);
        Path image=temp.resolve("texture.png");ImageIO.write(texture,"PNG",image.toFile());
        var model=new DocumentSnapshot(List.of(new Bone(bone,"root",null,Transform.IDENTITY,60)),
                List.of(new Slot(slot,"body",bone,attachment)),List.of(new RegionAttachment(attachment,"body",slot,image.toString(),Transform.IDENTITY,160,60)),
                List.of(new AnimationClip(animation,"turn",List.of())));
        model=AnimationEdits.put(model,animation,bone,Channel.ROTATE,0,List.of(0.));
        model=AnimationEdits.put(model,animation,bone,Channel.ROTATE,1,List.of(90.));
        Path source=temp.resolve("source.json");Files.write(source,new ProjectCodec().encode(model));
        Path runtime=Path.of(System.getProperty("openbone.runtimeJar","runtime-preview/target/openbone-runtime-preview.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(runtime),runtime.toString());
        long runtimePid;
        try(var service=new HeadlessApplication(source,0,null,runtime)) {
            var client=new HeadlessMcpTest();client.temp=temp;client.service=service;client.animation=animation;client.bone=bone;
            var before=client.context();String revision=before.path("revision").asText();
            assertTrue(before.path("capabilities").path("cleanPreview").asBoolean());
            var first=capture(client,revision,0);var second=capture(client,revision,1);
            runtimePid=second.path("structuredContent").path("data").path("runtimePid").asLong();
            assertTrue(runtimePid>0);
            assertEquals(first.path("structuredContent").path("data").path("runtimePid").asLong(),runtimePid);
            var a=decode(first);var b=decode(second);
            assertNotEquals(Arrays.hashCode(a.getRGB(0,0,768,768,null,0,768)),Arrays.hashCode(b.getRGB(0,0,768,768,null,0,768)));
            assertTrue(nonBackground(a)>1000);assertTrue(nonBackground(b)>1000);
            assertEquals(revision,second.path("structuredContent").path("data").path("revision").asText());
            assertFalse(second.path("structuredContent").path("data").path("glRenderer").asText().isBlank());
            var edit=client.call("openbone_apply_edits",client.key("runtime-edit",revision,.5));
            assertFalse(edit.path("isError").asBoolean(),edit.toString());
            var edited=client.context();
            assertTrue(client.call("openbone_capture_preview",Map.of("animationId",animation.value(),"time",0,"expectedRevision",revision)).path("isError").asBoolean());
            assertFalse(client.call("openbone_undo",Map.of("expectedRevision",edited.path("revision").asText(),"requestId","runtime-undo")).path("isError").asBoolean());
            var restored=capture(client,client.context().path("revision").asText(),1);
            var c=decode(restored);
            assertArrayEquals(b.getRGB(0,0,768,768,null,0,768),c.getRGB(0,0,768,768,null,0,768));
            Path evidence=Path.of("target/runtime-validation").toAbsolutePath();Files.createDirectories(evidence);
            ImageIO.write(a,"PNG",evidence.resolve("time-0.png").toFile());ImageIO.write(b,"PNG",evidence.resolve("time-1.png").toFile());
            HeadlessMcpTest.JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("receipt.json").toFile(),second.path("structuredContent").path("data"));
            if(!System.getProperty("openbone.ffmpeg","").isBlank()) {
                Path movie=temp.resolve("movie");
                var arguments=Map.of("directory",movie.toString(),"animationId",animation.value(),"fps",2,
                        "expectedRevision",client.context().path("revision").asText(),"requestId","runtime-movie");
                var started=client.call("openbone_render_animation",arguments);
                assertFalse(started.path("isError").asBoolean(),started.toString());
                assertEquals(started,client.call("openbone_render_animation",arguments));
                String job=started.path("structuredContent").path("data").path("jobId").asText();
                JsonNode status=null;
                for(int i=0;i<12;i++) {
                    status=client.call("openbone_render_status",Map.of("jobId",job,"waitMs",5000)).path("structuredContent").path("data");
                    if(Set.of("COMPLETE","FAILED","CANCELLED").contains(status.path("status").asText()))break;
                }
                assertEquals("COMPLETE",status.path("status").asText(),status.toString());
                assertEquals(3,status.path("decodedFrameCount").asInt());
                assertTrue(Files.size(movie.resolve("animation.mp4"))>100);
                Files.copy(movie.resolve("animation.mp4"),evidence.resolve("animation.mp4"),StandardCopyOption.REPLACE_EXISTING);
                Files.copy(movie.resolve("proof.json"),evidence.resolve("proof.json"),StandardCopyOption.REPLACE_EXISTING);
                Path cancelled=temp.resolve("cancelled");
                var cancellation=client.call("openbone_render_animation",Map.of("directory",cancelled.toString(),"animationId",animation.value(),"fps",24,
                        "expectedRevision",client.context().path("revision").asText(),"requestId","runtime-cancel"));
                assertFalse(cancellation.path("isError").asBoolean(),cancellation.toString());
                String cancelJob=cancellation.path("structuredContent").path("data").path("jobId").asText();
                assertFalse(client.call("openbone_cancel_render",Map.of("jobId",cancelJob)).path("isError").asBoolean());
                var cancelStatus=client.call("openbone_render_status",Map.of("jobId",cancelJob,"waitMs",5000)).path("structuredContent").path("data");
                assertEquals("CANCELLED",cancelStatus.path("status").asText(),cancelStatus.toString());
                assertFalse(Files.exists(cancelled));
                runtimePid=capture(client,client.context().path("revision").asText(),0)
                        .path("structuredContent").path("data").path("runtimePid").asLong();
                assertTrue(runtimePid>0);
            }
        }
        assertFalse(ProcessHandle.of(runtimePid).map(ProcessHandle::isAlive).orElse(false),"The runtime worker survived service close");
    }

    private static JsonNode capture(HeadlessMcpTest client,String revision,double time) throws Exception {
        var result=client.call("openbone_capture_preview",Map.of("animationId",client.animation.value(),"time",time,"expectedRevision",revision,"fit",true));
        assertFalse(result.path("isError").asBoolean(),result.toString());return result;
    }
    private static BufferedImage decode(JsonNode result) throws Exception {
        for(var content:result.path("content"))if(content.path("type").asText().equals("image"))
            return ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(content.path("data").asText())));
        throw new AssertionError("No actual rendered image");
    }
    private static int nonBackground(BufferedImage image) {
        int count=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0x191f27)count++;
        return count;
    }
}
