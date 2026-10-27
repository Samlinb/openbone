package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class WeightTest {
    @TempDir Path temp;
    static DocumentSnapshot fixture(){
        var points=List.of(new Vec2(-30,-20),new Vec2(-30,20),new Vec2(30,20),new Vec2(30,-20));var mesh=new MeshAttachment(id(20),"mesh",id(10),"image.png",points,List.of(new Vec2(0,1),new Vec2(0,0),new Vec2(1,0),new Vec2(1,1)),4,MeshTopology.triangulate(points,4));
        return new DocumentSnapshot(List.of(bone(1,"root",null,new Transform(10,20,25,1.3,.7)),bone(2,"a",id(1),new Transform(40,0,15,-1,2)),bone(3,"b",id(1),new Transform(0,30,-20,.6,-1)),bone(4,"c",id(2),new Transform(20,10,30,1,1))),List.of(new Slot(id(10),"slot",id(1),id(20))),List.of(mesh));
    }
    static void near(List<Vec2> expected,List<Vec2> actual){assertEquals(expected.size(),actual.size());for(int i=0;i<actual.size();i++){assertEquals(expected.get(i).x(),actual.get(i).x(),1e-9+1e-10*Math.abs(expected.get(i).x()));assertEquals(expected.get(i).y(),actual.get(i).y(),1e-9+1e-10*Math.abs(expected.get(i).y()));}}
    @ParameterizedTest @ValueSource(ints={1,2,4}) void bindingCapturesBoneLocalAndTracksPoseWithoutWorldHardcoding(int count){
        var d=fixture();var bindings=new ArrayList<WeightEdits.Binding>();for(int b=1;b<=count;b++)bindings.add(new WeightEdits.Binding(id(b),b));var skin=WeightEdits.bind(d,id(20),bindings);near(d.evaluateWorld().meshes().get(id(20)).vertices(),skin.evaluateWorld().meshes().get(id(20)).vertices());
        var changed=ModelEdits.editBone(skin,id(1),b->b.withLocal(new Transform(50,-10,-45,-2,.3)));var originalWorld=skin.evaluateWorld().meshes().get(id(20)).vertices();assertNotEquals(originalWorld,changed.evaluateWorld().meshes().get(id(20)).vertices());
        var m=MeshEdits.mesh(changed,id(20));var pose=changed.evaluateWorld();for(int v=0;v<m.vertices().size();v++){double x=0,y=0;for(var i:m.weights().get(v)){var matrix=pose.bones().get(i.boneId()).matrix();var p=i.point();x+=(matrix.a()*p.x()+matrix.b()*p.y()+matrix.tx())*i.weight();y+=(matrix.c()*p.x()+matrix.d()*p.y()+matrix.ty())*i.weight();}assertEquals(x,pose.meshes().get(m.id()).vertices().get(v).x());assertEquals(y,pose.meshes().get(m.id()).vertices().get(v).y());}
    }
    @Test void safeNormalizationLargeTinyAndZeros(){
        for(double scale:new double[]{1,1e300,1e-300}){var n=Influence.normalize(List.of(new Influence(id(1),new Vec2(0,0),scale),new Influence(id(2),new Vec2(0,0),scale*2)));assertEquals(1.0/3,n.get(0).weight());assertEquals(2.0/3,n.get(1).weight());}
        var zeros=Influence.normalize(List.of(new Influence(id(1),new Vec2(0,0),0),new Influence(id(2),new Vec2(0,0),2)));assertEquals(0,zeros.get(0).weight());assertEquals(1,zeros.get(1).weight());
        assertThrows(IllegalArgumentException.class,()->Influence.normalize(List.of(new Influence(id(1),new Vec2(0,0),0))));assertThrows(IllegalArgumentException.class,()->Influence.normalize(List.of(new Influence(id(1),new Vec2(0,0),-1))));assertThrows(IllegalArgumentException.class,()->new Influence(id(1),new Vec2(0,0),Double.NaN));assertThrows(IllegalArgumentException.class,()->Influence.normalize(List.of(new Influence(id(1),new Vec2(0,0),1),new Influence(id(1),new Vec2(0,0),2))));
    }
    @Test void invalidBindingHistoryRedoAndZeroInverseRemainAtomic(){
        var h=new CommandHistory(fixture());h.select(Selection.of(id(20)));h.execute("bind",Commands.model(d->WeightEdits.bind(d,id(20),List.of(new WeightEdits.Binding(id(2),1)))));h.undo();var initial=h.state();
        for(var bindings:List.of(List.of(new WeightEdits.Binding(id(2),0)),List.of(new WeightEdits.Binding(id(99),1))))assertThrows(IllegalArgumentException.class,()->h.execute("bad",Commands.model(d->WeightEdits.bind(d,id(20),bindings))));assertEquals(initial,h.state());assertEquals(1,h.redoCount());assertFalse(h.isDirty());
        var zero=ModelEdits.editBone(fixture(),id(2),b->b.withLocal(new Transform(40,0,0,0,1)));assertThrows(IllegalArgumentException.class,()->WeightEdits.bind(zero,id(20),List.of(new WeightEdits.Binding(id(2),1))));
    }
    @Test void deleteInfluenceBoneCascadesAttachmentAndUndoRestoresWholeSkin(){
        var d=WeightEdits.bind(fixture(),id(20),List.of(new WeightEdits.Binding(id(1),1),new WeightEdits.Binding(id(2),1)));var h=new CommandHistory(d);h.select(Selection.of(id(20)));var initial=h.state();h.execute("delete influence",Commands.model(a->ModelEdits.delete(a,id(2))));assertTrue(h.state().model().attachments().isEmpty());assertNull(h.state().model().slots().get(0).activeAttachmentId());h.undo();assertEquals(initial,h.state());h.redo();assertTrue(h.state().model().attachments().isEmpty());
    }
    @Test void topologyPolicyAndWorldDragUnbindAndReparent(){
        var d=WeightEdits.bind(fixture(),id(20),List.of(new WeightEdits.Binding(id(2),1),new WeightEdits.Binding(id(3),3)));var m=MeshEdits.mesh(d,id(20));
        assertThrows(IllegalArgumentException.class,()->MeshEdits.remove(d,id(20),0));var before=d.evaluateWorld().meshes().get(id(20)).vertices().get(0);var moved=WeightEdits.moveWorld(d,id(20),0,new Vec2(before.x()+5,before.y()-3));var after=moved.evaluateWorld().meshes().get(id(20)).vertices().get(0);assertEquals(before.x()+5,after.x(),1e-9);assertEquals(before.y()-3,after.y(),1e-9);assertEquals(m.weights().get(0).get(0).weight(),MeshEdits.mesh(moved,id(20)).weights().get(0).get(0).weight());
        near(d.evaluateWorld().meshes().get(id(20)).vertices(),WeightEdits.unbind(d,id(20)).evaluateWorld().meshes().get(id(20)).vertices());assertFalse(MeshEdits.mesh(WeightEdits.unbind(d,id(20)),id(20)).weighted());
        var reparent=ModelEdits.reparent(d,id(3),id(2),ModelEdits.ReparentPolicy.KEEP_LOCAL);assertEquals(m.weights(),MeshEdits.mesh(reparent,id(20)).weights());assertNotEquals(d.evaluateWorld(),reparent.evaluateWorld());
    }
    @Test void codecOwnAndRuntimePreserveInfluencesAndImages() throws Exception {
        var d=WeightEdits.bind(fixture(),id(20),List.of(new WeightEdits.Binding(id(1),1),new WeightEdits.Binding(id(2),1),new WeightEdits.Binding(id(3),1),new WeightEdits.Binding(id(4),1)));var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));
        var png=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);ImageIO.write(png,"PNG",temp.resolve("image.png").toFile());var bytes=new SkeletonJsonCodec().encode(d,Map.of(id(20),"image"),"");var read=new SkeletonJsonCodec().decode(bytes,temp);near(d.evaluateWorld().meshes().get(id(20)).vertices(),read.evaluateWorld().meshes().values().iterator().next().vertices());var imported=(MeshAttachment)read.attachments().get(0);assertTrue(imported.weighted());
        for(int v=0;v<4;v++)for(int i=0;i<4;i++){var a=MeshEdits.mesh(d,id(20)).weights().get(v).get(i);var b=imported.weights().get(v).get(i);assertEquals(d.bone(a.boneId()).name(),read.bone(b.boneId()).name());assertEquals(a.point(),b.point());assertEquals(a.weight(),b.weight());}
    }
    @Test void zeroScaleAfterBindingIsFiniteButWorldDragRefusesAndFloatLocalCannotHide(){
        var d=WeightEdits.bind(fixture(),id(20),List.of(new WeightEdits.Binding(id(2),1)));var zero=ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(1,2,15,0,0)));assertDoesNotThrow(()->{zero.evaluateWorld();});assertThrows(IllegalArgumentException.class,()->WeightEdits.moveWorld(zero,id(20),0,new Vec2(1,2)));
        var m=MeshEdits.mesh(d,id(20));var w=new ArrayList<>(m.weights());w.set(0,List.of(new Influence(id(2),new Vec2(1e100,1e100),1)));var huge=MeshEdits.replace(d,id(20),a->m.withWeights(w));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(huge));
        var bad=new ArrayList<>(m.weights());bad.set(0,List.of(new Influence(id(99),new Vec2(0,0),1)));assertThrows(IllegalArgumentException.class,()->MeshEdits.replace(d,id(20),a->m.withWeights(bad)));
    }
    @Test void weightedZeroSetupRuntimeImportUsesTopologyCertificateNotWorldInversion()throws Exception{
        var bound=WeightEdits.bind(fixture(),id(20),List.of(new WeightEdits.Binding(id(1),1)));var zero=ModelEdits.editBone(bound,id(1),b->b.withLocal(new Transform(0,0,20,0,0)));
        assertDoesNotThrow(()->RuntimeRange.validate(zero));var png=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);ImageIO.write(png,"PNG",temp.resolve("image.png").toFile());
        var bytes=new SkeletonJsonCodec().encode(zero,Map.of(id(20),"image"),"");var read=new SkeletonJsonCodec().decode(bytes,temp);near(zero.evaluateWorld().meshes().get(id(20)).vertices(),read.evaluateWorld().meshes().values().iterator().next().vertices());
    }
    @Test void graphCertificateContainsInteriorAndRejectsNonManifoldData(){
        var v=List.of(new Vec2(0,0),new Vec2(6,0),new Vec2(6,6),new Vec2(3,2),new Vec2(0,6),new Vec2(1,1));var triangles=MeshTopology.triangulate(v,5);var certificate=MeshTopology.certificate(v.size(),5,triangles);MeshTopology.validate(certificate,5,triangles);assertEquals(v.size(),certificate.size());
        assertThrows(IllegalArgumentException.class,()->MeshTopology.certificate(4,4,List.of(0,1,2,0,1,3)));
    }
}
