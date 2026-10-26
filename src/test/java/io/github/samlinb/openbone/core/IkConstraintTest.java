package io.github.samlinb.openbone.core;

import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class IkConstraintTest {
    static DocumentSnapshot fixture(double x,double y,double mix,boolean compress,boolean stretch,boolean uniform){
        var bones=List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0),
            new Bone(id(2),"arm",id(1),Transform.IDENTITY,100),
            new Bone(id(3),"tip",id(2),new Transform(100,0,0,1,1),10),
            new Bone(id(4),"target",id(1),new Transform(x,y,0,1,1),0));
        var d=new DocumentSnapshot(bones,List.of(),List.of());
        return d.withConstraints(List.of(new IkConstraint(id(5),"aim",0,true,List.of(id(2)),id(4),mix,0,true,compress,stretch,uniform)));
    }
    static void near(double expected,double actual){assertEquals(expected,actual,1e-9+Math.abs(expected)*1e-10);}
    @ParameterizedTest @CsvSource({"100,100,0,0","100,100,.5,22.5","100,100,1,45","-100,100,1,135","-100,-100,1,-135","100,-100,1,-45","0,0,1,0"})
    void quadrantsMixAndDescendants(double x,double y,double mix,double angle){
        var d=fixture(x,y,mix,false,false,false);var w=d.evaluateWorld();var a=w.bones().get(id(2));
        near(Math.cos(Math.toRadians(angle))*100,a.tip().x());near(Math.sin(Math.toRadians(angle))*100,a.tip().y());
        near(a.tip().x(),w.bones().get(id(3)).origin().x());near(a.tip().y(),w.bones().get(id(3)).origin().y());
        assertEquals(Transform.IDENTITY,d.bone(id(2)).local());
    }
    @ParameterizedTest @CsvSource({"50,true,false,false,0.5,1","200,false,true,false,2,1","200,false,true,true,2,2","200,true,false,true,1,1","50,false,true,true,1,1"})
    void compressionStretchUniform(double x,boolean compress,boolean stretch,boolean uniform,double sx,double sy){
        var w=fixture(x,0,1,compress,stretch,uniform).evaluateWorld().bones().get(id(2)).matrix();near(sx,w.a());near(sy,w.d());
    }
    @Test void mirroredNonuniformParentAimsInParentSpace(){
        var d=fixture(100,80,1,false,false,false);d=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(15,-4,27,-2,.4)));
        var pose=d.evaluateWorld();var tip=pose.bones().get(id(2)).tip();var root=pose.bones().get(id(2)).origin();var target=pose.bones().get(id(4)).origin();
        near(0,(tip.x()-root.x())*(target.y()-root.y())-(tip.y()-root.y())*(target.x()-root.x()));
        assertTrue((tip.x()-root.x())*(target.x()-root.x())+(tip.y()-root.y())*(target.y()-root.y())>0);
    }
    @Test void signedZeroLengthAndSingularPolicies(){
        var d=fixture(0,100,1,true,true,true);
        var negative=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(0,0,0,-1,1)));
        near(100,negative.evaluateWorld().bones().get(id(2)).tip().y());near(1,Math.hypot(negative.evaluateWorld().bones().get(id(2)).matrix().a(),negative.evaluateWorld().bones().get(id(2)).matrix().c()));
        var zero=ModelEdits.editBone(d,id(2),b->new Bone(b.id(),b.name(),b.parentId(),b.local(),0));near(0,zero.evaluateWorld().bones().get(id(2)).tip().x());
        var singular=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,0,1)));assertNotNull(singular.evaluateWorld());
        assertThrows(IllegalArgumentException.class,()->ModelEdits.moveBoneWorld(singular,id(2),new Vec2(4,5)));
    }
    @Test void partialMixPreservesTargetOneWrapForMultipleTurnSetup(){
        var d=fixture(100,0,.25,false,false,false);d=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(0,0,1080,1,1)));
        double expected=1080+(-1080+360)*.25;near(Math.cos(Math.toRadians(expected)),d.evaluateWorld().bones().get(id(2)).matrix().a());
    }
    @Test void rootIkProjectExtensionAndRuntimeRejection()throws Exception{
        var root=new Bone(id(2),"arm",null,Transform.IDENTITY,100);var target=new Bone(id(4),"target",null,new Transform(0,100,0,1,1),0);
        var d=new DocumentSnapshot(List.of(root,target),List.of(),List.of()).withConstraints(fixture(1,1,1,false,false,false).constraints());
        near(100,d.evaluateWorld().bones().get(id(2)).tip().y());assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(d));
    }
    @Test void stableReferencesCycleOrderAndIdentityValidation(){
        var d=fixture(0,100,1,false,false,false);var c=(IkConstraint)d.constraint(id(5));
        assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(c.withTarget(id(3)))));
        assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(c.withTarget(id(99)))));
        assertThrows(IllegalArgumentException.class,()->d.withConstraints(List.of(c,new IkConstraint(id(6),"second",0,true,List.of(id(2)),id(4),1,0,true,false,false,false))));
        assertThrows(IllegalArgumentException.class,()->ConstraintEdits.editIk(d,id(5),v->new IkConstraint(id(6),v.name(),0,true,v.boneIds(),v.targetId(),1,0,true,false,false,false)));
        assertThrows(IllegalArgumentException.class,()->c.withMix(Double.NaN));assertThrows(IllegalArgumentException.class,()->c.withMix(1.01));
    }
    @Test void commandPreviewCascadeAndUndoPreserveEverything()throws Exception{
        var d=fixture(20,100,.5,false,false,false);var h=new CommandHistory(d);h.select(Selection.of(id(5)));var before=h.state();
        h.beginPreview("target");for(int n=0;n<20;n++){int i=n;h.updatePreview(Commands.model(v->ModelEdits.moveBoneWorld(v,id(4),new Vec2(i,50))));}h.cancelPreview();assertEquals(before,h.state());assertFalse(h.isDirty());
        h.beginPreview("target");h.updatePreview(Commands.model(v->ModelEdits.moveBoneWorld(v,id(4),new Vec2(80,20))));h.commitPreview();assertEquals(1,h.undoCount());h.undo();assertEquals(before,h.state());h.redo();
        h.execute("delete",Commands.model(v->ModelEdits.delete(v,id(4))));assertTrue(h.state().model().constraints().isEmpty());assertEquals(Selection.EMPTY,h.state().selection());h.undo();assertEquals(d.constraints(),h.state().model().constraints());
        var source=h.state().model();assertEquals(source.constraints(),ResourcePaths.absolute(source,Path.of("D:/tmp" )).constraints());
    }
    @Test void schema4AndStrictRuntimeRoundtrip()throws Exception{
        var d=fixture(20,100,.5,true,true,true);var codec=new ProjectCodec();var bytes=codec.encode(d);assertEquals(d,codec.decode(bytes));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(bytes);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("constraints").get(0)).put("enabled","true");assertThrows(ProjectFormatException.class,()->codec.decode(json.writeValueAsBytes(tree)));
        var runtime=new SkeletonJsonCodec();var exported=runtime.encode(d,Map.of(),"");var imported=runtime.decode(exported,Path.of("D:/tmp"));
        var ik=(IkConstraint)imported.constraints().get(0);assertEquals("aim",ik.name());near(.5,ik.mix());assertTrue(ik.compress()&&ik.stretch()&&ik.uniform());near(d.evaluateWorld().bones().get(id(2)).tip().x(),imported.evaluateWorld().bones().get(imported.bones().get(1).id()).tip().x());
    }
    @Test void skinAndDeformUseSolvedInfluenceMatricesAndKeepSetup(){
        var d=fixture(0,100,1,false,false,false);
        var vertices=List.of(new Vec2(0,0),new Vec2(0,10),new Vec2(10,0));var uv=List.of(new Vec2(0,0),new Vec2(0,1),new Vec2(1,0));
        var weights=vertices.stream().map(v->List.of(new Influence(id(2),v,.5),new Influence(id(4),v,.5))).toList();
        var m=new MeshAttachment(id(11),"skin",id(10),"missing.png",vertices,uv,3,List.of(0,1,2),weights);
        d=ModelEdits.addSlot(d,new Slot(id(10),"skin-slot",id(2),null));d=ModelEdits.addAttachment(d,m,true);
        var offset=List.of(2.,3.,4.,5.,0.,0.,0.,0.,0.,0.,0.,0.);var setup=d.bones();var w=d.evaluateWorld(Map.of(),Map.of(id(11),offset));
        // First influence is rotated90 at origin; second is unrotated at (0,100).
        near(.5*(-3+4),w.meshes().get(id(11)).vertices().get(0).x());near(.5*(2+105),w.meshes().get(id(11)).vertices().get(0).y());
        assertEquals(setup,d.bones());assertEquals(m,d.attachments().get(0));
    }
    @Test void runtimeCertificateRejectsOverflowIntroducedOnlyBySolve()throws Exception{
        var d=fixture(1e20,0,1,false,true,false);d=ModelEdits.editBone(d,id(2),b->new Bone(b.id(),b.name(),b.parentId(),b.local(),1e-3));
        d=ModelEdits.addSlot(d,new Slot(id(10),"huge",id(2),null));d=ModelEdits.addAttachment(d,new RegionAttachment(id(11),"huge",id(10),"missing.png",Transform.IDENTITY,1e20,1),true);
        RuntimeRange.validate(d.withConstraints(List.of()));var constrained=d;assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(constrained));
        assertTrue(d.evaluateWorld().regions().get(id(11)).vertices().stream().anyMatch(v->Math.abs(v.x())>Float.MAX_VALUE));
        assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
    }
}
