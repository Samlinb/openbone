package io.github.samlinb.openbone.io;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class Ik2StaticBranchesTest {
 static DocumentSnapshot original()throws Exception{try(var in=Ik2StaticBranchesTest.class.getResourceAsStream("/regressions/p7-ind-03.json")){assertNotNull(in);return new ProjectCodec().decode(in.readAllBytes());}}
 static DocumentSnapshot safe()throws Exception{var d=FloatPrecisionCertificateTest.safeZero();return d.withAnimations(List.of(AnimationClip.DEFAULT));}
 static String branch(DocumentSnapshot d){var ik=(IkConstraint)d.constraints().get(0);return Ik2PrecisionCertificate.staticSolution(d,ik,Ik2PrecisionCertificate.input(d,ik)).branch();}
 static void refuses(DocumentSnapshot d)throws Exception{assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));var pose=d.evaluateWorld();var e=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(d));assertTrue(e.getMessage().contains("float-precision"),e.getMessage());assertEquals(pose,d.evaluateWorld());}
 @Test void originalThirdIndependentStaticEllipseIsAuditedAndLossless()throws Exception{var d=original();assertTrue(branch(d).startsWith("ELLIPSE"));refuses(d);}
 @Test void floatAuditNormalInheritancePreservesTheZeroShearSignedZeroAddition()throws Exception{var d=ModelEdits.editBone(safe(),id(1),b->b.withLocal(new Transform(0,0,-0.0,1,1)));var ik=(IkConstraint)d.constraints().get(0);assertEquals(0,Float.floatToRawIntBits(Ik2PrecisionCertificate.input(d,ik).floats().get(id(1)).c()));}
 @Test void safeStaticEllipseRetainsBothBendsAndMirrors()throws Exception{for(double sx:new double[]{-2,2})for(double sy:new double[]{-.8,.8})for(boolean bend:new boolean[]{false,true}){var d=ModelEdits.editBone(safe(),id(2),b->b.withLocal(new Transform(-40,-20,10,sx,sy)));d=d.withConstraints(List.of(((IkConstraint)d.constraints().get(0)).withOptions(bend,false,false,false)));var candidate=d;assertTrue(branch(d).startsWith("ELLIPSE"));assertDoesNotThrow(()->RuntimeRange.validate(candidate));}}
 @Test void staticEllipseComparesRootsAndAuditsBothExtremaFallbacks()throws Exception{Set<String> branches=new HashSet<>();for(double sx:new double[]{.4,2})for(double sy:new double[]{.8,3})for(double x:new double[]{-400,-100,-40,0,20,60,100,400})for(double y:new double[]{-20,40,150}){var d=ModelEdits.editBone(safe(),id(2),b->b.withLocal(new Transform(-40,-20,10,sx,sy)));d=ModelEdits.editBone(d,id(4),b->b.withLocal(new Transform(x,y,0,1,1)));branches.add(branch(d));}assertTrue(branches.containsAll(Set.of("ELLIPSE_ROOT1","ELLIPSE_EXTREMA_INNER","ELLIPSE_EXTREMA_OUTER")),branches.toString());}
 @Test void staticFirstSegmentFallbackIsComparedIncludingSignedStretch()throws Exception{for(double sx:new double[]{-1,1})for(boolean stretch:new boolean[]{false,true}){var d=ModelEdits.editBone(safe(),id(2),b->b.withLocal(new Transform(-40,-20,10,sx,1)));d=ModelEdits.editBone(d,id(3),b->b.withLocal(new Transform(0,0,15,1,1)));d=d.withConstraints(List.of(((IkConstraint)d.constraints().get(0)).withOptions(true,false,stretch,false)));var candidate=d;assertEquals("IK1_FALLBACK",branch(d));assertDoesNotThrow(()->RuntimeRange.validate(candidate));}}
 @Test void fallbackAndEllipseMustIncludeLongDescendantEndpoints()throws Exception{for(boolean fallback:new boolean[]{false,true}){var d=ModelEdits.editBone(safe(),id(2),b->b.withLocal(new Transform(-40,-20,10,fallback?1:2,fallback?1:.8)));if(fallback)d=ModelEdits.editBone(d,id(3),b->b.withLocal(new Transform(0,0,15,1,1)));var pose=d.evaluateWorld().bones().get(id(3));double length=1e7,rotation=Math.toDegrees(Math.atan2(pose.matrix().b(),pose.matrix().a())+Math.acos(-pose.origin().x()/length/Math.hypot(pose.matrix().a(),pose.matrix().b())));var bones=new ArrayList<>(d.bones());bones.add(new Bone(id(77),"static amplified descendant",id(3),new Transform(0,0,rotation,1,1),length));refuses(new DocumentSnapshot(bones,d.slots(),d.attachments(),d.animations(),d.constraints()));}}
}
