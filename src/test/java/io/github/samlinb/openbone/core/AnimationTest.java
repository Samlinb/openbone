package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.application.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTest {
    static final EntityId CLIP=AnimationClip.DEFAULT_ID;
    static DocumentSnapshot keys(DocumentSnapshot d,Channel c,double[] times,double[][] values){
        for(int i=0;i<times.length;i++){List<Double> v=new ArrayList<>();for(double n:values[i])v.add(n);d=AnimationEdits.put(d,CLIP,id(3),c,times[i],v);}return d;
    }
    static Transform sample(DocumentSnapshot d,double t){return AnimationSampler.pose(d,CLIP,t,false).getOrDefault(id(3),d.bone(id(3)).local());}
    @ParameterizedTest @CsvSource({"170,190,180","170,-170,0","0,720,360"})
    void unwrappedAnglesAndStatelessSeek(double from,double to,double middle){
        var setup=hierarchy();var d=keys(setup,Channel.ROTATE,new double[]{0,1},new double[][]{{from},{to}});
        for(double t:new double[]{1,.999,.25,0,.5,2,.5})assertEquals(setup.bone(id(3)).local().rotation()+from+(to-from)*Math.min(1,t),sample(d,t).rotation(),1e-9);
        assertEquals(setup.bone(id(3)).local().rotation()+middle,sample(d,.5).rotation(),1e-9);assertEquals(setup.bones(),d.bones());
    }
    @Test void setupBeforeFirstUnkeyedChannelsExactBoundaryAndScaleFactors(){
        var d=ModelEdits.editBone(hierarchy(),id(3),b->b.withLocal(new Transform(10,20,30,-2,0)));
        d=keys(d,Channel.SCALE,new double[]{.25,1},new double[][]{{1,-2},{-1,3}});
        assertEquals(d.bone(id(3)).local(),sample(d,0));
        assertEquals(new Transform(10,20,30,-2,-0.0),sample(d,.25));assertEquals(0,sample(d,.625).scaleX(),1e-12);assertEquals(2,sample(d,1).scaleX());assertEquals(0,sample(d,.8).scaleY(),1e-12);
    }
    @Test void linearAndSteppedAtEveryBoundary(){
        var d=keys(hierarchy(),Channel.TRANSLATE,new double[]{0,1},new double[][]{{0,0},{100,60}});var a=d.animation(CLIP);var first=a.timelines().get(0).keys().get(0);
        d=AnimationEdits.curves(d,CLIP,Set.of(first.id()),List.of(Curve.LINEAR,Curve.STEPPED));var setup=d.bone(id(3)).local();
        for(double t:new double[]{0,.25,.5,.999,1,2}){assertEquals(setup.x()+100*Math.min(t,1),sample(d,t).x(),1e-9);assertEquals(setup.y()+(t<1?0:60),sample(d,t).y(),1e-9);}
    }
    @Test void independentlyCalculatedBezierChordsTimeInverseAndDifferentComponents(){
        var x=new Curve(Curve.Kind.BEZIER,.2,0,.8,1);var y=new Curve(Curve.Kind.BEZIER,.3,1,.7,0);
        // x(u=.3)=.2664, y(u=.3)=.216. Endpoint samples must invert time, not evaluate y(t).
        assertEquals(.216,x.progress(.2664),1e-12);assertEquals(.5,x.progress(.5),1e-12);assertEquals(.5,y.progress(.5),1e-12);
        assertNotEquals(x.progress(.25),y.progress(.25));assertEquals(0,x.progress(0));assertEquals(1,x.progress(1));
        var d=keys(hierarchy(),Channel.TRANSLATE,new double[]{0,1},new double[][]{{0,0},{100,60}});var first=d.animation(CLIP).timelines().get(0).keys().get(0);
        d=AnimationEdits.curves(d,CLIP,Set.of(first.id()),List.of(x,y));var setup=d.bone(id(3)).local();
        assertEquals(setup.x()+100*x.progress(.25),sample(d,.25).x(),1e-12);assertEquals(setup.y()+60*y.progress(.25),sample(d,.25).y(),1e-12);
    }
    @Test void degenerateHandlesOvershootConstantSegmentAndValidation(){
        var c=new Curve(Curve.Kind.BEZIER,0,-1,0,2);for(double t:new double[]{0,1e-12,.001,.5,.999999,1})assertTrue(Double.isFinite(c.progress(t)));
        assertDoesNotThrow(()->new Curve(Curve.Kind.BEZIER,.8,0,.2,1));assertThrows(IllegalArgumentException.class,()->new Curve(Curve.Kind.BEZIER,0,Double.NaN,1,1));
        assertThrows(IllegalArgumentException.class,()->new Curve(Curve.Kind.LINEAR,.2,0,1,1));
        var t=new BoneTimeline(id(3),Channel.ROTATE,List.of(new Keyframe(id(101),0,List.of(7.),List.of(c)),new Keyframe(id(102),1,List.of(7.),List.of(Curve.LINEAR))));assertEquals(7,t.sample(.5).orElseThrow().get(0));
    }
    @TestFactory java.util.stream.Stream<DynamicTest> invalidKeysAndReferencesAreRejected(){
        Map<String,Runnable> bad=new LinkedHashMap<>();
        bad.put("NaN",()->new Keyframe(id(101),Double.NaN,List.of(1.),List.of(Curve.LINEAR)));
        bad.put("infinite",()->new Keyframe(id(101),0,List.of(Double.POSITIVE_INFINITY),List.of(Curve.LINEAR)));
        bad.put("negative time",()->AnimationEdits.put(hierarchy(),CLIP,id(3),Channel.ROTATE,-1,List.of(1.)));
        bad.put("wrong arity",()->AnimationEdits.put(hierarchy(),CLIP,id(3),Channel.TRANSLATE,0,List.of(1.)));
        bad.put("dangling bone",()->AnimationEdits.put(hierarchy(),CLIP,id(99),Channel.ROTATE,0,List.of(1.)));
        bad.put("duplicate time",()->new BoneTimeline(id(3),Channel.ROTATE,List.of(new Keyframe(id(101),0,List.of(1.),List.of(Curve.LINEAR)),new Keyframe(id(102),0,List.of(2.),List.of(Curve.LINEAR)))));
        bad.put("duplicate id",()->new BoneTimeline(id(3),Channel.ROTATE,List.of(new Keyframe(id(101),0,List.of(1.),List.of(Curve.LINEAR)),new Keyframe(id(101),1,List.of(2.),List.of(Curve.LINEAR)))));
        return bad.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->assertThrows(IllegalArgumentException.class,e.getValue()::run)));
    }
    @Test void recordSameTimeReplacesPreservesIdAndDoesNotQuantize(){
        var d=keys(hierarchy(),Channel.ROTATE,new double[]{.123456789},new double[][]{{720}});var id=d.animation(CLIP).timelines().get(0).keys().get(0).id();
        d=AnimationEdits.put(d,CLIP,id(3),Channel.ROTATE,.123456789,List.of(-170.));var keys=d.animation(CLIP).timelines().get(0).keys();assertEquals(1,keys.size());assertEquals(id,keys.get(0).id());assertEquals(.123456789,keys.get(0).time());
    }
    @Test void identicalSingleChannelRecordingKeepsTrackOrderSavedPointAndHistory(){
        var d=hierarchy();for(var c:Channel.values())d=AnimationEdits.record(d,CLIP,id(3),0,d.bone(id(3)).local(),EnumSet.of(c));
        var h=new CommandHistory(d);var before=h.state();
        for(var c:Channel.values())h.execute("same-channel record",Commands.model(m->AnimationEdits.record(m,CLIP,id(3),0,m.bone(id(3)).local(),EnumSet.of(c))));
        assertEquals(before,h.state());assertEquals(0,h.undoCount());assertFalse(h.isDirty());
    }
    @Test void overflowInAnimatedPoseOrBezierControlHullIsRejectedBeforePublication(){
        var h=new CommandHistory(hierarchy());var before=h.state();
        assertThrows(IllegalArgumentException.class,()->h.execute("overflow",Commands.model(d->AnimationEdits.put(d,CLIP,id(3),Channel.SCALE,0,List.of(Double.MAX_VALUE,Double.MAX_VALUE)))));
        assertEquals(before,h.state());assertEquals(0,h.undoCount());
        var d=keys(hierarchy(),Channel.TRANSLATE,new double[]{0,1},new double[][]{{0,0},{100,100}});var first=d.animation(CLIP).timelines().get(0).keys().get(0).id();
        assertThrows(IllegalArgumentException.class,()->AnimationEdits.curves(d,CLIP,Set.of(first),List.of(new Curve(Curve.Kind.BEZIER,.2,Double.MAX_VALUE,.8,1),Curve.LINEAR)));
    }
    @Test void animatedScaleCannotOverflowAnUntexturedBoneTip(){
        var bone=new Bone(id(101),"bare",null,Transform.IDENTITY,80);var d=new DocumentSnapshot(List.of(bone),List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->AnimationEdits.put(d,CLIP,bone.id(),Channel.SCALE,1,List.of(Double.MAX_VALUE/4,1.)));
        var zero=new Bone(id(102),"zero-length",null,Transform.IDENTITY,0);var emptyTip=new DocumentSnapshot(List.of(zero),List.of(),List.of());
        var accepted=AnimationEdits.put(emptyTip,CLIP,zero.id(),Channel.SCALE,1,List.of(Double.MAX_VALUE/4,1.));
        assertDoesNotThrow(()->accepted.evaluateWorld(AnimationSampler.pose(accepted,CLIP,1,false)));
    }
    @Test void atomicMovesCopyDeleteUndoRedoSelectionDirtyAndCancel(){
        var d=keys(hierarchy(),Channel.ROTATE,new double[]{0,1},new double[][]{{0},{720}});var keys=d.animation(CLIP).timelines().get(0).keys();var first=keys.get(0).id();var second=keys.get(1).id();var h=new CommandHistory(d);h.select(Selection.of(id(3)));h.selectKeys(Set.of(first));
        var initial=h.state();assertThrows(IllegalArgumentException.class,()->h.execute("collision",Commands.model(m->AnimationEdits.move(m,CLIP,Set.of(first),1))));assertEquals(initial,h.state());assertFalse(h.isDirty());
        h.beginPreview("drag");for(int i=1;i<=20;i++){double delta=.01*i;h.updatePreview(Commands.model(m->AnimationEdits.move(m,CLIP,Set.of(first,second),delta)));}h.cancelPreview();assertEquals(initial,h.state());assertEquals(0,h.undoCount());
        h.beginPreview("drag");h.updatePreview(Commands.model(m->AnimationEdits.move(m,CLIP,Set.of(first,second),.5)));h.commitPreview();assertEquals(1,h.undoCount());assertTrue(h.isDirty());h.undo();assertEquals(initial,h.state());h.redo();h.markSaved();
        var saved=h.state();var cb=AnimationEdits.copy(saved.model(),CLIP,Set.of(first,second));assertThrows(IllegalArgumentException.class,()->AnimationEdits.paste(saved.model(),CLIP,cb,.5));
        h.execute("paste",Commands.model(m->AnimationEdits.paste(m,CLIP,cb,2)));assertEquals(4,h.state().model().animation(CLIP).timelines().get(0).keys().size());h.undo();assertEquals(saved,h.state());assertFalse(h.isDirty());
        h.execute("delete",Commands.model(m->AnimationEdits.remove(m,CLIP,Set.of(first))));assertTrue(h.state().keySelection().isEmpty());h.undo();assertEquals(saved,h.state());assertFalse(h.isDirty());
    }
    @Test void p3OperationsPreserveAnimationAndCascadeDeletionUndoRestoresReferences(){
        var d=keys(hierarchy(),Channel.ROTATE,new double[]{0,1},new double[][]{{0},{720}});var original=d.animations();
        d=ModelEdits.editBone(d,id(1),b->b.withName("renamed"));assertEquals(original,d.animations());
        d=ModelEdits.addBone(d,new Bone(id(99),"extra",null,Transform.IDENTITY,2));assertEquals(original,d.animations());
        d=ModelEdits.addSlot(d,new Slot(id(100),"extra-slot",id(99),null));assertEquals(original,d.animations());
        d=ModelEdits.editSlot(d,id(100),s->new Slot(s.id(),"changed-slot",id(1),null));assertEquals(original,d.animations());
        d=ModelEdits.editRegion(d,id(21),r->new RegionAttachment(r.id(),r.name(),r.slotId(),"中文/新.png",r.local(),r.width(),r.height()));assertEquals(original,d.animations());
        d=EditorEdits.order(d,id(10),1);assertEquals(original,d.animations());d=ModelEdits.reparent(d,id(3),id(1),ModelEdits.ReparentPolicy.KEEP_LOCAL);assertEquals(original,d.animations());
        var h=new CommandHistory(d);var before=h.state();h.execute("cascade",Commands.model(m->ModelEdits.delete(m,id(3))));assertTrue(h.state().model().animation(CLIP).timelines().isEmpty());h.undo();assertEquals(before,h.state());
        var clipboard=AnimationEdits.copy(d,CLIP,Set.of(d.animation(CLIP).timelines().get(0).keys().get(0).id()));var deleted=h.state().model();deleted=ModelEdits.delete(deleted,id(3));var finalDeleted=deleted;assertThrows(IllegalArgumentException.class,()->AnimationEdits.paste(finalDeleted,CLIP,clipboard,3));
    }
    @Test void animateTransformUsesEvaluatedParentsWithoutSetupWritesAndZeroScalePolicy(){
        var d=keys(hierarchy(),Channel.ROTATE,new double[]{0,1},new double[][]{{0},{720}});var before=d;
        d=AnimationEdits.transform(d,CLIP,.5,id(3),v->EditorEdits.move(v,id(3),new Vec2(10,20),EditorEdits.Space.WORLD),EnumSet.of(Channel.TRANSLATE));assertEquals(before.bones(),d.bones());
        var world=d.evaluateWorld(AnimationSampler.pose(d,CLIP,.5,false));var previous=before.evaluateWorld(AnimationSampler.pose(before,CLIP,.5,false));assertEquals(previous.bones().get(id(3)).origin().x()+10,world.bones().get(id(3)).origin().x(),1e-9);
        var zero=ModelEdits.editBone(d,id(3),b->b.withLocal(new Transform(0,0,0,0,1)));assertThrows(IllegalArgumentException.class,()->AnimationEdits.record(zero,CLIP,id(3),0,new Transform(0,0,0,1,1),EnumSet.of(Channel.SCALE)));
    }
    @Test void playbackUsesClockAnchorLoopBoundaryPauseEndAndExplicitQuantization(){
        var p=new Playback();long start=1_000_000_000L;p.play(start,1);p.tick(start+333_333_333,1);assertEquals(.333333333,p.time());p.tick(start+750_000_000,1);assertEquals(.75,p.time());p.pause(start+800_000_000,1);assertEquals(.8,p.time());p.tick(start+900_000_000,1);assertEquals(.8,p.time());
        p.play(start+1_000_000_000,1);p.tick(start+1_400_000_000,1);assertEquals(1,p.time());assertFalse(p.playing());p.play(start+2_000_000_000,1);assertEquals(0,p.time());p.loop(true);p.tick(start+3_000_000_000L,1);assertEquals(0,p.time());assertTrue(p.playing());p.tick(start+3_250_000_000L,1);assertEquals(.25,p.time());
        p.seek(.123456789);assertFalse(p.playing());assertEquals(.123456789,p.time());assertEquals(4./30,Playback.snap(p.time(),30));p.play(start,0);assertFalse(p.playing());assertEquals(0,p.time());
        assertEquals(0,AnimationSampler.sampleTime(1,1,true));assertEquals(1,AnimationSampler.sampleTime(1,1,false));
    }
}
