package io.github.samlinb.openbone.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class TwoBoneIkTest {
 static DocumentSnapshot fixture(double tx,double ty,boolean bend,double mix,double softness,boolean stretch){
  var d=IkConstraintTest.fixture(tx,ty,1,false,false,false).withConstraints(List.of());
  d=ModelEdits.editBone(d,id(3),b->new Bone(b.id(),b.name(),b.parentId(),b.local(),60));
  return d.withConstraints(List.of(new IkConstraint(id(5),"two",0,true,List.of(id(2),id(3)),id(4),mix,softness,bend,false,stretch,false)));
 }
 static void near(double x,double y){IkConstraintTest.near(x,y);}
 @ParameterizedTest @CsvSource({"100,80,true","100,80,false","-100,80,true","-100,-80,false","100,-80,true","160,0,true","20,80,false"})
 void reachableEndpointsAndBend(double x,double y,boolean bend){
  var d=fixture(x,y,bend,1,0,false);var w=d.evaluateWorld();near(x,w.bones().get(id(3)).tip().x());near(y,w.bones().get(id(3)).tip().y());
  var a=w.bones().get(id(2));var b=w.bones().get(id(3));double cross=(a.tip().x()-a.origin().x())*(b.tip().y()-b.origin().y())-(a.tip().y()-a.origin().y())*(b.tip().x()-b.origin().x());assertTrue(bend?cross>=-1e-9:cross<=1e-9);
 }
 @Test void unreachableOriginStretchSoftnessAndMix(){
  var far=fixture(300,0,true,1,0,false);near(160,far.evaluateWorld().bones().get(id(3)).tip().x());
  var stretched=fixture(300,0,true,1,0,true);near(300,stretched.evaluateWorld().bones().get(id(3)).tip().x());
  var origin=fixture(0,0,true,1,0,false);assertNotNull(origin.evaluateWorld());near(40,Math.hypot(origin.evaluateWorld().bones().get(id(3)).tip().x(),origin.evaluateWorld().bones().get(id(3)).tip().y()));
  var soft=fixture(160,0,true,1,20,false);assertTrue(soft.evaluateWorld().bones().get(id(3)).tip().x()<160);
  var none=fixture(100,80,true,0,0,false);near(160,none.evaluateWorld().bones().get(id(3)).tip().x());
  var half=fixture(100,80,true,.5,0,false);assertNotEquals(none.evaluateWorld(),half.evaluateWorld());assertEquals(none.bones(),half.bones());
 }
 @ParameterizedTest @CsvSource({"2,.8,1","-2,.8,1","2,-.8,1","2,.8,-1","-2,-.8,-1"})
 void ellipseAndMirrors(double sx,double sy,double childScale){
  var d=fixture(180,80,true,1,0,false);d=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(0,0,0,sx,sy)));d=ModelEdits.editBone(d,id(3),b->b.withLocal(new Transform(100,12,0,childScale,1)));
  var w=d.evaluateWorld();near(180,w.bones().get(id(3)).tip().x());near(80,w.bones().get(id(3)).tip().y());near(0,w.bones().get(id(3)).origin().x()*w.bones().get(id(2)).matrix().c()-w.bones().get(id(3)).origin().y()*w.bones().get(id(2)).matrix().a());
 }
 @Test void invalidChainsCompressAndOverlappingWritersRejected(){
  var d=fixture(100,80,true,1,0,false);var ik=(IkConstraint)d.constraint(id(5));assertThrows(IllegalArgumentException.class,()->ik.withOptions(true,true,false,false));
  assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(ik.withBones(List.of(id(3),id(2))))));
  var other=new IkConstraint(id(6),"again",1,true,List.of(id(2)),id(4),1,0,true,false,false,false);assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(ik,other)));
 }
 @Test void zeroLengthAndScaleFiniteInProjectButRejectedForRuntime()throws Exception{
  var d=fixture(100,80,true,1,0,false);d=ModelEdits.editBone(d,id(3),b->new Bone(b.id(),b.name(),b.parentId(),b.local(),0));var zero=d;assertNotNull(d.evaluateWorld());assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(zero));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
  d=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(0,0,0,0,1)));assertNotNull(d.evaluateWorld());
 }
 @Test void dependencyPolicyAllowsParentBeforeChildAndRejectsTheReverse(){
  var d=fixture(100,80,true,1,0,false).withConstraints(List.of());
  var parent=new IkConstraint(id(5),"parent",0,true,List.of(id(2)),id(4),.5,0,true,false,false,false);
  var child=new IkConstraint(id(6),"child",1,true,List.of(id(3)),id(4),1,0,true,false,false,false);
  var solved=d.withConstraints(List.of(child,parent));assertEquals(List.of(parent,child),solved.constraints());
  var world=solved.evaluateWorld();var c=world.bones().get(id(3));var target=world.bones().get(id(4));
  near(0,(c.tip().x()-c.origin().x())*(target.origin().y()-c.origin().y())-(c.tip().y()-c.origin().y())*(target.origin().x()-c.origin().x()));
  assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(parent.withOrder(1),child.withOrder(0))));
 }
 @Test void scaleAnimationIsPreservedAcrossZeroWhileRuntimeRefusesUnprovedRange()throws Exception{
  var d=fixture(100,80,true,1,0,false);var clip=d.animations().get(0).id();
  d=AnimationEdits.put(d,clip,id(3),io.github.samlinb.openbone.core.animation.Channel.SCALE,0,List.of(1.,1.));
  d=AnimationEdits.put(d,clip,id(3),io.github.samlinb.openbone.core.animation.Channel.SCALE,1,List.of(-1.,1.));
  for(double t:new double[]{0,.25,.5,.5-1e-14,.5+1e-14,.75,1})assertNotNull(d.evaluateWorld(io.github.samlinb.openbone.core.animation.AnimationSampler.pose(d,clip,t,false)));
  var saved=d;assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(saved));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));assertEquals(2,d.animation(clip).keys().size());
 }
 @Test void firstInverseOverflowCannotHideBehindZeroTargetOrSingularMultiplier()throws Exception{
  var bones=List.of(new Bone(id(1),"root",null,new Transform(0,0,45,1e17,1e-17),0),new Bone(id(2),"upstream",id(1),new Transform(0,0,45,1,1),0),new Bone(id(3),"parent",id(2),Transform.IDENTITY,80),new Bone(id(4),"child",id(3),new Transform(1e6,0,0,1,1),30),new Bone(id(5),"target",id(1),Transform.IDENTITY,0));
  var d=new DocumentSnapshot(bones,List.of(),List.of()).withConstraints(List.of(new IkConstraint(id(6),"two",0,true,List.of(id(3),id(4)),id(5),1,0,true,false,false,false)));
  assertNotNull(d.evaluateWorld());RuntimeRange.validate(d.withConstraints(List.of()));
  var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(d));assertTrue(error.getMessage().contains("first inverse numerators"));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void firstBoneDrawingLengthZeroDoesNotDisableTwoBoneStretch()throws Exception{
  var d=fixture(300,0,true,1,0,true);d=ModelEdits.editBone(d,id(2),b->new Bone(b.id(),b.name(),b.parentId(),b.local(),0));
  near(300,d.evaluateWorld().bones().get(id(3)).tip().x());RuntimeRange.validate(d);
 }
}
