package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.viewport.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class MeshTest {
    @TempDir Path temp;
    private static List<Vec2> points(double... xy){List<Vec2> out=new ArrayList<>();for(int i=0;i<xy.length;i+=2)out.add(new Vec2(xy[i],xy[i+1]));return out;}
    private static List<Vec2> concave(){return points(0,0,6,0,6,6,3,2,0,6);}
    private static MeshAttachment mesh(List<Vec2> v,int hull){return new MeshAttachment(id(20),"mesh",id(10),"image.png",v,Collections.nCopies(v.size(),new Vec2(.5,.5)),hull,MeshTopology.triangulate(v,hull));}
    private static DocumentSnapshot document(MeshAttachment m,Transform transform){return new DocumentSnapshot(List.of(bone(1,"root",null,transform)),List.of(new Slot(id(10),"slot",id(1),m.id())),List.of(m));}
    @ParameterizedTest @ValueSource(doubles={1,1e150,1e-150})
    void concaveAndReversedHaveExactCoverAtHugeAndTinyScales(double scale){
        var v=concave().stream().map(p->new Vec2(p.x()*scale,p.y()*scale)).toList();assertEquals(9,MeshTopology.triangulate(v,5).size());
        var reverse=new ArrayList<>(v);Collections.reverse(reverse);assertEquals(9,MeshTopology.triangulate(reverse,5).size());
    }
    @Test void knownConcavityDoesNotFillNotch(){
        var v=concave();var t=MeshTopology.triangulate(v,5);var notch=new Vec2(3,5);
        assertFalse(MeshTopology.contains(v,5,notch,true));for(int i=0;i<t.size();i+=3)assertFalse(MeshTopology.insideTriangle(notch,v.get(t.get(i)),v.get(t.get(i+1)),v.get(t.get(i+2)),true));
        assertTrue(MeshTopology.contains(v,5,new Vec2(6,3),true));assertFalse(MeshTopology.contains(v,5,new Vec2(6,3),false));
    }
    @Test void interiorOnInitialDiagonalSplitsBothTrianglesWithoutTjunction(){
        var v=points(0,0,8,0,8,8,0,8,4,4,3,2);var t=MeshTopology.triangulate(v,4);assertEquals(18,t.size());MeshTopology.validate(v,4,t);
    }
    @Test void rejectsAllInvalidBoundariesAndNonfiniteCoordinates(){
        for(var v:List.of(points(),points(0,0,1,1),points(0,0,1,0,0,0),points(0,0,1,0,2,0,2,2,0,2),points(0,0,3,3,0,3,3,0)))assertThrows(IllegalArgumentException.class,()->MeshTopology.triangulate(v,v.size()));
        assertThrows(IllegalArgumentException.class,()->new Vec2(Double.NaN,0));assertThrows(IllegalArgumentException.class,()->new Vec2(0,Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,()->MeshTopology.triangulate(points(0,0,2,0,0,2,4,4),3));
        assertThrows(IllegalArgumentException.class,()->MeshTopology.triangulate(points(0,0,2,0,0,2,1,0),3));
    }
    @Test void malformedIndicesAndWrongCoverReject(){
        var v=points(0,0,4,0,4,4,0,4);
        for(var t:List.of(List.of(0,1,2),List.of(0,1,99,0,2,3),List.of(0,1,1,0,2,3),List.of(0,2,1,0,2,3),List.of(0,1,2,0,1,3)))assertThrows(IllegalArgumentException.class,()->MeshTopology.validate(v,4,t));
    }
    @Test void seededConcavePolygonsAndInteriorPointsAllValidate(){
        Random random=new Random(61024);for(int n=4;n<=20;n++){
            List<Vec2> v=new ArrayList<>();for(int i=0;i<n;i++){double angle=2*Math.PI*i/n,r=i%2==0?10:5;v.add(new Vec2(Math.cos(angle)*r,Math.sin(angle)*r));}
            v.add(new Vec2(random.nextDouble()-.5,random.nextDouble()-.5));var t=MeshTopology.triangulate(v,n);assertEquals(3*n,t.size());MeshTopology.validate(v,n,t);
        }
    }
    @Test void conversionBakesAttachmentLocalAndPreservesIdWorldUvAndAnimations() throws Exception {
        var before=hierarchy();var d=MeshEdits.convert(before,id(20));var m=MeshEdits.mesh(d,id(20));assertEquals(before.animations(),d.animations());for(int i=0;i<4;i++){var expected=before.evaluateWorld().regions().get(id(20)).vertices().get(i);var actual=d.evaluateWorld().meshes().get(id(20)).vertices().get(i);assertEquals(expected.x(),actual.x(),1e-9+1e-10*Math.abs(expected.x()));assertEquals(expected.y(),actual.y(),1e-9+1e-10*Math.abs(expected.y()));}
        var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));assertEquals(3,new com.fasterxml.jackson.databind.ObjectMapper().readTree(codec.encode(d)).path("schemaVersion").asInt());
        assertThrows(UnsupportedOperationException.class,()->m.vertices().clear());assertThrows(UnsupportedOperationException.class,()->m.triangles().clear());
    }
    @Test void dragOneEntryCancelAndInvalidUpdatePreserveStateRedoDirty(){
        var d=document(mesh(concave(),5),new Transform(3,4,25,-2,.5));var h=new CommandHistory(d);h.select(Selection.of(id(20)));var initial=h.state();
        h.beginPreview("mesh drag");for(int i=0;i<20;i++){final int n=i;h.updatePreview(Commands.model(a->MeshEdits.vertex(a,id(20),0,new Vec2(-n/20.0,-1),new Vec2(.5,.5))));}h.cancelPreview();assertEquals(initial,h.state());assertFalse(h.isDirty());assertEquals(0,h.undoCount());
        h.beginPreview("mesh drag");h.updatePreview(Commands.model(a->MeshEdits.vertex(a,id(20),0,new Vec2(-1,-1),new Vec2(.5,.5))));h.commitPreview();var next=h.state();assertEquals(1,h.undoCount());h.undo();assertEquals(initial,h.state());
        assertThrows(IllegalArgumentException.class,()->h.execute("invalid",Commands.model(a->MeshEdits.vertex(a,id(20),0,new Vec2(6,0),new Vec2(.5,.5)))));assertEquals(initial,h.state());assertEquals(1,h.redoCount());assertFalse(h.isDirty());h.redo();assertEquals(next,h.state());
    }
    @Test void mirroredMoveUsesBoneInverseAndZeroScaleRejectsOnlyWorldOperation(){
        var m=mesh(concave(),5);var d=document(m,new Transform(10,20,0,-2,.5));var next=MeshEdits.moveWorld(d,m.id(),0,new Vec2(12,19.5));assertEquals(new Vec2(-1,-1),MeshEdits.mesh(next,m.id()).vertices().get(0));
        var zero=document(m,new Transform(0,0,0,0,1));assertThrows(IllegalArgumentException.class,()->MeshEdits.moveWorld(zero,m.id(),0,new Vec2(12,19.5)));assertDoesNotThrow(()->MeshEdits.vertex(zero,m.id(),0,new Vec2(-1,-1),new Vec2(.5,.5)));
    }
    @Test void explicitTopologyInsertDeleteAndUvValidation(){
        var d=document(mesh(concave(),5),Transform.IDENTITY);var added=MeshEdits.insert(d,id(20),5,new Vec2(1,1),new Vec2(.2,.3),false);assertEquals(6,MeshEdits.mesh(added,id(20)).vertices().size());assertEquals(d,MeshEdits.remove(added,id(20),5));
        assertThrows(IllegalArgumentException.class,()->MeshEdits.vertex(d,id(20),0,new Vec2(0,0),new Vec2(-.1,1)));assertThrows(IllegalArgumentException.class,()->MeshEdits.geometry(d,id(20),concave(),List.of(),5,List.of()));
    }
    @Test void doubleDomainAndFloatDomainAreSeparateAndAllLocalPointsCertified() throws Exception {
        var huge=document(mesh(concave().stream().map(p->new Vec2(p.x()*1e100,p.y()*1e100)).toList(),5),new Transform(0,0,0,1e-100,1e-100));
        assertDoesNotThrow(()->new ProjectCodec().decode(new ProjectCodec().encode(huge)));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(huge));
        var collapse=document(mesh(points(1,0,1+1e-9,0,1,1),3),Transform.IDENTITY);assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(collapse));
    }
    @Test void actualPngPortableSaveAsProtectsImageAndCodecAndRuntimeRoundtrip() throws Exception {
        Path png=temp.resolve("image.png");var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,0xff00ff00);ImageIO.write(image,"PNG",png.toFile());byte[] original=Files.readAllBytes(png);
        var m=mesh(concave(),5).withSource(png.toString());var d=document(m,Transform.IDENTITY);var doc=new ProjectDocument(d);Path first=temp.resolve("便携.json"),second=temp.resolve("新目录/工程.json");Files.createDirectories(second.getParent());doc.savePortable(first);doc.save(second);assertEquals(d,doc.history().state().model());assertArrayEquals(original,Files.readAllBytes(png));assertThrows(Exception.class,()->doc.save(png));
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(second);assertEquals(d.evaluateWorld(),reopened.history().state().model().evaluateWorld());
        byte[] exported=new SkeletonJsonCodec().encode(d,Map.of(m.id(),"image"),"");var read=new SkeletonJsonCodec().decode(exported,temp);var imported=(MeshAttachment)read.attachments().get(0);assertEquals(m.vertices(),imported.vertices());assertEquals(m.uvs(),imported.uvs());assertEquals(m.triangles(),imported.triangles());
        var assets=new ImageAssets();assertEquals(Optional.of(m.id()),Picker.region(d,new Vec2(1,1),assets));assertTrue(Picker.region(d,new Vec2(3,5),assets).isEmpty());
    }
    @Test void barycentricUvHugeTinyMirroredAndDegenerate(){
        for(double s:new double[]{1,1e200,1e-200,-1}){
            var uv=Picker.triangleUv(new Vec2(.25*s,.25*s),new Vec2(0,0),new Vec2(s,0),new Vec2(0,s),new Vec2(0,0),new Vec2(1,0),new Vec2(0,1)).orElseThrow();assertEquals(.25,uv.x(),1e-15);assertEquals(.25,uv.y(),1e-15);
        }
        assertTrue(Picker.triangleUv(new Vec2(0,0),new Vec2(0,0),new Vec2(0,0),new Vec2(0,1),new Vec2(0,0),new Vec2(1,0),new Vec2(0,1)).isEmpty());
    }
}
