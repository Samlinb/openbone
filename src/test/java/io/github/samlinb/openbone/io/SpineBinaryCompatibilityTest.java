package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent wire fixtures exercise byte ordering, not writer/reader agreement. */
class SpineBinaryCompatibilityTest {
    @TempDir Path temp;
    private static final class Wire {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final DataOutputStream out=new DataOutputStream(bytes);
        void v(int n)throws IOException {while((n&~127)!=0){out.writeByte((n&127)|128);n>>>=7;}out.writeByte(n);}
        void s(String text)throws IOException {if(text==null){v(0);return;}byte[] raw=text.getBytes(StandardCharsets.UTF_8);v(raw.length+1);out.write(raw);}
        void f(double... values)throws IOException {for(double d:values)out.writeFloat((float)d);}
        void head()throws IOException {
            out.writeLong(0);s("4.0.21");f(0,0,0,0);out.writeBoolean(false);
            v(3);s("mesh");s("linked");s("image");
            v(1);s("root");f(0,0,0,1,1,0,0,0);v(0);out.writeBoolean(false);
            v(1);s("slot");v(0);out.writeInt(-1);out.writeInt(-1);v(1);v(0);
        }
        void constraints(boolean ik)throws IOException {
            v(ik?1:0);
            if(ik){s("ik");v(0);out.writeBoolean(false);v(1);v(0);v(0);f(1,0);out.writeByte(1);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);}
            v(0);v(0);
        }
        void mesh(int vertices,boolean link)throws IOException {
            v(1);v(0);v(link?2:1);
            v(1);v(0);out.writeByte(2);v(3);out.writeInt(-1);v(vertices);
            for(int i=0;i<vertices;i++)f((1+Math.cos(i*2*Math.PI/vertices))*.5,(1+Math.sin(i*2*Math.PI/vertices))*.5);
            v((vertices-2)*3);for(int i=1;i+1<vertices;i++){out.writeShort(0);out.writeShort(i);out.writeShort(i+1);}
            out.writeBoolean(false);for(int i=0;i<vertices;i++)f(Math.cos(i*2*Math.PI/vertices),Math.sin(i*2*Math.PI/vertices));v(vertices);
            if(link){v(2);v(0);out.writeByte(3);v(3);out.writeInt(-1);v(0);v(1);out.writeBoolean(true);}
            v(0);v(0);
        }
        void animation(String name)throws IOException {v(1);s(name);v(1);v(0);v(0);}
        void noConstraintTracks()throws IOException {v(0);v(0);v(0);}
        void deform(int offset)throws IOException {
            v(1);v(0);v(1);v(0);v(1);v(1);v(1);v(0);f(0);v(2);v(offset);f(.25,-.5);
            v(0);v(0);
        }
    }

    @Test void sparseDeformOffsetIndexesAttachmentRatherThanUnreadFileBytes()throws Exception {
        var w=new Wire();w.head();w.constraints(false);w.mesh(66,false);w.animation("deform");w.noConstraintTracks();w.deform(130);
        var tree=Spine40Binary.decode(w.bytes.toByteArray()).tree();
        var key=tree.path("animations").path("deform").path("attachments").path("default").path("slot").path("mesh").path("deform").get(0);
        assertEquals(130,key.path("offset").asInt());assertEquals(.25,key.path("vertices").get(0).asDouble());
    }

    @Test void linkedMeshPreservesSharedGeometryAndInheritedDeformKeys()throws Exception {
        var w=new Wire();w.head();w.constraints(false);w.mesh(3,true);w.animation("deform");w.noConstraintTracks();w.deform(4);
        var tree=Spine40Binary.decode(w.bytes.toByteArray()).tree();
        var attachments=tree.path("skins").get(0).path("attachments").path("slot");
        assertEquals("mesh",attachments.path("linked").path("type").asText());
        for(String field:List.of("vertices","uvs","triangles","hull"))assertEquals(attachments.path("mesh").get(field),attachments.path("linked").get(field));
        var deform=tree.path("animations").path("deform").path("attachments").path("default").path("slot");
        assertEquals(deform.path("mesh").path("deform"),deform.path("linked").path("deform"));
    }

    @Test void ikReadsDiscreteFlagsBeforeNextFrameAndOutgoingBezier()throws Exception {
        var w=new Wire();w.head();w.constraints(true);w.v(0);w.v(0);w.v(0);w.animation("ik");
        w.v(1);w.v(0);w.v(2);w.v(2);
        w.f(0,.25,1);w.out.writeByte(1);w.out.writeBoolean(false);w.out.writeBoolean(true);
        w.f(1,.75,2);w.out.writeByte(2);w.f(.2,.3,.8,.7,.2,1.2,.8,1.8);
        w.out.writeByte(-1);w.out.writeBoolean(true);w.out.writeBoolean(false);
        w.v(0);w.v(0);w.v(0);w.v(0);w.v(0);
        var keys=Spine40Binary.decode(w.bytes.toByteArray()).tree().path("animations").path("ik").path("ik").path("ik");
        assertEquals(2,keys.size());assertTrue(keys.get(0).path("stretch").asBoolean());
        assertEquals(.75,keys.get(1).path("mix").asDouble());assertFalse(keys.get(1).path("bendPositive").asBoolean());
        assertTrue(keys.get(1).path("compress").asBoolean());assertEquals(8,keys.get(0).path("curve").size());
    }

    @Test void actualNinjaFourZeroSamplesDecodeWithoutMutatingSources()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openbone.verifyNinja"));
        Path root=Path.of("E:/resource/ninja3-assets-047097ea/骨骼动画分类");
        for(String suffix:List.of("qifu__7cbef9414b/qifu.skel","xiujian__6da64bbf46/xiujian.skel")) {
            Path source;
            try(var paths=Files.walk(root)){source=paths.filter(p->p.toString().replace('\\','/').endsWith(suffix)).findFirst().orElseThrow();}
            byte[] original=Files.readAllBytes(source);var decoded=Spine40Binary.decode(original);
            assertFalse(decoded.tree().path("animations").isEmpty());assertArrayEquals(original,Files.readAllBytes(source));
        }
    }

    @Test void completeOrderedOverrideArchivesBothSourceTracksAndRetainsDuration()throws Exception {
        var w=new Wire();w.head();w.constraints(false);w.mesh(3,false);
        w.v(1);w.s("duplicates");w.v(2);w.v(0);w.v(2);
        w.v(0);w.v(1);w.out.writeByte(0);w.v(2);w.v(0);w.f(0,1);w.f(2,3);w.out.writeByte(0);
        w.v(0);w.v(1);w.out.writeByte(0);w.v(2);w.v(0);w.f(0,4);w.f(1,5);w.out.writeByte(0);
        w.noConstraintTracks();w.v(0);w.v(0);w.v(0);
        byte[] bytes=w.bytes.toByteArray();var decoded=Spine40Binary.decode(bytes);
        var keys=decoded.tree().path("animations").path("duplicates").path("bones").path("root").path("rotate");
        assertEquals(3,keys.size());assertEquals(4,keys.get(0).path("value").asDouble());
        assertEquals(2,keys.get(2).path("time").asDouble());assertEquals(5,keys.get(2).path("value").asDouble());
        assertEquals(1,decoded.importArchive().size());
        var archive=new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded.importArchive().values().iterator().next());
        assertEquals(1,archive.path("overriddenTrack").get(0).path("value").asDouble());
        assertEquals(3,archive.path("overriddenTrack").get(1).path("value").asDouble());
        Path binary=temp.resolve("duplicates.skel");Files.write(binary,bytes);
        var codec=new SkeletonJsonCodec();var model=codec.read(binary);
        assertEquals(decoded.importArchive(),model.resources().metadata().importArchive());
        assertTrue(codec.warnings().stream().anyMatch(s->s.contains("import archive")));
        var restored=new ProjectCodec().decode(new ProjectCodec().encode(model));
        assertEquals(model.resources().metadata().importArchive(),restored.resources().metadata().importArchive());
        assertArrayEquals(bytes,Files.readAllBytes(binary));
    }

    @Test void approvedActualThreeTwoRegionAnimationUsesItsOriginalWireLayout()throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openbone.verifyNinja"));
        Path file=Path.of("E:/resource/ninja3-assets-047097ea/骨骼动画分类/10_其他骨骼动画/long__a4d4bc58c7/long.skel");
        byte[] source=Files.readAllBytes(file);
        var risk=assertThrows(SpineVersionRiskException.class,()->Spine40Binary.decode(source));
        var decoded=Spine40Binary.decode(source,risk.selection().approval());
        assertEquals("3.2.01",decoded.sourceVersion());assertFalse(decoded.selection().known());
        assertEquals(2,decoded.tree().path("bones").size());assertEquals(1,decoded.tree().path("slots").size());
        assertEquals(List.of("up"),new ArrayList<String>(){{decoded.tree().path("animations").fieldNames().forEachRemaining(this::add);}});
        var model=new SkeletonJsonCodec().read(file,file.getParent(),risk.selection().approval());
        assertEquals(1,model.animations().size());assertEquals(4,model.animations().get(0).duration(),1e-5);
        assertEquals(2,model.resources().events().size());assertArrayEquals(source,Files.readAllBytes(file));
    }
}
