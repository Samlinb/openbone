package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Version-specific wire contracts, with expected curves/poses independent of the importer. */
class SpineImportVersionTest {
    @TempDir Path temp;

    @Test void actualUserFilesHave40HeaderAndPreserveSourceVersion() throws Exception {
        Path source=Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel");
        byte[] data=Files.readAllBytes(source);
        // 4.0: eight raw hash bytes, varint string byte length including terminator, version UTF-8.
        assertEquals(7, data[8]);
        assertEquals("4.0.21", new String(data,9,6,StandardCharsets.UTF_8));
        assertArrayEquals(data, Files.readAllBytes(source.resolveSibling("Ninjahouse_xiaohei.skel.bytes")));
        Path userSource=Path.of("res/xiaohei/Ninjahouse_xiaohei.skel");
        if(Files.isRegularFile(userSource)) assertArrayEquals(data,Files.readAllBytes(userSource));
        var decoded=Spine40Binary.decode(data);
        assertEquals("4.0.21", decoded.sourceVersion());
        assertEquals(SkeletonJsonCodec.VERSION, decoded.tree().path("skeleton").path("spine").asText());
        var codec=new SkeletonJsonCodec(); codec.read(source);
        assertEquals(Optional.of("4.0.21"),codec.sourceVersion());
    }

    @Test void binary40KeepsAbsoluteBezierSplitScaleAndRgbTags() throws Exception {
        Path path=temp.resolve("probe.skel"); Files.write(path,binary40("4.0.21"));
        var codec=new SkeletonJsonCodec(); var model=codec.read(path);
        assertEquals(Optional.of("4.0.21"),codec.sourceVersion());
        assertTrue(codec.warnings().isEmpty(),codec.warnings().toString());
        var clip=model.animations().get(0);
        var rotate=clip.timelines().stream().filter(t->t.channel()==Channel.ROTATE).findFirst().orElseThrow();
        assertEquals(List.of(2.0,4.0),rotate.keys().stream().map(Keyframe::time).toList());
        assertEquals(new Curve(Curve.Kind.BEZIER,.25,0,.75,1),rotate.keys().get(0).curves().get(0));
        assertEquals(20,rotate.sample(3).orElseThrow().get(0),1e-9);
        // Tag 5 is SCALEX in 4.0. Applying 3.8's tag table/normalizer would lose this track.
        var scale=clip.timelines().stream().filter(t->t.channel()==Channel.SCALE).findFirst().orElseThrow();
        assertEquals(List.of(1.0,1.0),scale.sample(3).orElseThrow());
        assertEquals(List.of(2.0,1.0),scale.sample(4).orElseThrow());
        var pose=AnimationSampler.pose(model,clip.id(),3,false).get(model.bones().get(0).id());
        assertEquals(25,pose.rotation(),1e-9); assertEquals(2,pose.scaleX(),1e-9); assertEquals(3,pose.scaleY(),1e-9);
        // Slot tag 2 is RGB in 4.0, while it means two-color in 3.8.
        var rgb=clip.discrete().colors().get(0);
        assertEquals(SlotColorTimeline.Channel.RGB,rgb.channel());
        assertEquals(List.of(1.0,0.0,0.0),rgb.keys().get(0).values());
        assertEquals(List.of(0.0,1.0,0.0),rgb.keys().get(1).values());
    }

    @Test void json38ConvertsRelativeBezierAndAngleWithoutChanging40Interpretation() throws Exception {
        byte[] json38="""
            {"skeleton":{"spine":"3.8.75"},"bones":[{"name":"root","rotation":5,"scaleX":2,"scaleY":3}],
             "slots":[],"skins":[],"animations":{"probe":{"bones":{"root":{
               "rotate":[{"time":2,"angle":10,"curve":[0.25,0,0.75,1]},{"time":4,"angle":30}],
               "scale":[{"time":2,"x":1,"y":1,"curve":"stepped"},{"time":4,"x":2,"y":1}]
             }}}}}
            """.getBytes(StandardCharsets.UTF_8);
        var codec=new SkeletonJsonCodec();var old=codec.decode(json38,temp);
        assertEquals(Optional.of("3.8.75"),codec.sourceVersion());
        Path binary=temp.resolve("probe.skel.bytes");Files.write(binary,binary40("4.0.21"));
        var current=codec.read(binary);
        assertEquals(Optional.of("4.0.21"),codec.sourceVersion());
        for(double time:new double[]{0,2,2.5,3,3.5,4}) {
            var expected=AnimationSampler.pose(old,old.animations().get(0).id(),time,false).get(old.bones().get(0).id());
            var actual=AnimationSampler.pose(current,current.animations().get(0).id(),time,false).get(current.bones().get(0).id());
            assertEquals(expected,actual,"Different source versions must preserve the same intended pose at "+time);
        }
        // A version label is not a converter. Refuse an unsupported binary header before reading its body.
        Files.write(binary,binary40("3.8.75"));
        assertThrows(ProjectFormatException.class,()->codec.read(binary));
        assertTrue(codec.sourceVersion().isEmpty());
        // JSON support is still 3.8.x and strict 4.2.22; 4.0 JSON cannot silently enter the 3.8 normalizer.
        byte[] mislabeled=new String(json38,StandardCharsets.UTF_8).replace("3.8.75","4.0.21").getBytes(StandardCharsets.UTF_8);
        assertThrows(ProjectFormatException.class,()->codec.decode(mislabeled,temp));
        assertTrue(codec.sourceVersion().isEmpty());
    }

    private static byte[] binary40(String version) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeLong(0);text(out,version);floats(out,0,0,0,0);out.writeBoolean(false);
        out.writeByte(0); // String table.
        out.writeByte(1);text(out,"root");floats(out,5,0,0,2,3,0,0,1);out.writeByte(0);out.writeBoolean(false);
        out.writeByte(1);text(out,"slot");out.writeByte(0);out.writeInt(-1);out.writeInt(-1);out.writeByte(0);out.writeByte(0);
        for(int i=0;i<6;i++)out.writeByte(0); // Setup constraints, default skin, other skins, events.
        out.writeByte(1);text(out,"probe");out.writeByte(3); // Animation and declared timeline count.
        out.writeByte(1);out.writeByte(0);out.writeByte(1); // Slot group, slot index, one RGB timeline.
        out.writeByte(2);out.writeByte(2);out.writeByte(0); // RGB tag, frames, Bezier capacity.
        out.writeFloat(2);out.write(new byte[]{(byte)255,0,0});
        out.writeFloat(4);out.write(new byte[]{0,(byte)255,0});out.writeByte(0);
        out.writeByte(1);out.writeByte(0);out.writeByte(2); // Bone group, root, two timelines.
        out.writeByte(0);out.writeByte(2);out.writeByte(1); // Rotate tag, frames, Bezier capacity.
        floats(out,2,10,4,30);out.writeByte(2);floats(out,2.5f,10,3.5f,30);
        out.writeByte(5);out.writeByte(2);out.writeByte(0); // SCALEX tag, frames, Bezier capacity.
        floats(out,2,1,4,2);out.writeByte(1); // Stepped.
        for(int i=0;i<6;i++)out.writeByte(0); // Constraint timelines, deform, order, events.
        return bytes.toByteArray();
    }
    private static void text(DataOutputStream out,String value)throws IOException {
        byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeByte(data.length+1);out.write(data);
    }
    private static void floats(DataOutputStream out,float... values)throws IOException {for(float value:values)out.writeFloat(value);}
}
