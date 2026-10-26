package io.github.samlinb.openbone.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class PhysicsParameterTimelineTest {
 static final EntityId CLIP=AnimationClip.DEFAULT_ID;
 static DocumentSnapshot base(){return new DocumentSnapshot(List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0),new Bone(id(2),"physical",id(1),Transform.IDENTITY,100)),List.of(),List.of()).withConstraints(List.of(new PhysicsConstraint(id(5),"spring",0,true,id(2),List.of(1.,0.,0.,0.,0.),60,List.of(0.,0.,1.,1.,1.,0.,1.))));}
 static DocumentSnapshot key(DocumentSnapshot d,ConstraintChannel channel,double time,double value){return AnimationEdits.putConstraint(d,CLIP,id(5),channel,time,List.of(value));}
 static DocumentSnapshot steps(DocumentSnapshot d){return AnimationEdits.curves(d,CLIP,d.animation(CLIP).keys().stream().map(Keyframe::id).collect(java.util.stream.Collectors.toSet()),List.of(Curve.STEPPED));}
 static PhysicsConstraint sample(DocumentSnapshot d,double time){return (PhysicsConstraint)AnimationSampler.constraints(d,CLIP,time,false).get(0);}
 @ParameterizedTest @EnumSource(value=ConstraintChannel.class,names={"PHYSICS_INERTIA","PHYSICS_STRENGTH","PHYSICS_DAMPING","PHYSICS_MASS","PHYSICS_WIND","PHYSICS_GRAVITY","PHYSICS_MIX"}) void sevenAbsoluteChannelsPreserveUnitAndOwnIdentity(ConstraintChannel channel)throws Exception{
  double a=switch(channel){case PHYSICS_DAMPING,PHYSICS_MASS->1;default->0;},b=switch(channel){case PHYSICS_STRENGTH->100;case PHYSICS_MASS->2;case PHYSICS_DAMPING->.5;default->1;};var d=key(base(),channel,.25,a);d=key(d,channel,1.25,b);assertEquals((a+b)/2,channel.values(sample(d,.75)).get(0),1e-12);assertEquals(channel.values(base().constraint(id(5))),channel.values(sample(d,0)));var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));assertEquals(1.25,d.animation(CLIP).duration());var copied=AnimationEdits.copy(d,CLIP,Set.of(d.animation(CLIP).keys().get(0).id()));var pasted=AnimationEdits.paste(d,CLIP,copied,2);assertEquals(3,pasted.animation(CLIP).constraintTracks().get(0).keys().size());assertEquals(3,pasted.animation(CLIP).keys().stream().map(Keyframe::id).distinct().count());var candidate=d;if(channel==ConstraintChannel.PHYSICS_MIX)assertTrue(assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(candidate)).getMessage().contains(".curve"));else assertDoesNotThrow(()->RuntimeRange.validate(candidate));
 }
 @Test void changingWindHasIndependentClosedFormSumOfCubes(){
  var d=key(base(),ConstraintChannel.PHYSICS_WIND,0,0);d=key(d,ConstraintChannel.PHYSICS_WIND,1,1);var p=new PhysicsPlayer();var result=p.seek(d,CLIP,1,false);double dt=PhysicsConstraint.STEP;PhysicsSolverTest.near(100*dt*dt*60*61/2,p.states().get(id(5)).xVelocity());PhysicsSolverTest.near(100*dt*dt*dt*60*61*62/6,result.bones().get(id(2)).origin().x());assertEquals(60,p.states().get(id(5)).steps());
 }
 @Test void massValuesAreMassAndAllCoefficientsAreSampledBeforeTheFixedStep(){
  var d=base();d=key(d,ConstraintChannel.PHYSICS_MASS,0,1);d=key(d,ConstraintChannel.PHYSICS_MASS,1,2);d=key(d,ConstraintChannel.PHYSICS_STRENGTH,0,0);d=key(d,ConstraintChannel.PHYSICS_STRENGTH,1,100);d=key(d,ConstraintChannel.PHYSICS_DAMPING,0,1);d=key(d,ConstraintChannel.PHYSICS_DAMPING,1,.5);d=key(d,ConstraintChannel.PHYSICS_WIND,0,.5);d=key(d,ConstraintChannel.PHYSICS_WIND,1,1);var player=new PhysicsPlayer();double offset=0,velocity=0,dt=PhysicsConstraint.STEP;for(int n=1;n<=60;n++){double time=n/60.;velocity+=(100*(.5+.5*time)-offset*100*time)*dt/(1+time);offset+=velocity*dt;velocity*=1-.5*time;var actual=player.seek(d,CLIP,time,false);PhysicsSolverTest.near(offset,actual.bones().get(id(2)).origin().x());PhysicsSolverTest.near(velocity,player.states().get(id(5)).xVelocity());}assertEquals(1.5,sample(d,.5).mass());
 }
 @Test void steppedMixDisableReactivateAndLoopSeekMatchTheClockContract(){
  var d=key(base(),ConstraintChannel.PHYSICS_MIX,0,1);d=key(d,ConstraintChannel.PHYSICS_MIX,1./60,0);d=key(d,ConstraintChannel.PHYSICS_MIX,3./60,1);d=key(d,ConstraintChannel.PHYSICS_MIX,6./60,1);d=steps(d);var player=new PhysicsPlayer();assertEquals(0,player.seek(d,CLIP,2./60,false).bones().get(id(2)).origin().x());assertEquals(0,player.states().get(id(5)).lastTick());var resumed=player.seek(d,CLIP,3./60,false);PhysicsSolverTest.near(100*PhysicsConstraint.STEP*PhysicsConstraint.STEP*6,resumed.bones().get(id(2)).origin().x());assertEquals(3,player.states().get(id(5)).steps());long count=player.evaluations();assertEquals(resumed,player.seek(d,CLIP,3./60,false));assertEquals(count,player.evaluations());double phase=AnimationSampler.sampleTime(9./60,d.animation(CLIP).duration(),true);var loopPlayer=new PhysicsPlayer();assertEquals(new PhysicsPlayer().seek(d,CLIP,phase,false),loopPlayer.seek(d,CLIP,9./60,true));assertEquals(PhysicsEnvelope.tickAt(phase),loopPlayer.frame());assertEquals(resumed,new PhysicsPlayer().seek(d,CLIP,3./60,false));
 }
 @Test void setupMixZeroStillCreatesAnActivePreviewAndFirstActivationRetainsPending(){
  var initial=base();initial=initial.withConstraints(List.of(((PhysicsConstraint)initial.constraint(id(5))).withParameter(6,0)));var d=key(initial,ConstraintChannel.PHYSICS_MIX,0,0);d=key(d,ConstraintChannel.PHYSICS_MIX,3./60,1);d=key(d,ConstraintChannel.PHYSICS_MIX,6./60,1);d=steps(d);assertTrue(PhysicsPlayer.active(d));var player=new PhysicsPlayer();assertEquals(0,player.seek(d,CLIP,3./60,false).bones().get(id(2)).origin().x());assertEquals(3,player.states().get(id(5)).pendingTicks());var later=player.seek(d,CLIP,4./60,false);PhysicsSolverTest.near(100*PhysicsConstraint.STEP*PhysicsConstraint.STEP*10,later.bones().get(id(2)).origin().x());assertEquals(4,player.states().get(id(5)).steps());
 }
 @Test void subTickPoseUsesCurrentMixAndDoesNotIntegrateOrDirtyHistory(){
  var d=key(base(),ConstraintChannel.PHYSICS_MIX,0,.25);d=key(d,ConstraintChannel.PHYSICS_MIX,1,1);var h=new CommandHistory(d);var player=new PhysicsPlayer();player.seek(d,CLIP,1./60,false);var state=player.states();long evaluations=player.evaluations();double time=1./60+.001;var posed=player.seek(d,CLIP,time,false);PhysicsSolverTest.near(100*PhysicsConstraint.STEP*PhysicsConstraint.STEP*(.25+.75*time),posed.bones().get(id(2)).origin().x());assertEquals(state,player.states());assertEquals(evaluations,player.evaluations());assertEquals(d,h.state().model());assertFalse(h.isDirty());assertEquals(0,h.undoCount());
 }
 @Test void invalidParameterControlHullsAndUnstableCoupledHullRejectAtomically(){
  var mass=key(base(),ConstraintChannel.PHYSICS_MASS,0,1);mass=key(mass,ConstraintChannel.PHYSICS_MASS,1,2);var candidate=mass;var first=mass.animation(CLIP).keys().get(0).id();assertThrows(IllegalArgumentException.class,()->AnimationEdits.curves(candidate,CLIP,Set.of(first),List.of(new Curve(Curve.Kind.BEZIER,.2,-2,.8,1))));var strength=new ConstraintTimeline(id(5),ConstraintChannel.PHYSICS_STRENGTH,List.of(new Keyframe(EntityId.create(),0,List.of(100.),List.of(Curve.LINEAR))));var lowMass=new ConstraintTimeline(id(5),ConstraintChannel.PHYSICS_MASS,List.of(new Keyframe(EntityId.create(),0,List.of(.01),List.of(Curve.LINEAR))));assertTrue(assertThrows(IllegalArgumentException.class,()->base().withAnimations(List.of(new AnimationClip(CLIP,"unsafe hull",List.of(),List.of(),List.of(strength,lowMass))))).getMessage().contains("control hull"));
 }
 @Test void parametricGrowthCanOverflowDespiteEveryCoefficientBeingPointwiseStable(){
  // A(q=0)^4 A(q=1) has determinant 1 and trace -3, hence an eigenvalue
  // of magnitude (3+sqrt(5))/2 > 1. The old constant-coefficient bound is invalid.
  var keys=new ArrayList<Keyframe>();for(int n=0;n<1200;n++){keys.add(new Keyframe(EntityId.create(),n*5./60,List.of(3600.),List.of(Curve.STEPPED)));keys.add(new Keyframe(EntityId.create(),(n*5.+1)/60,List.of(0.),List.of(Curve.STEPPED)));}keys.add(new Keyframe(EntityId.create(),100,List.of(0.),List.of(Curve.STEPPED)));var clip=new AnimationClip(CLIP,"parametric resonance",List.of(),List.of(),List.of(new ConstraintTimeline(id(5),ConstraintChannel.PHYSICS_STRENGTH,keys)));var h=new CommandHistory(base());var before=h.state();var failure=assertThrows(IllegalArgumentException.class,()->h.execute("unsafe dynamics",Commands.model(d->d.withAnimations(List.of(clip)))));assertTrue(failure.getMessage().contains("physics X velocity"),failure.getMessage());assertEquals(before,h.state());assertEquals(0,h.undoCount());assertFalse(h.isDirty());
 }
 @Test void dynamicProofWorkBudgetIsCheckedBeforeReplay(){
  var d=base();var bones=new ArrayList<>(d.bones());for(int n=10;n<1010;n++)bones.add(new Bone(id(n),"idle"+n,id(1),Transform.IDENTITY,0));var setup=new DocumentSnapshot(bones,d.slots(),d.attachments(),d.animations(),d.constraints());assertTrue(assertThrows(IllegalArgumentException.class,()->key(setup,ConstraintChannel.PHYSICS_WIND,60,2)).getMessage().contains("2M"));
 }
 @Test void representableInstantBeforeTickNeverConsumesTheNextStep(){for(int tick:new int[]{1,3,31,121,36000}){double time=tick/60.;assertEquals(tick,PhysicsEnvelope.tickAt(time));assertEquals(tick-1,PhysicsEnvelope.tickAt(Math.nextDown(time)));if(tick<36000)assertEquals(tick,PhysicsEnvelope.tickAt(Math.nextUp(time)));}var d=key(base(),ConstraintChannel.PHYSICS_WIND,1,1);var player=new PhysicsPlayer();player.seek(d,CLIP,Math.nextDown(3./60),false);assertEquals(2,player.frame());assertEquals(2,player.states().get(id(5)).steps());player.seek(d,CLIP,3./60,false);assertEquals(3,player.states().get(id(5)).steps());}
 @Test void physicsWorldOutputCannotSilentlyBecomeALocalTransformTarget(){
  var d=base();var bones=new ArrayList<>(d.bones());bones.add(new Bone(id(3),"other",id(1),Transform.IDENTITY,20));var local=new TransformConstraint(id(6),"local follower",1,true,List.of(id(3)),id(2),true,false,Collections.nCopies(6,1.),Collections.nCopies(6,0.));var candidate=d;assertTrue(assertThrows(IllegalArgumentException.class,()->new DocumentSnapshot(bones,candidate.slots(),candidate.attachments(),candidate.animations(),List.of(candidate.constraint(id(5)),local))).getMessage().contains("local"));
 }
}
