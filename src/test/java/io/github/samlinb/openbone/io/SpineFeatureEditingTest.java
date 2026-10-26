package io.github.samlinb.openbone.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.*;
import static org.junit.jupiter.api.Assertions.*;

class SpineFeatureEditingTest {
    @TempDir Path temp;
    public static final String SOURCE = """
        {"skeleton":{"spine":"3.8.55"},"bones":[{"name":"root","rotation":10,"shearX":5,"shearY":-5}],
        "slots":[{"name":"clip","bone":"root","attachment":"clip"},{"name":"bounds","bone":"root","attachment":"box"}],
        "skins":[{"name":"default","attachments":{
        "clip":{"clip":{"type":"clipping","vertexCount":4,"vertices":[-20,-20,20,-20,20,20,-20,20],"end":"bounds","color":"123456ff"}},
        "bounds":{"box":{"type":"boundingbox","vertexCount":4,"vertices":[0,0,10,0,10,10,0,10],"color":"ab3210ff"}}}}],
        "animations":{"bend":{"bones":{"root":{
        "shear":[{"x":0,"y":0,"curve":0.9,"c2":0.1,"c3":0.2,"c4":0.9},{"time":1,"x":30,"y":-20}],
        "translate":[{"x":0,"y":0},{"time":1,"x":4,"y":6}],
        "rotate":[{"angle":0},{"time":1,"angle":40}],
        "scale":[{"x":1,"y":1},{"time":1,"x":2,"y":3}]}}}}}
        """;
    DocumentSnapshot sample() throws Exception {
        var codec = new SkeletonJsonCodec(); var d = codec.decode(SOURCE.getBytes(StandardCharsets.UTF_8), temp);
        assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString()); return d;
    }
    static void matrix(Affine2 a, Affine2 b) {
        assertEquals(a.a(),b.a(),1e-10); assertEquals(a.b(),b.b(),1e-10); assertEquals(a.c(),b.c(),1e-10);
        assertEquals(a.d(),b.d(),1e-10); assertEquals(a.tx(),b.tx(),1e-10); assertEquals(a.ty(),b.ty(),1e-10);
    }

    @Test void shearSamplingCombinesAllFourChannelsAndSupportsSeekAndLoop() throws Exception {
        var d = sample(); var clip = d.animations().get(0); var bone = d.bones().get(0);
        var curve = new Curve(Curve.Kind.BEZIER,.9,.1,.2,.9);
        for (double time : List.of(0.,.25,.9,.1,1.,.5)) {
            double p = curve.progress(time);
            var expected = new Transform(4*time,6*time,10+40*time,1+time,1+2*time,5+30*p,-5-20*p);
            var sampled = AnimationSampler.pose(d,clip.id(),time,false).get(bone.id());
            assertEquals(expected.shearX(),sampled.shearX(),1e-10); assertEquals(expected.shearY(),sampled.shearY(),1e-10);
            matrix(expected.matrix(),sampled.matrix());
            matrix(expected.matrix(),AnimationSampler.world(d,clip.id(),time,false).bones().get(bone.id()).matrix());
        }
        assertEquals(AnimationSampler.pose(d,clip.id(),.25,false),AnimationSampler.pose(d,clip.id(),1.25,true));
        assertNotEquals(.25,curve.progress(.25),1e-3);
    }

    @Test void editingKeysCurvesAndSetupIsUndoableAndDoesNotEraseShear() throws Exception {
        var d = sample(); var bone = d.bones().get(0); var clip = d.animations().get(0);
        var history = new CommandHistory(d);
        history.execute("Move",Commands.model(m -> EditorEdits.move(m,bone.id(),new Vec2(3,4),EditorEdits.Space.LOCAL)));
        assertEquals(5,history.state().model().bones().get(0).local().shearX());
        history.execute("Key shear",Commands.model(m -> AnimationEdits.put(m,clip.id(),bone.id(),Channel.SHEAR,.5,List.of(7.,-9.))));
        var key = history.state().model().animation(clip.id()).timelines().stream().filter(t -> t.channel()==Channel.SHEAR).findFirst().orElseThrow().keys().get(1);
        var edited = history.state().model();
        history.execute("Curve",Commands.model(m -> AnimationEdits.curves(m,clip.id(),Set.of(key.id()),List.of(Curve.STEPPED,Curve.STEPPED))));
        assertTrue(history.undo()); assertEquals(edited,history.state().model());
        assertTrue(history.undo()); assertTrue(history.undo()); assertEquals(d,history.state().model());
        assertTrue(history.redo()); assertTrue(history.redo()); assertTrue(history.redo());
        var sampled = AnimationSampler.pose(history.state().model(),clip.id(),.75,false).get(bone.id());
        assertEquals(12,sampled.shearX()); assertEquals(-14,sampled.shearY());
        var local = new Transform(3,4,20,2,3,17,-28); var parent = new Transform(5,9,40,-2,3).matrix();
        var world = parent.multiply(local.matrix());
        matrix(world,parent.multiply(BoneInheritance.local(Bone.Inherit.normal,parent,world).matrix()));
    }

    @Test void boundingBoxEditingPickingAndColorChangesSurviveHistoryAndNativeSave() throws Exception {
        var d = sample(); var box = (BoundingBoxAttachment)d.attachments().stream().filter(a -> a instanceof BoundingBoxAttachment).findFirst().orElseThrow();
        var history = new CommandHistory(d);
        var expected = d.evaluateWorld().bones().get(d.bones().get(0).id()).matrix().apply(2,3);
        history.execute("Move bbox vertex",Commands.model(m -> BoundingBoxEdits.moveWorld(m,box.id(),0,expected)));
        assertEquals(2,BoundingBoxEdits.box(history.state().model(),box.id()).vertices().get(0).x(),1e-10);
        var clip = (ClippingAttachment)d.attachments().stream().filter(a -> a instanceof ClippingAttachment).findFirst().orElseThrow();
        history.execute("Clip color",Commands.model(m -> ClippingEdits.edit(m,clip.id(),c -> new ClippingAttachment(c.id(),c.name(),c.slotId(),c.vertices(),c.endSlotId(),Rgba.parse("ff00ffff")))));
        assertTrue(history.undo()); assertTrue(history.undo()); assertEquals(d,history.state().model()); assertTrue(history.redo()); assertTrue(history.redo());
        var projects = new ProjectCodec(); var bytes = projects.encode(history.state().model());
        assertEquals(10,new ObjectMapper().readTree(bytes).path("schemaVersion").asInt());
        var reopened = projects.decode(bytes); assertEquals(history.state().model(),reopened);
        assertEquals(1,reopened.evaluateWorld().bounds().size());
        assertTrue(VisibleGeometry.evaluate(reopened,reopened.evaluateWorld()).isEmpty());
        var point = reopened.evaluateWorld().bones().get(d.bones().get(0).id()).matrix().apply(5,5);
        assertEquals(box.id(),Picker.region(reopened,reopened.evaluateWorld(),point,new ImageAssets()).orElseThrow());
    }

    @Test void bothRuntimeVersionsPreserveShearCrossedCurvesPolygonsAndColors() throws Exception {
        var d = sample(); var codec = new SkeletonJsonCodec(); var originalClip = d.animations().get(0);
        for (String version : List.of(SkeletonJsonCodec.VERSION_38,SkeletonJsonCodec.VERSION)) {
            var bytes = codec.encode(d,Map.of(),"",version); var loaded = codec.decode(bytes,temp);
            assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
            assertEquals(2,loaded.attachments().size());
            assertEquals(((ClippingAttachment)d.attachments().get(0)).color(),((ClippingAttachment)loaded.attachments().get(0)).color());
            for (double time : List.of(0.,.25,.5,.75,1.)) matrix(AnimationSampler.world(d,originalClip.id(),time,false).bones().values().iterator().next().matrix(),
                    AnimationSampler.world(loaded,loaded.animations().get(0).id(),time,false).bones().values().iterator().next().matrix());
        }
    }

    @Test @EnabledIfSystemProperty(named="openbone.spineboy",matches="true")
    void realSpineboyKeepsEveryShearTrackAndAllReportedProperties() throws Exception {
        var source = SpineProjectSource.resolve(Path.of("res/spineboy/spineboy-pro.spine"));
        var codec = new SkeletonJsonCodec(); var d = codec.read(source.json(),source.imageBase());
        assertEquals(80,d.attachments().size());
        assertTrue(codec.warnings().stream().noneMatch(w -> w.contains("unsupported field") || w.contains("unsupported attachment") || w.contains("invalid curve")), () -> codec.warnings().toString());
        assertEquals(10,d.animations().stream().flatMap(a -> a.timelines().stream()).filter(t -> t.channel()==Channel.SHEAR).count());
        assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
        for (var clip : d.animations()) for (int i=0;i<12;i++) assertEquals(64,AnimationSampler.world(d,clip.id(),clip.duration()*i/11,false).bones().size());
        Files.writeString(Path.of("target/spineboy-feature-import-report.txt"),String.join("\n",codec.warnings()));
    }
}
