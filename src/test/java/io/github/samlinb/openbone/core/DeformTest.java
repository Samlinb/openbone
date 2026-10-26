package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class DeformTest {
    @TempDir Path temp;
    static DocumentSnapshot fixture(int influences){var d=WeightTest.fixture();if(influences==0)return d;List<WeightEdits.Binding> bindings=new ArrayList<>();for(int i=1;i<=influences;i++)bindings.add(new WeightEdits.Binding(id(i),i));return WeightEdits.bind(d,id(20),bindings);}
    static List<Double> values(DocumentSnapshot d,double x,double y){var v=new ArrayList<>(Collections.nCopies(MeshEdits.mesh(d,id(20)).deformSize(),0.0));v.set(0,x);v.set(1,y);return v;}
    static DocumentSnapshot keys(DocumentSnapshot d){var clip=d.animations().get(0).id();d=DeformEdits.put(d,clip,id(20),0,values(d,0,0));d=DeformEdits.put(d,clip,id(20),.5,values(d,10,-4));return DeformEdits.put(d,clip,id(20),1,values(d,-8,6));}
    static WorldPose pose(DocumentSnapshot d,double time,boolean loop){var clip=d.animations().get(0).id();return d.evaluateWorld(AnimationSampler.pose(d,clip,time,loop),AnimationSampler.deforms(d,clip,time,loop));}
    @ParameterizedTest @ValueSource(ints={0,1,2,4}) void offsetsArePerVertexOrPerInfluenceAndNeverOverwriteSetup(int count){
        var setup=fixture(count);var d=keys(setup);assertEquals(setup.attachments(),d.attachments());assertEquals(setup.bones(),d.bones());var mesh=MeshEdits.mesh(d,id(20));var world=setup.evaluateWorld();var before=world.meshes().get(id(20)).vertices().get(0);var after=pose(d,.25,false).meshes().get(id(20)).vertices().get(0);
        EntityId bone=count==0?id(1):mesh.weights().get(0).get(0).boneId();double weight=count==0?1:mesh.weights().get(0).get(0).weight();var matrix=world.bones().get(bone).matrix();assertEquals(before.x()+(matrix.a()*5+matrix.b()*-2)*weight,after.x(),1e-9);assertEquals(before.y()+(matrix.c()*5+matrix.d()*-2)*weight,after.y(),1e-9);
        assertEquals(pose(d,.25,false),pose(d,1.25,true));assertEquals(pose(d,1,false),pose(d,3,false));
    }
    @Test void setupBeforeFirstKeyAndExactKeysAndCurveTypes(){
        var d=fixture(0);var clip=d.animations().get(0).id();d=DeformEdits.put(d,clip,id(20),.5,values(d,10,0));d=DeformEdits.put(d,clip,id(20),1,values(d,20,0));assertEquals(d.evaluateWorld(),pose(d,.25,false));var key=d.animation(clip).deforms().get(0).keys().get(0);assertEquals(10,DeformEdits.offsets(d,clip,id(20),.5).get(0));
        d=AnimationEdits.curves(d,clip,Set.of(key.id()),List.of(Curve.STEPPED));assertEquals(10,DeformEdits.offsets(d,clip,id(20),.75).get(0));d=AnimationEdits.curves(d,clip,Set.of(key.id()),List.of(new Curve(Curve.Kind.BEZIER,.2,.1,.8,.9)));assertEquals(15,DeformEdits.offsets(d,clip,id(20),.75).get(0),1e-10);
    }
    @Test void replaceSameTimeIdCopyPasteMoveDeleteSelectionUndoAndCollisionAtomic(){
        var d=keys(fixture(2));var clip=d.animations().get(0).id();var key=d.animation(clip).deforms().get(0).keys().get(1);var replaced=DeformEdits.put(d,clip,id(20),.5,values(d,12,3));assertEquals(key.id(),replaced.animation(clip).deforms().get(0).keys().get(1).id());
        var h=new CommandHistory(d);h.selectKeys(Set.of(key.id()));var original=h.state();var copy=AnimationEdits.copy(d,clip,Set.of(key.id()));h.execute("paste",Commands.model(a->AnimationEdits.paste(a,clip,copy,1.5)));assertEquals(1.5,h.state().model().animation(clip).duration());h.undo();assertEquals(original,h.state());
        assertThrows(IllegalArgumentException.class,()->h.execute("collision",Commands.model(a->AnimationEdits.move(a,clip,Set.of(key.id()),.5))));assertEquals(original,h.state());assertEquals(1,h.redoCount());assertFalse(h.isDirty());
        h.execute("delete",Commands.model(a->AnimationEdits.remove(a,clip,Set.of(key.id()))));assertTrue(h.state().keySelection().isEmpty());h.undo();assertEquals(original,h.state());h.beginPreview("drag");h.updatePreview(Commands.model(a->AnimationEdits.move(a,clip,Set.of(key.id()),.25)));h.commitPreview();assertEquals(1,h.undoCount());assertEquals(.75,h.state().model().animation(clip).deforms().get(0).keys().get(1).time());
    }
    @Test void topologyAndBoneInfluenceLayoutCannotSilentlyRemapExistingOffsets(){
        var d=keys(fixture(0));assertThrows(IllegalArgumentException.class,()->MeshEdits.remove(d,id(20),0));assertThrows(IllegalArgumentException.class,()->WeightEdits.bind(d,id(20),List.of(new WeightEdits.Binding(id(1),1))));
        var moved=MeshEdits.vertex(d,id(20),0,new Vec2(-35,-25),new Vec2(0,1));assertEquals(d.animations(),moved.animations());
        var weighted=keys(fixture(2));var m=MeshEdits.mesh(weighted,id(20));var row=m.weights().get(0);assertThrows(IllegalArgumentException.class,()->WeightEdits.vertex(weighted,id(20),0,List.of(new Influence(id(3),row.get(0).point(),.5),new Influence(id(2),row.get(1).point(),.5))));assertDoesNotThrow(()->WeightEdits.vertex(weighted,id(20),0,List.of(new Influence(row.get(0).boneId(),row.get(0).point(),1),new Influence(row.get(1).boneId(),row.get(1).point(),3))));
    }
    @Test void boneEditsAndDeletionPreserveOrCascadeDeformReferencesAndUndo(){
        var d=keys(fixture(4));var clip=d.animations().get(0).id();var trs=AnimationEdits.put(d,clip,id(1),Channel.ROTATE,0,List.of(30.0));assertEquals(d.animation(clip).deforms(),trs.animation(clip).deforms());
        var h=new CommandHistory(d);h.select(Selection.of(id(20)));h.selectKeys(Set.of(d.animation(clip).deforms().get(0).keys().get(0).id()));var before=h.state();h.execute("delete bone",Commands.model(a->ModelEdits.delete(a,id(4))));assertTrue(h.state().model().animation(clip).deforms().isEmpty());assertTrue(h.state().keySelection().isEmpty());h.undo();assertEquals(before,h.state());
    }
    @Test void playbackSamplingDoesNotMutateModelHistorySelectionOrDirty(){
        var d=keys(fixture(4));var h=new CommandHistory(d);h.select(Selection.of(id(20)));var state=h.state();var clock=new Playback();clock.loop(true);clock.play(0,d.animations().get(0).duration());for(int i=0;i<300;i++){clock.tick(i*16_666_667L,1);pose(d,clock.time(),false);}assertEquals(state,h.state());assertEquals(0,h.undoCount());assertFalse(h.isDirty());
    }
    @ParameterizedTest @ValueSource(ints={0,4}) void worldDragRecordsOffsetsAndEscKeepsSetupAndOneHistory(int count){
        var setup=fixture(count);var d=keys(setup);var clip=d.animations().get(0).id();var h=new CommandHistory(d);var before=pose(d,.25,false).meshes().get(id(20)).vertices().get(0);var target=new Vec2(before.x()+10,before.y()-6);h.beginPreview("deform drag");h.updatePreview(Commands.model(a->DeformEdits.moveWorld(a,clip,.25,id(20),0,target)));h.cancelPreview();assertEquals(d,h.state().model());assertFalse(h.isDirty());
        h.beginPreview("deform drag");h.updatePreview(Commands.model(a->DeformEdits.moveWorld(a,clip,.25,id(20),0,target)));h.commitPreview();assertEquals(1,h.undoCount());assertEquals(d.attachments(),h.state().model().attachments());var actual=pose(h.state().model(),.25,false).meshes().get(id(20)).vertices().get(0);assertEquals(target.x(),actual.x(),1e-9);assertEquals(target.y(),actual.y(),1e-9);
    }
    @ParameterizedTest @ValueSource(ints={0,4}) void ownAndActual42SparseCodecRoundtripIncludingOddScalarOffset(int count)throws Exception{
        var d=keys(fixture(count));var clip=d.animations().get(0).id();var vector=values(d,0,-4);d=DeformEdits.put(d,clip,id(20),.5,vector);var key=d.animation(clip).deforms().get(0).keys().get(0);d=AnimationEdits.curves(d,clip,Set.of(key.id()),List.of(new Curve(Curve.Kind.BEZIER,.2,.1,.8,.9)));var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));
        var png=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);ImageIO.write(png,"PNG",temp.resolve("image.png").toFile());var bytes=new SkeletonJsonCodec().encode(d,Map.of(id(20),"image"),"");var tree=new ObjectMapper().readTree(bytes);var keys=tree.path("animations").path(d.animation(clip).name()).path("attachments").path("default").path("slot").path("mesh").path("deform");assertFalse(keys.get(0).has("vertices"));assertEquals(1,keys.get(1).path("offset").asInt());assertEquals(1,keys.get(1).path("vertices").size());
        var read=new SkeletonJsonCodec().decode(bytes,temp);for(double time:new double[]{0,.25,.5,.75,1,1.25})WeightTest.near(pose(d,time,false).meshes().get(id(20)).vertices(),pose(read,time,false).meshes().values().iterator().next().vertices());
        ((ObjectNode)keys.get(1)).put("offset",999);assertThrows(ProjectFormatException.class,()->new SkeletonJsonCodec().decode(new ObjectMapper().writeValueAsBytes(tree),temp));
    }
    @Test void finiteLocalAndWholeAnimationCertificatesCannotHideOverflowBehindZeroScale(){
        var d=fixture(4);var clip=d.animations().get(0).id();var tooLarge=values(d,Double.MAX_VALUE,Double.MAX_VALUE);assertThrows(IllegalArgumentException.class,()->DeformEdits.put(d,clip,id(20),0,tooLarge));
        var normal=keys(d);var first=normal.animation(clip).deforms().get(0).keys().get(0);assertThrows(IllegalArgumentException.class,()->AnimationEdits.curves(normal,clip,Set.of(first.id()),List.of(new Curve(Curve.Kind.BEZIER,.2,Double.MAX_VALUE,.8,Double.MAX_VALUE))));
        var large=DeformEdits.put(d,clip,id(20),0,values(d,1e100,0));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(large));
        var narrow=DeformEdits.put(d,clip,id(20),1,values(d,0,0));narrow=DeformEdits.put(narrow,clip,id(20),Math.nextUp(1f),values(d,1,0));assertDoesNotThrow(()->RuntimeRange.validate(DeformEdits.put(d,clip,id(20),0,values(d,0,0))));var k=narrow.animation(clip).deforms().get(0).keys().get(0);var curved=AnimationEdits.curves(narrow,clip,Set.of(k.id()),List.of(new Curve(Curve.Kind.BEZIER,0,0,0,1)));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(curved));
    }
    @Test void invalidLengthsIdsCurvesAndCopyAcrossLayoutAreRejected(){
        var d=fixture(0);var clip=d.animations().get(0).id();assertThrows(IllegalArgumentException.class,()->DeformEdits.put(d,clip,id(20),0,List.of(1.0,2.0)));assertThrows(IllegalArgumentException.class,()->DeformEdits.put(d,clip,id(20),-1,values(d,0,0)));
        var recorded=keys(d);var ids=recorded.animation(clip).deforms().get(0).keys().stream().map(Keyframe::id).collect(java.util.stream.Collectors.toSet());var clipboard=AnimationEdits.copy(recorded,clip,ids);var bare=AnimationEdits.remove(recorded,clip,ids);var bound=WeightEdits.bind(bare,id(20),List.of(new WeightEdits.Binding(id(1),1)));assertThrows(IllegalArgumentException.class,()->AnimationEdits.paste(bound,clip,clipboard,2));
    }
    @Test void localSetupPlusOffsetOverflowIsRejectedEvenWhenEntireParentChainIsZero(){
        var d=fixture(4);d=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,0,0)));var m=MeshEdits.mesh(d,id(20));var weights=new ArrayList<>(m.weights());var row=new ArrayList<>(weights.get(0));var first=row.get(0);row.set(0,new Influence(first.boneId(),new Vec2(1e308,0),first.weight()));weights.set(0,row);var zero=MeshEdits.replace(d,id(20),a->m.withWeights(weights));assertDoesNotThrow(()->zero.evaluateWorld());var clip=zero.animations().get(0).id();assertThrows(IllegalArgumentException.class,()->DeformEdits.put(zero,clip,id(20),0,values(zero,1e308,0)));
    }
}
