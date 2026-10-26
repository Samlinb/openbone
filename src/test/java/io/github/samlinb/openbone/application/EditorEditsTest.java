package io.github.samlinb.openbone.application;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.application.EditorEdits.*;

class EditorEditsTest {
    @TempDir Path temp;
    private final Bone root=new Bone(EntityId.create(),"root",null,new Transform(5,10,30,-2,.5),80);
    private final Bone child=new Bone(EntityId.create(),"child",root.id(),new Transform(20,4,0,1,1),50);
    private DocumentSnapshot scene(){return new DocumentSnapshot(List.of(root,child),List.of(),List.of());}
    @Test void createBoneUnderMirroredParentMatchesMouseWorldEndpoints(){
        Vec2 start=new Vec2(30,50),end=new Vec2(-20,80);var d=scene();var bone=EditorEdits.create(d,child.id(),start,end,"tip");var w=ModelEdits.addBone(d,bone).evaluateWorld().bones().get(bone.id());
        assertEquals(start.x(),w.origin().x(),1e-10);assertEquals(start.y(),w.origin().y(),1e-10);assertEquals(end.x(),w.tip().x(),1e-10);assertEquals(end.y(),w.tip().y(),1e-10);
    }
    @Test void numericAndPreviewMoveRotateScaleShareResultsAndOneHistoryItem(){
        var h=new CommandHistory(scene());h.select(Selection.of(child.id()));var start=h.state();
        h.beginPreview("move");for(int i=1;i<=20;i++){double v=i;h.updatePreview(Commands.model(d->EditorEdits.move(d,child.id(),new Vec2(v,v*2),Space.LOCAL)));}h.commitPreview();assertEquals(1,h.undoCount());
        var numeric=EditorEdits.transform(scene(),child.id(),new Transform(40,44,0,1,1),Space.LOCAL);assertEquals(numeric,h.state().model());h.undo();assertEquals(start,h.state());assertFalse(h.isDirty());h.redo();
        h.beginPreview("rotate");h.updatePreview(Commands.model(d->EditorEdits.rotate(d,child.id(),45,Space.LOCAL)));h.cancelPreview();assertEquals(numeric,h.state().model());assertEquals(1,h.undoCount());
        var r=EditorEdits.rotate(scene(),child.id(),45,Space.LOCAL);assertEquals(EditorEdits.transform(scene(),child.id(),new Transform(20,4,45,1,1),Space.LOCAL),r);
        var s=EditorEdits.scale(scene(),child.id(),-1,.5,Space.LOCAL);assertEquals(EditorEdits.transform(scene(),child.id(),new Transform(20,4,0,-1,.5),Space.LOCAL),s);
    }
    @Test void worldMoveRetainsFullLinearBlockAndSingularIsAtomic(){
        var d=scene();var before=EditorEdits.world(d,child.id());var result=EditorEdits.move(d,child.id(),new Vec2(10,20),Space.WORLD);var after=EditorEdits.world(result,child.id());
        assertEquals(before.tx()+10,after.tx(),1e-10);assertEquals(before.ty()+20,after.ty(),1e-10);assertEquals(before.a(),after.a());assertEquals(before.b(),after.b());
        var zero=ModelEdits.editBone(d,root.id(),b->b.withLocal(new Transform(0,0,0,0,1)));var h=new CommandHistory(zero);h.beginPreview("move");assertThrows(IllegalArgumentException.class,()->h.updatePreview(Commands.model(x->EditorEdits.move(x,child.id(),new Vec2(1,1),Space.WORLD))));h.cancelPreview();assertEquals(zero,h.state().model());assertEquals(0,h.undoCount());
        assertDoesNotThrow(()->EditorEdits.move(zero,child.id(),new Vec2(1,1),Space.LOCAL));
    }
    @Test void worldRotateScalePreserveShearWithoutApproximation(){
        var original=EditorEdits.world(scene(),child.id());
        var rotated=EditorEdits.world(EditorEdits.rotate(scene(),child.id(),45,Space.WORLD),child.id());
        var expected=new Transform(0,0,45,1,1).matrix().multiply(new Affine2(original.a(),original.b(),original.c(),original.d(),0,0));
        assertEquals(expected.a(),rotated.a(),1e-10); assertEquals(expected.b(),rotated.b(),1e-10);
        assertEquals(expected.c(),rotated.c(),1e-10); assertEquals(expected.d(),rotated.d(),1e-10);
        var scaled=EditorEdits.world(EditorEdits.scale(scene(),child.id(),2,1,Space.WORLD),child.id());
        assertEquals(original.a()*2,scaled.a(),1e-10); assertEquals(original.b()*2,scaled.b(),1e-10);
        assertEquals(original.c(),scaled.c(),1e-10); assertEquals(original.d(),scaled.d(),1e-10);
    }
    @Test void slotSelectionTransformsBoundBoneAndRegionEditsStayLocal(){
        var s=new Slot(EntityId.create(),"slot",child.id(),null);var r=new RegionAttachment(EntityId.create(),"image",s.id(),"missing.png",Transform.IDENTITY,4,4);
        var d=ModelEdits.addAttachment(ModelEdits.addSlot(scene(),s),r,true);
        assertEquals(EditorEdits.move(d,child.id(),new Vec2(10,20),Space.LOCAL),EditorEdits.move(d,s.id(),new Vec2(10,20),Space.LOCAL));
        var moved=EditorEdits.move(d,r.id(),new Vec2(10,20),Space.LOCAL);assertEquals(d.bones(),moved.bones());assertEquals(10,EditorEdits.local(moved,r.id()).x());
    }
    @Test void preparedOpenPublishesOnlyAfterAdapterSuccessAndKeepsCliCompatibility() throws Exception {
        var file=temp.resolve("项目.json");Files.write(file,new io.github.samlinb.openbone.io.ProjectCodec().encode(scene()));
        var d=new ProjectDocument(DocumentSnapshot.EMPTY);d.history().execute("create",Commands.model(x->scene()));var before=d.history().state();
        assertThrows(IllegalArgumentException.class,()->d.open(file,x->{throw new IllegalArgumentException("bad resource path");}));assertEquals(before,d.history().state());assertTrue(d.path().isEmpty());assertEquals(1,d.history().undoCount());
        d.open(file,x->x);assertEquals(scene(),d.history().state().model());assertFalse(d.history().isDirty());assertEquals(0,d.history().undoCount());
    }
}
