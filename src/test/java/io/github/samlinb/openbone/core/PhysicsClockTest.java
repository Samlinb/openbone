package io.github.samlinb.openbone.core;

import java.util.*;
import org.junit.jupiter.api.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class PhysicsClockTest {
 static PhysicsConstraint x(){return PhysicsSolverTest.fixture(List.of(1.,0.,0.,0.,0.)).withParameter(5,0).withLimit(60);}
 static PhysicsSolver.Result at(PhysicsConstraint p,double x,PhysicsSolver.State state,PhysicsSolver.Mode mode,int tick){return PhysicsSolver.apply(p,new Transform(x,0,0,1,1).matrix(),100,state,mode,tick);}
 @Test void zeroMixFreezesClockAndReactivationInjectsInertiaOnlyOnce(){
  var p=x();var initial=at(p,0,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0).state();var inactive=p.withParameter(6,0);var first=at(inactive,10,initial,PhysicsSolver.Mode.UPDATE,1);assertEquals(initial,first.state());var second=at(inactive,20,first.state(),PhysicsSolver.Mode.UPDATE,2);assertEquals(initial,second.state());var resumed=at(p,30,second.state(),PhysicsSolver.Mode.UPDATE,3);double dt=PhysicsConstraint.STEP,offset=-3+100*dt*dt*6;PhysicsSolverTest.near(offset,resumed.state().xOffset());PhysicsSolverTest.near(30+offset,resumed.matrix().tx());PhysicsSolverTest.near(100*dt*3,resumed.state().xVelocity());assertEquals(3,resumed.state().steps());assertEquals(3,resumed.state().lastTick());assertEquals(0,resumed.state().pendingTicks());
 }
 @Test void firstActivationOnlyInitializesAndNextUpdateConsumesAccumulatedTicks(){
  var p=x();var state=at(p.withParameter(6,0),0,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0).state();assertFalse(state.initialized());var initial=at(p,20,state,PhysicsSolver.Mode.UPDATE,5);assertTrue(initial.state().initialized());assertEquals(5,initial.state().lastTick());assertEquals(5,initial.state().pendingTicks());assertEquals(0,initial.state().steps());assertEquals(20,initial.matrix().tx());var next=at(p,20,initial.state(),PhysicsSolver.Mode.UPDATE,6);PhysicsSolverTest.near(100*PhysicsConstraint.STEP*PhysicsConstraint.STEP*21,next.state().xOffset());assertEquals(6,next.state().steps());assertEquals(0,next.state().pendingTicks());
 }
 @Test void angularDirectionUpdatesBetweenEachCatchupIntegration(){
  var p=PhysicsSolverTest.fixture(List.of(0.,0.,1.,1.,0.)).withParameter(0,0).withParameter(3,2).withParameter(4,.7).withParameter(5,.2);var state=PhysicsSolver.apply(p,Affine2.IDENTITY,100,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0).state();var actual=PhysicsSolver.apply(p,Affine2.IDENTITY,100,state,PhysicsSolver.Mode.UPDATE,5);double ro=0,rv=0,so=0,sv=0,dt=PhysicsConstraint.STEP;for(int n=0;n<5;n++){double c=Math.cos(ro),s=Math.sin(ro);sv+=(.7*c-.2*s)*dt/2;so+=sv*dt;rv-=(.7*s+.2*c)*dt/2;ro+=rv*dt;}PhysicsSolverTest.near(ro,actual.state().rotationOffset());PhysicsSolverTest.near(so,actual.state().scaleOffset());PhysicsSolverTest.near(Math.cos(ro)*(1+so),actual.matrix().a());PhysicsSolverTest.near(-Math.sin(ro),actual.matrix().b());assertEquals(5,actual.state().steps());
 }
 @Test void poseNoneSameTickAndResetDoNotAdvanceAccumulatedTime(){
  var p=x();var reset=at(p,0,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,100);assertEquals(100,reset.state().lastTick());assertEquals(0,reset.state().pendingTicks());var same=at(p,0,reset.state(),PhysicsSolver.Mode.UPDATE,100);assertEquals(reset.state(),same.state());var posed=at(p,0,same.state(),PhysicsSolver.Mode.POSE,120);assertEquals(same.state(),posed.state());assertEquals(same.state(),at(p,0,same.state(),PhysicsSolver.Mode.NONE,120).state());var advanced=at(p,0,same.state(),PhysicsSolver.Mode.UPDATE,103);assertEquals(3,advanced.state().steps());var cleared=at(p,0,advanced.state(),PhysicsSolver.Mode.RESET,500);assertEquals(0,cleared.state().steps());assertEquals(500,cleared.state().lastTick());assertEquals(0,cleared.state().xOffset());assertThrows(IllegalArgumentException.class,()->at(p,0,advanced.state(),PhysicsSolver.Mode.UPDATE,102));assertThrows(IllegalArgumentException.class,()->at(p,0,advanced.state(),PhysicsSolver.Mode.UPDATE,36001));assertEquals(3,advanced.state().steps());
 }
 @Test void noInfluenceAxesKeepPendingTimeWithoutInventingIntegration(){
  var p=PhysicsSolverTest.fixture(Collections.nCopies(5,0.));var initial=PhysicsSolver.apply(p,Affine2.IDENTITY,100,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0);var later=PhysicsSolver.apply(p,Affine2.IDENTITY,100,initial.state(),PhysicsSolver.Mode.UPDATE,10);assertEquals(10,later.state().lastTick());assertEquals(10,later.state().pendingTicks());assertEquals(0,later.state().steps());assertEquals(Affine2.IDENTITY,later.matrix());
 }
 @Test void fullHorizonCatchupIsFiniteBoundedAndAnalyticallyExact(){
  var p=x();var start=at(p,0,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0);var end=at(p,0,start.state(),PhysicsSolver.Mode.UPDATE,36000);assertEquals(36000,end.state().steps());assertEquals(36000,end.state().lastTick());assertEquals(0,end.state().pendingTicks());double expected=100*PhysicsConstraint.STEP*PhysicsConstraint.STEP*(36000.*36001/2);PhysicsSolverTest.near(expected,end.state().xOffset());
 }
 @Test void tenIdenticalPauseReactivateSchedulesAreBitExact(){
  var p=x().withParameter(1,100).withParameter(2,.95);List<PhysicsSolver.Result> baseline=new ArrayList<>();for(int run=0;run<10;run++){var state=at(p,0,PhysicsSolver.State.ZERO,PhysicsSolver.Mode.RESET,0).state();for(int tick=1;tick<=600;tick++){var parameters=p.withParameter(6,tick%11<4?0:1);var result=at(parameters,Math.sin(tick*.03)*20,state,PhysicsSolver.Mode.UPDATE,tick);state=result.state();if(run==0)baseline.add(result);else assertEquals(baseline.get(tick-1),result);}}
 }
}
