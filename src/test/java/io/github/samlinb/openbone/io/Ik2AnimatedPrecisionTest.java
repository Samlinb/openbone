package io.github.samlinb.openbone.io;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class Ik2AnimatedPrecisionTest {
 static DocumentSnapshot original()throws Exception{try(var in=Ik2AnimatedPrecisionTest.class.getResourceAsStream("/regressions/p7-ind-02.json")){assertNotNull(in);return new ProjectCodec().decode(in.readAllBytes());}}
 static DocumentSnapshot safe()throws Exception{var d=Ik2PrecisionCertificateTest.safe();return d.withConstraints(List.of(((IkConstraint)d.constraints().get(0)).withSoftness(0).withOptions(true,false,false,false)));}
 static DocumentSnapshot line(DocumentSnapshot d,double x,double y,double dx,double dy){d=ModelEdits.editBone(d,id(4),b->b.withLocal(new Transform(x,y,0,1,1)));var clip=d.animations().get(0).id();d=AnimationEdits.put(d,clip,id(4),Channel.TRANSLATE,0,List.of(0.,0.));return AnimationEdits.put(d,clip,id(4),Channel.TRANSLATE,1,List.of(dx,dy));}
 static String refuses(DocumentSnapshot d)throws Exception{var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(d));assertTrue(error.getMessage().contains("float-precision"),error.getMessage());return error.getMessage();}
 @Test void exactSecondIndependentNoRasterProjectIsLosslessButCannotPublish()throws Exception{var d=original();var pose=d.evaluateWorld();assertTrue(refuses(d).contains("hull"));assertEquals(pose,d.evaluateWorld());}
 @Test void zeroSoftnessLinearCrossingBothCircleBoundariesCoversMirrorsAndBothBends()throws Exception{for(double sx:new double[]{-1,1})for(double sy:new double[]{-1,1})for(boolean bend:new boolean[]{false,true})for(double radius:new double[]{70,130}){var d=safe();d=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,sx,sy)));d=d.withConstraints(List.of(((IkConstraint)d.constraints().get(0)).withOptions(bend,false,false,false)));refuses(line(d,-40+radius-.02,-20,.04,0));}}
 @Test void safeZeroSoftnessInteriorMotionAndStableOuterClampRemainAvailable()throws Exception{var inside=line(safe(),20,60,5,-5);assertDoesNotThrow(()->RuntimeRange.validate(inside));var outside=line(safe(),460,-20,5,0);assertDoesNotThrow(()->RuntimeRange.validate(outside));}
 @Test void zeroSoftnessChangingCausalRotationCannotBypassTheFullHullProof()throws Exception{var d=safe();d=AnimationEdits.put(d,d.animations().get(0).id(),id(1),Channel.ROTATE,1,List.of(1.));assertTrue(refuses(d).contains("causal"));}
 @Test void zeroSoftnessDescendantOnlyMotionCannotBypassEndpointAmplification()throws Exception{var d=safe();var bones=new ArrayList<>(d.bones());bones.add(new Bone(id(77),"moving lever",id(3),Transform.IDENTITY,1));d=new DocumentSnapshot(bones,d.slots(),d.attachments(),d.animations(),d.constraints());RuntimeRange.validate(d);d=AnimationEdits.put(d,d.animations().get(0).id(),id(77),Channel.SCALE,1,List.of(1e7,1e7));refuses(d);}
 @Test void zeroSoftnessNonuniformEllipseAndFirstSegmentFallbackRefuseUnprovedMotion()throws Exception{var ellipse=ModelEdits.editBone(safe(),id(2),b->b.withLocal(new Transform(-40,-20,10,2,.8)));assertTrue(refuses(line(ellipse,20,60,5,-5)).contains("ellipse"));var fallback=ModelEdits.editBone(safe(),id(3),b->b.withLocal(new Transform(0,0,15,1,1)));assertTrue(refuses(line(fallback,20,60,5,-5)).contains("fallback"));}
 @Test void zeroMixSetupCannotHideAnUnsafeSteppedActivation()throws Exception{var d=original();var ik=((IkConstraint)d.constraints().get(0)).withSoftness(0);d=ModelEdits.editBone(d,id(4),b->b.withLocal(new Transform(90,-20,0,1,1)));d=d.withConstraints(List.of(ik.withMix(0)));var old=d.animations().get(0);var key=new Keyframe(id(76),1,ConstraintChannel.IK.values(ik),Collections.nCopies(5,Curve.STEPPED));d=d.withAnimations(List.of(new AnimationClip(old.id(),old.name(),List.of(),List.of(),List.of(new ConstraintTimeline(ik.id(),ConstraintChannel.IK,List.of(key))))));refuses(d);}
}
