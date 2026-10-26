package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.viewport.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ClippingTest {
    @TempDir Path temp;
    static List<Vec2> boundary(){return List.of(new Vec2(0,0),new Vec2(4,0),new Vec2(4,2),new Vec2(2,2),new Vec2(2,4),new Vec2(0,4));}
    static PolygonClipper.Geometry square(double scale){var v=List.of(new Vec2(-scale,-scale),new Vec2(5*scale,-scale),new Vec2(5*scale,5*scale),new Vec2(-scale,5*scale));return new PolygonClipper.Geometry(v,List.of(new Vec2(0,1),new Vec2(1,1),new Vec2(1,0),new Vec2(0,0)),List.of(0,1,2,2,3,0));}
    static double area(PolygonClipper.Geometry g){double area=0;for(int i=0;i<g.triangles().size();i+=3)area+=MeshTopology.cross(g.vertices().get(g.triangles().get(i)),g.vertices().get(g.triangles().get(i+1)),g.vertices().get(g.triangles().get(i+2))).abs().doubleValue()/2;return area;}
    @ParameterizedTest @ValueSource(doubles={1,1e150,1e-150,-1}) void concaveIntersectionPreservesAreaAndUvAtExtremeFiniteScales(double scale){
        var clip=boundary().stream().map(p->new Vec2(p.x()*scale,p.y()*scale)).toList();var g=PolygonClipper.clip(square(scale),clip,MeshTopology.triangulate(boundary(),6));assertEquals(12*scale*scale,area(g),Math.abs(12*scale*scale)*1e-12+1e-310);
        for(int i=0;i<g.vertices().size();i++){var p=g.vertices().get(i);assertTrue(MeshTopology.contains(clip,clip.size(),p,true),"point="+p+" scale="+scale);assertEquals((p.x()/scale+1)/6,g.uvs().get(i).x(),1e-12);assertEquals((5-p.y()/scale)/6,g.uvs().get(i).y(),1e-12);}
    }
    @Test void zeroWorldPolygonIsEmptyAndBoundaryPointsRemainIncluded(){
        var c=List.of(new Vec2(0,0),new Vec2(2,0),new Vec2(2,2),new Vec2(0,2));var tris=MeshTopology.triangulate(c,4);var g=PolygonClipper.clip(square(1),c,tris);assertEquals(4,area(g),1e-12);assertTrue(g.vertices().contains(new Vec2(0,0)));assertTrue(PolygonClipper.clip(square(1),Collections.nCopies(4,new Vec2(0,0)),tris).triangles().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new PolygonClipper.Geometry(List.of(new Vec2(0,0)),List.of(),List.of()));assertThrows(IllegalArgumentException.class,()->PolygonClipper.clip(square(1),c,List.of(99,0,1)));
    }
    static DocumentSnapshot fixture(String image){
        var root=bone(1,"root",null,Transform.IDENTITY);var clip=new ClippingAttachment(id(20),"clip",id(11),boundary(),id(13));List<Slot> slots=List.of(new Slot(id(10),"before",id(1),id(21)),new Slot(id(11),"clip-slot",id(1),id(20)),new Slot(id(12),"inside-a",id(1),id(22)),new Slot(id(13),"end-inclusive",id(1),id(23)),new Slot(id(14),"after",id(1),id(24)));
        List<Attachment> attachments=new ArrayList<>();attachments.add(clip);for(int i=0;i<4;i++){int slot=new int[]{10,12,13,14}[i];attachments.add(new RegionAttachment(id(21+i),"image-"+i,id(slot),image,new Transform(2,2,0,1,1),6,6));}return new DocumentSnapshot(List.of(root),slots,attachments);
    }
    @Test void endSlotInclusiveNextSlotAndNextEvaluationAreUnclipped(){
        var d=fixture("image.png");var first=VisibleGeometry.evaluate(d,d.evaluateWorld());assertEquals(4,first.size());assertEquals(36,area(first.get(0).geometry()));assertEquals(12,area(first.get(1).geometry()),1e-12);assertEquals(12,area(first.get(2).geometry()),1e-12);assertEquals(36,area(first.get(3).geometry()));assertEquals(first,VisibleGeometry.evaluate(d,d.evaluateWorld()));
        var emptyEnd=ModelEdits.editSlot(d,id(13),s->s.withActiveAttachment(null));var draws=VisibleGeometry.evaluate(emptyEnd,emptyEnd.evaluateWorld());assertEquals(36,area(draws.get(draws.size()-1).geometry()));
        var tail=ClippingEdits.edit(d,id(20),c->new ClippingAttachment(c.id(),c.name(),c.slotId(),c.vertices(),null));assertEquals(12,area(VisibleGeometry.evaluate(tail,tail.evaluateWorld()).get(3).geometry()),1e-12);
    }
    @Test void invalidPolygonsAndEndReferencesRejectAtomically(){
        for(var v:List.of(List.<Vec2>of(),List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(1,0)),List.of(new Vec2(0,0),new Vec2(1,1),new Vec2(0,1),new Vec2(1,0))))assertThrows(IllegalArgumentException.class,()->new ClippingAttachment(id(20),"clip",id(11),v,null));
        var d=fixture("image.png");for(var end:List.of(id(10),id(11),id(99)))assertThrows(IllegalArgumentException.class,()->ClippingEdits.edit(d,id(20),c->new ClippingAttachment(c.id(),c.name(),c.slotId(),c.vertices(),end)));
        var h=new CommandHistory(d);h.select(Selection.of(id(20)));h.execute("vertex",Commands.model(a->ClippingEdits.vertex(a,id(20),0,new Vec2(-1,-1))));h.undo();var before=h.state();assertThrows(IllegalArgumentException.class,()->h.execute("bad",Commands.model(a->ClippingEdits.vertex(a,id(20),0,new Vec2(4,0)))));assertEquals(before,h.state());assertEquals(1,h.redoCount());assertFalse(h.isDirty());
    }
    @Test void dragMirrorsCancelsAndDeleteEndSlotUndoRestoresAllReferences(){
        var d=ModelEdits.editBone(fixture("image.png"),id(1),b->b.withLocal(new Transform(10,20,0,-2,.5)));var h=new CommandHistory(d);h.select(Selection.of(id(20)));var before=h.state();h.beginPreview("clip drag");h.updatePreview(Commands.model(a->ClippingEdits.moveWorld(a,id(20),0,new Vec2(12,19.5))));h.cancelPreview();assertEquals(before,h.state());h.beginPreview("clip drag");h.updatePreview(Commands.model(a->ClippingEdits.moveWorld(a,id(20),0,new Vec2(12,19.5))));h.commitPreview();assertEquals(1,h.undoCount());assertEquals(new Vec2(-1,-1),ClippingEdits.clip(h.state().model(),id(20)).vertices().get(0));h.undo();h.execute("delete end",Commands.model(a->ModelEdits.delete(a,id(13))));assertFalse(h.state().model().contains(id(20)));assertNull(h.state().model().slots().stream().filter(s->s.id().equals(id(11))).findFirst().orElseThrow().activeAttachmentId());h.undo();assertEquals(before,h.state());
    }
    @Test void ownPortableAndRuntimeJsonRoundtripAndUnknownClipFields()throws Exception{
        var png=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);Path source=temp.resolve("image.png");ImageIO.write(png,"PNG",source.toFile());var d=fixture(source.toString());var codec=new ProjectCodec();assertEquals(d,codec.decode(codec.encode(d)));var doc=new ProjectDocument(d);Path saved=temp.resolve("便携.json");doc.savePortable(saved);var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(saved);assertEquals(d.evaluateWorld(),reopened.history().state().model().evaluateWorld());
        Map<EntityId,String> paths=new HashMap<>();d.attachments().stream().filter(a->a.source()!=null).forEach(a->paths.put(a.id(),"image"));byte[] bytes=new SkeletonJsonCodec().encode(d,paths,"");var read=new SkeletonJsonCodec().decode(bytes,temp);assertEquals(VisibleGeometry.evaluate(d,d.evaluateWorld()).stream().map(v->area(v.geometry())).toList(),VisibleGeometry.evaluate(read,read.evaluateWorld()).stream().map(v->area(v.geometry())).toList());
        var tree=new ObjectMapper().readTree(bytes);var node=(ObjectNode)tree.path("skins").get(0).path("attachments").path("clip-slot").path("clip");node.put("inverse",true).put("convex",true);String error=assertThrows(ProjectFormatException.class,()->new SkeletonJsonCodec().decode(new ObjectMapper().writeValueAsBytes(tree),temp)).getMessage();assertTrue(error.contains("inverse")&&error.contains("convex"));
    }
    @Test void pickingUsesClippedSameGeometryAndTextureAlpha()throws Exception{
        var pixels=new BufferedImage(12,12,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<12;y++)for(int x=0;x<12;x++)pixels.setRGB(x,y,0xff00ff00);Path png=temp.resolve("opaque.png");ImageIO.write(pixels,"PNG",png.toFile());var d=fixture(png.toString());d=ModelEdits.editSlot(d,id(14),s->s.withActiveAttachment(null));var assets=new ImageAssets();assertEquals(Optional.of(id(23)),Picker.region(d,new Vec2(1,3),assets));assertEquals(Optional.of(id(21)),Picker.region(d,new Vec2(3,3),assets));
        var zero=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,0,1)));assertDoesNotThrow(()->VisibleGeometry.evaluate(zero,zero.evaluateWorld()));assertThrows(IllegalArgumentException.class,()->ClippingEdits.moveWorld(zero,id(20),0,new Vec2(1,2)));
    }
    @Test void floatLocalRangeCannotBeHiddenByTinyAncestorOrZeroBone(){
        var d=fixture("image.png");var small=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,1e-100,1e-100)));var c=ClippingEdits.clip(small,id(20));var huge=ClippingEdits.edit(small,id(20),a->a.withVertices(c.vertices().stream().map(p->new Vec2(p.x()*1e100,p.y()*1e100)).toList()));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(huge));
        var zero=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,0,0)));assertDoesNotThrow(()->RuntimeRange.validate(zero));assertTrue(VisibleGeometry.evaluate(zero,zero.evaluateWorld()).get(1).geometry().triangles().isEmpty());
        var hugeZero=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(1e30,1e30,0,0,0)));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(hugeZero));
    }
    @Test void clippingCertificateRejectsFiniteVerticesWithUnsafeProductsOrHiddenZeroArea(){
        var d=fixture("image.png");var huge=ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,1e30,1e30)));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(huge));
        var subject=ModelEdits.editRegion(d,id(22),r->new RegionAttachment(r.id(),r.name(),r.slotId(),r.source(),new Transform(2,2,0,0,1),r.width(),r.height()));assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(subject));
        var b=bone(2,"source",id(1),Transform.IDENTITY);var bones=new ArrayList<>(d.bones());bones.add(b);var slots=d.slots().stream().map(s->s.id().equals(id(12))?new Slot(s.id(),s.name(),b.id(),s.activeAttachmentId()):s).toList();var animated=new DocumentSnapshot(bones,slots,d.attachments());var clip=animated.animations().get(0).id();animated=AnimationEdits.put(animated,clip,b.id(),Channel.SCALE,0,List.of(1.0,1.0));animated=AnimationEdits.put(animated,clip,b.id(),Channel.SCALE,.6,List.of(-1.0,1.0));animated=AnimationEdits.put(animated,clip,b.id(),Channel.SCALE,1,List.of(1.0,1.0));var hidden=animated;
        for(double time:new double[]{0,.5,1})assertTrue(hidden.evaluateWorld(AnimationSampler.pose(hidden,clip,time,false)).regions().values().stream().flatMap(r->r.vertices().stream()).allMatch(p->Double.isFinite(p.x())&&Double.isFinite(p.y())));
        var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(hidden));assertTrue(error.getMessage().contains("whole animation"));assertDoesNotThrow(()->new ProjectCodec().encode(hidden));
    }
    @Test void clippingCertificateCoversWholeSmallMotionAndAllCurveControlHulls(){
        var d=fixture("image.png");var id=d.animations().get(0).id();d=AnimationEdits.put(d,id,io.github.samlinb.openbone.TestData.id(1),Channel.ROTATE,0,List.of(0.0));d=AnimationEdits.put(d,id,io.github.samlinb.openbone.TestData.id(1),Channel.ROTATE,1,List.of(5.0));var moving=d;assertDoesNotThrow(()->RuntimeRange.validate(moving));
        var keys=d.animation(id).timelines().get(0).keys();d=AnimationEdits.curves(d,id,Set.of(keys.get(0).id()),List.of(new Curve(Curve.Kind.BEZIER,.2,-.1,.8,1.1)));var curved=d;assertDoesNotThrow(()->RuntimeRange.validate(curved));
        var mirrored=ModelEdits.editBone(d,io.github.samlinb.openbone.TestData.id(1),b->b.withLocal(new Transform(0,0,0,-1,.5)));assertDoesNotThrow(()->RuntimeRange.validate(mirrored));
    }
    @Test void legalBoundaryUvSamplesLastTexelWithAndWithoutClipping()throws Exception{
        var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,x==7&&y==0?0xffef50a0:0);Path png=temp.resolve("edge.png");ImageIO.write(image,"PNG",png.toFile());
        var b=bone(1,"root",null,Transform.IDENTITY);var s=new Slot(id(10),"mesh",b.id(),id(20));var v=List.of(new Vec2(-5,-5),new Vec2(5,-5),new Vec2(5,5),new Vec2(-5,5));var m=new MeshAttachment(id(20),"edge",s.id(),png.toString(),v,Collections.nCopies(4,new Vec2(1,0)),4,List.of(0,1,2,2,3,0));var plain=new DocumentSnapshot(List.of(b),List.of(s),List.of(m));assertEquals(Optional.of(m.id()),Picker.region(plain,new Vec2(1,2),new ImageAssets()));
        var cs=new Slot(id(11),"clip",b.id(),id(21));var c=new ClippingAttachment(id(21),"clip",cs.id(),v,s.id());var clipped=new DocumentSnapshot(List.of(b),List.of(cs,s),List.of(c,m));assertEquals(Optional.of(m.id()),Picker.region(clipped,new Vec2(1,2),new ImageAssets()));
        var transparent=MeshEdits.replace(clipped,m.id(),a->new MeshAttachment(m.id(),m.name(),m.slotId(),m.source(),m.vertices(),Collections.nCopies(4,new Vec2(1,1)),m.hull(),m.triangles()));assertTrue(Picker.region(transparent,new Vec2(1,2),new ImageAssets()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new MeshAttachment(m.id(),m.name(),m.slotId(),m.source(),m.vertices(),Collections.nCopies(4,new Vec2(1.2,0)),m.hull(),m.triangles()));
    }
    @Test void floatClipVertexBudgetFailsClosedWithoutRestrictingOwnProjectTopology()throws Exception{
        var d=fixture("image.png");List<Vec2> ring=new ArrayList<>();for(int i=0;i<12;i++)ring.add(new Vec2(2+10*Math.cos(i*Math.PI/6),2+10*Math.sin(i*Math.PI/6)));var many=ClippingEdits.edit(d,id(20),c->c.withVertices(ring));assertDoesNotThrow(()->new ProjectCodec().encode(many));var error=assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(many));assertTrue(error.getMessage().contains("uint16 vertex budget"));
    }
}
