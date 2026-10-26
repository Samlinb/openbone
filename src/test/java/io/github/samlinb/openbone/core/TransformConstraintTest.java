package io.github.samlinb.openbone.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.io.*;
import java.nio.file.Path;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class TransformConstraintTest {
 static DocumentSnapshot fixture(boolean local,boolean relative,double mix){
  var d=IkConstraintTest.fixture(20,30,1,false,false,false).withConstraints(List.of());
  d=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(1,2,10,2,3)));d=ModelEdits.editBone(d,id(4),b->b.withLocal(new Transform(20,30,40,4,5)));
  return d.withConstraints(List.of(new TransformConstraint(id(5),"copy",0,true,List.of(id(2)),id(4),local,relative,Collections.nCopies(6,mix),List.of(5.,3.,4.,.2,.4,15.))));
 }
 static void near(double a,double b){IkConstraintTest.near(a,b);}
 @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
 void allModesAtFullMixHaveIndependentAnalyticMatrices(boolean local,boolean relative){
  var d=fixture(local,relative,1);var world=d.evaluateWorld();var m=world.bones().get(id(2)).matrix();
  double x,y,rotation,sx,sy;
  if(local){x=relative?24:23;y=relative?36:34;rotation=relative?55:45;sx=relative?8.4:2.1;sy=relative?16.2:1.8;}
  else {x=20+12*Math.cos(Math.toRadians(40))-20*Math.sin(Math.toRadians(40))+(relative?1:0);y=30+12*Math.sin(Math.toRadians(40))+20*Math.cos(Math.toRadians(40))+(relative?2:0);rotation=relative?55:45;sx=relative?8.4:4.2;sy=relative?16.2:5.4;}
  near(x,m.tx());near(y,m.ty());near(Math.cos(Math.toRadians(rotation))*sx,m.a());near(Math.sin(Math.toRadians(rotation))*sx,m.c());near(Math.cos(Math.toRadians(rotation+105))*sy,m.b());near(Math.sin(Math.toRadians(rotation+105))*sy,m.d());
  var child=world.bones().get(id(3));var expected=m.apply(100,0);near(expected.x(),child.origin().x());near(expected.y(),child.origin().y());assertEquals(new Transform(1,2,10,2,3),d.bone(id(2)).local());
 }
 @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
 void allZeroMixIsExactSetupAndHalfIsFinite(boolean local,boolean relative){
  var zero=fixture(local,relative,0);assertEquals(zero.withConstraints(List.of()).evaluateWorld(),zero.evaluateWorld());assertNotNull(fixture(local,relative,.5).evaluateWorld());
 }
 @Test void worldOverrideSurvivesLaterConstraintAndTargetChain(){
  var d=fixture(false,false,1);var extra=new Bone(id(8),"follower",id(1),Transform.IDENTITY,10);d=ModelEdits.addBone(d,extra);
  var follow=new TransformConstraint(id(9),"follow",1,true,List.of(id(8)),id(2),false,false,Collections.nCopies(6,1.),Collections.nCopies(6,0.));var next=new ArrayList<>(d.constraints());next.add(follow);d=d.withConstraints(next);
  var world=d.evaluateWorld();near(world.bones().get(id(2)).matrix().tx(),world.bones().get(id(8)).matrix().tx());near(world.bones().get(id(2)).matrix().b(),world.bones().get(id(8)).matrix().b());
  var invalid=next.stream().map(c->c.id().equals(id(9))?follow.withMode(true,false):c).toList();var snapshot=d;assertThrows(IllegalArgumentException.class,()->snapshot.withConstraints(invalid));
 }
 @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
 void strictOwnAndRuntimeRoundtripsPreserveAllSixComponents(boolean local,boolean relative)throws Exception{
  var d=fixture(local,relative,.5);var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));var skeletonCodec=new SkeletonJsonCodec();var restored=skeletonCodec.decode(skeletonCodec.encode(d,Map.of(),""),Path.of("D:/tmp"));
  var t=(TransformConstraint)restored.constraints().get(0);assertEquals(local,t.local());assertEquals(relative,t.relative());assertEquals(((TransformConstraint)d.constraints().get(0)).mix(),t.mix());assertEquals(((TransformConstraint)d.constraints().get(0)).offset(),t.offset());
  var expected=d.evaluateWorld().bones().get(id(2)).matrix();var actual=restored.evaluateWorld().bones().get(restored.bones().stream().filter(b->b.name().equals("arm")).findFirst().orElseThrow().id()).matrix();assertEquals(expected,actual);
 }
 @Test void runtimeUsesOneGlobalDenseOrderAcrossConstraintKinds()throws Exception{
  var d=ModelEdits.addBone(fixture(false,false,.5),new Bone(id(8),"follower",id(1),Transform.IDENTITY,10));var t=(TransformConstraint)d.constraints().get(0);d=d.withConstraints(List.of(new IkConstraint(id(9),"aim",10,true,List.of(id(8)),id(2),1,0,true,false,false,false),t.withOrder(5)));
  var codec=new SkeletonJsonCodec();var bytes=codec.encode(d,Map.of(),"");var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);assertEquals(0,json.path("transform").get(0).path("order").asInt());assertEquals(1,json.path("ik").get(0).path("order").asInt());var restored=codec.decode(bytes,Path.of("D:/tmp"));assertTrue(restored.constraints().get(0) instanceof TransformConstraint);assertTrue(restored.constraints().get(1) instanceof IkConstraint);
 }
 @Test void singularParentIsRetainedInProjectButRefusedForAppliedFloatInverse()throws Exception{
  var d=ModelEdits.editBone(fixture(false,false,.5),id(1),b->b.withLocal(new Transform(0,0,0,0,1)));var unsafe=d;assertNotNull(d.evaluateWorld());assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(unsafe));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void multipleWorldBonesWithConstrainedParentRemainLosslessButUnprovedForRuntime()throws Exception{
  var d=fixture(false,false,.5);var t=(TransformConstraint)d.constraints().get(0);d=d.withConstraints(List.of(t.withBones(List.of(id(2),id(3)))));var own=d;assertNotNull(d.evaluateWorld());assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(own));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void oppositeHandedPartialShearBranchIsRefusedWithoutChangingTheDoubleProject()throws Exception{
  var d=ModelEdits.editBone(fixture(false,false,.5),id(4),b->b.withLocal(new Transform(20,30,40,-4,5)));var own=d;assertNotNull(d.evaluateWorld());var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(own));assertTrue(error.getMessage().contains("mixShearY"));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void relativePartialShearWithDegenerateTargetIsRefusedAndRemainsEditable()throws Exception{
  var d=ModelEdits.editBone(fixture(false,true,.5),id(4),b->b.withLocal(new Transform(20,30,90,0,1)));var own=d;assertNotNull(d.evaluateWorld());var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(own));assertTrue(error.getMessage().contains("mixShearY"));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void floatSignedZeroAngleIsRefusedEvenWithShearMixDisabled()throws Exception{
  var d=ModelEdits.editBone(fixture(false,true,.5),id(4),b->b.withLocal(new Transform(20,30,90,0,1)));var t=((TransformConstraint)d.constraints().get(0)).withMix(5,0);d=d.withConstraints(List.of(t));var own=d;var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(own));assertTrue(error.getMessage().contains("float-angular-certificate"));assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
 }
 @Test void localBoneListRequiresAncestorBeforeDescendant(){
  var d=fixture(true,false,.5);var t=(TransformConstraint)d.constraints().get(0);
  assertNotNull(d.withConstraints(List.of(t.withBones(List.of(id(2),id(3))))));
  assertTrue(assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(t.withBones(List.of(id(3),id(2)))))).getMessage().contains("ancestors first"));
 }
}
