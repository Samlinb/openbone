package io.github.samlinb.openbone.io;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class ConstraintClipCertificateTest {
 @org.junit.jupiter.api.io.TempDir Path folder;
 static DocumentSnapshot fixture(boolean local,boolean relative){
  var bones=List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0),new Bone(id(2),"source",id(1),new Transform(-5,0,0,1,1),40),new Bone(id(3),"target",id(1),new Transform(10,5,15,1,1),20),new Bone(id(4),"descendant",id(2),new Transform(5,0,0,1,1),10));
  var slots=List.of(new Slot(id(10),"clip",id(1),id(20)),new Slot(id(11),"draw",id(4),id(21)));var attachments=List.<Attachment>of(new ClippingAttachment(id(20),"clip",id(10),List.of(new Vec2(-30,-30),new Vec2(30,-30),new Vec2(30,30),new Vec2(-30,30)),id(11)),new RegionAttachment(id(21),"region",id(11),"unused.png",Transform.IDENTITY,100,80));
  return new DocumentSnapshot(bones,slots,attachments).withConstraints(List.of(new TransformConstraint(id(30),"xy",0,true,List.of(id(2)),id(3),local,relative,List.of(0.,.5,.75,0.,0.,0.),List.of(0.,2.,-3.,0.,0.,0.))));
 }
 @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"}) void allFourTranslationModesProveAnimatedDescendantsAndWeightedDeform(boolean local,boolean relative)throws Exception{
  var d=fixture(local,relative);var clip=d.animations().get(0).id();d=AnimationEdits.put(d,clip,id(3),Channel.TRANSLATE,0,List.of(0.,0.));d=AnimationEdits.put(d,clip,id(3),Channel.TRANSLATE,1,List.of(6.,-4.));d=MeshEdits.convert(d,id(21));d=WeightEdits.bind(d,id(21),List.of(new WeightEdits.Binding(id(2),.5),new WeightEdits.Binding(id(4),.5)));var values=new ArrayList<>(Collections.nCopies(MeshEdits.mesh(d,id(21)).deformSize(),0.));d=DeformEdits.put(d,clip,id(21),0,values);values.set(0,2.);d=DeformEdits.put(d,clip,id(21),1,values);var key=d.animation(clip).deforms().get(0).keys().get(0);d=AnimationEdits.curves(d,clip,Set.of(key.id()),List.of(new Curve(Curve.Kind.BEZIER,.2,-.1,.8,1.1)));var candidate=d;
  assertDoesNotThrow(()->RuntimeRange.validate(candidate));javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(100,80,java.awt.image.BufferedImage.TYPE_INT_ARGB),"PNG",folder.resolve("region.png").toFile());var codec=new SkeletonJsonCodec();var restored=codec.decode(codec.encode(d,Map.of(id(21),"region"),""),folder);assertEquals(1,restored.constraints().size());assertEquals(1,restored.animations().get(0).deforms().size());assertNotEquals(d.withConstraints(List.of()).evaluateWorld().bones().get(id(4)).matrix().tx(),d.evaluateWorld().bones().get(id(4)).matrix().tx());
 }
 @Test void solvedTranslationCannotHideOverflowBehindSafeUnconstrainedGeometry()throws Exception{
  var d=fixture(false,false);d=ModelEdits.editBone(d,id(3),b->b.withLocal(new Transform(1e18,0,0,1,1)));var candidate=d;assertDoesNotThrow(()->ClipFloatCertificate.validate(candidate.withConstraints(List.of())));var e=assertThrows(ProjectFormatException.class,()->ClipFloatCertificate.validate(candidate));assertTrue(e.getMessage().contains("cross products"),e.getMessage());
 }
 @Test void angularOrMultipleActiveConstraintsRemainPreciseRefusals()throws Exception{
  var d=fixture(false,false);var t=(TransformConstraint)d.constraints().get(0);var angular=d.withConstraints(List.of(t.withMix(0,1)));assertTrue(assertThrows(ProjectFormatException.class,()->ClipFloatCertificate.validate(angular)).getMessage().contains("translation-only"));var other=new TransformConstraint(id(31),"second",1,true,List.of(id(4)),id(3),true,false,t.mix(),t.offset());var multiple=d.withConstraints(List.of(t,other));assertTrue(assertThrows(ProjectFormatException.class,()->ClipFloatCertificate.validate(multiple)).getMessage().contains("one single-bone"));
 }
 @Test void disabledAngularConstraintDoesNotInventAnActiveWriter()throws Exception{var d=fixture(false,false);var t=(TransformConstraint)d.constraints().get(0);var disabled=d.withConstraints(List.of(t.withMix(0,1).withEnabled(false)));assertDoesNotThrow(()->RuntimeRange.validate(disabled));}
}
