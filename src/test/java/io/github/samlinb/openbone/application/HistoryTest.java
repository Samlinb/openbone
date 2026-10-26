package io.github.samlinb.openbone.application;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryTest {
    private static EditCommand transform(Transform t) { return Commands.model(d -> ModelEdits.editBone(d,id(1),b -> b.withLocal(t))); }
    private static void unchanged(CommandHistory h,EditorState state,int undo,int redo,boolean dirty) {
        assertEquals(state,h.state()); assertEquals(undo,h.undoCount()); assertEquals(redo,h.redoCount()); assertEquals(dirty,h.isDirty());
    }
    @ParameterizedTest @ValueSource(strings={"create","move","rotate","scale"})
    void eachCommandClass100UndoAndRedoEqualsEverySnapshot(String kind) {
        var h=new CommandHistory(kind.equals("create")?DocumentSnapshot.EMPTY:hierarchy());
        if(!kind.equals("create")) h.select(Selection.of(id(3)));
        List<EditorState> expected=new ArrayList<>(); expected.add(h.state());
        for(int i=1;i<=100;i++) {
            final int index=i;
            if(kind.equals("create")) h.execute("create "+i,state -> {
                var model=ModelEdits.addBone(state.model(),bone(index,"b"+index,index==1?null:id(index-1),new Transform(1,2,3,1,1)));
                return new EditorState(model,Selection.of(id(index)));
            });
            else {
                Transform before=h.state().model().bone(id(1)).local();
                Transform next=switch(kind) {
                    case "move" -> new Transform(i*1.25,-i*.5,before.rotation(),before.scaleX(),before.scaleY());
                    case "rotate" -> new Transform(before.x(),before.y(),i*15.5,before.scaleX(),before.scaleY());
                    default -> new Transform(before.x(),before.y(),before.rotation(),i%2==0?-i/10.0:i/10.0,i/20.0);
                };
                h.execute(kind,transform(next));
            }
            expected.add(h.state()); assertTrue(h.isDirty()); assertEquals(i,h.undoCount());
        }
        for(int i=99;i>=0;i--) {
            assertTrue(h.undo()); assertEquals(expected.get(i),h.state()); assertEquals(i!=0,h.isDirty());
            assertEquals(i,h.undoCount()); assertEquals(100-i,h.redoCount());
        }
        assertFalse(h.undo());
        for(int i=1;i<=100;i++) { assertTrue(h.redo()); assertEquals(expected.get(i),h.state()); assertTrue(h.isDirty()); }
        assertFalse(h.redo());
    }
    @Test void sharedSelectionApiValidatesBothEntrypointsWithoutHistoryOrDirty() {
        var h=new CommandHistory(hierarchy());
        h.select(Selection.of(id(3))); assertEquals(id(3),h.state().selection().primary().orElseThrow());
        h.select(Selection.of(id(2))); assertEquals(id(2),h.state().selection().primary().orElseThrow());
        assertFalse(h.isDirty()); assertEquals(0,h.undoCount());
        h.select(new Selection(List.of(id(1),id(10),id(20)))); assertEquals(id(20),h.state().selection().primary().orElseThrow());
        EditorState before=h.state();
        assertThrows(IllegalArgumentException.class,()->h.select(Selection.of(id(99)))); assertEquals(before,h.state());
        assertThrows(IllegalArgumentException.class,()->new Selection(List.of(id(1),id(1))));
        assertThrows(UnsupportedOperationException.class,()->h.state().selection().ids().clear());
    }
    @Test void deleteReferencesSelectionAndUndoAreOneTransaction() {
        var h=new CommandHistory(hierarchy()); h.select(new Selection(List.of(id(3),id(10),id(20),id(4)))); EditorState before=h.state();
        h.execute("delete subtree",Commands.model(d->ModelEdits.delete(d,id(2))));
        assertEquals(Selection.of(id(4)),h.state().selection()); assertEquals(1,h.undoCount()); assertTrue(h.isDirty());
        EditorState deleted=h.state(); assertTrue(h.undo()); unchanged(h,before,0,1,false);
        assertTrue(h.redo()); unchanged(h,deleted,1,0,true);
        for(EntityId target:List.of(id(10),id(20),id(21))) {
            var history=new CommandHistory(hierarchy()); history.select(Selection.of(target)); var initial=history.state();
            history.execute("delete",Commands.model(d->ModelEdits.delete(d,target))); assertEquals(Selection.EMPTY,history.state().selection());
            assertTrue(history.undo()); assertEquals(initial,history.state());
        }
    }
    @Test void invalidEditsLeaveModelHistorySelectionDirtyAndRedoUntouched() {
        var h=new CommandHistory(hierarchy()); h.select(Selection.of(id(3)));
        h.execute("move",transform(new Transform(10,20,30,1,1))); h.undo();
        EditorState initial=h.state();
        List<EditCommand> invalid=List.of(
                Commands.model(d->ModelEdits.reparent(d,id(1),id(3),ModelEdits.ReparentPolicy.KEEP_LOCAL)),
                Commands.model(d->ModelEdits.reparent(d,id(1),id(1),ModelEdits.ReparentPolicy.KEEP_LOCAL)),
                Commands.model(d->ModelEdits.reparent(d,id(1),id(99),ModelEdits.ReparentPolicy.KEEP_LOCAL)),
                Commands.model(d->ModelEdits.addBone(d,bone(99,"根骨",null,Transform.IDENTITY))),
                Commands.model(d->ModelEdits.editBone(d,id(1),b->b.withLocal(new Transform(0,0,0,Double.NaN,1)))),
                Commands.transaction(List.of(Commands.model(d->ModelEdits.delete(d,id(2))),Commands.model(d->ModelEdits.delete(d,id(99))))));
        for(EditCommand command:invalid) {
            assertThrows(IllegalArgumentException.class,()->h.execute("invalid",command)); unchanged(h,initial,0,1,false);
        }
        assertThrows(NullPointerException.class,()->h.execute("null",state->null)); unchanged(h,initial,0,1,false);
        assertThrows(IllegalStateException.class,()->h.execute("reentrant",state->{h.markSaved();return state;})); unchanged(h,initial,0,1,false);
    }
    @Test void compositeIsOneEntryAndNewCommandClearsRedoOnlyWhenContentChanges() {
        var h=new CommandHistory(hierarchy()); EditorState initial=h.state();
        h.execute("multi",Commands.transaction(List.of(Commands.model(d->ModelEdits.delete(d,id(20))),transform(Transform.IDENTITY))));
        assertEquals(1,h.undoCount()); h.undo(); assertEquals(initial,h.state());
        h.execute("noop",state->state); assertEquals(1,h.redoCount());
        h.select(Selection.of(id(4))); assertEquals(1,h.redoCount());
        h.execute("new branch",Commands.model(d->ModelEdits.editBone(d,id(1),b->b.withName("branch"))));
        assertEquals(0,h.redoCount()); assertFalse(h.redo());
    }
    @Test void savedContentTracksUndoRedoAndEquivalentBranchNotCursorPosition() {
        var h=new CommandHistory(hierarchy());
        h.execute("A",transform(new Transform(1,2,3,1,1))); h.markSaved(); var saved=h.state().model(); assertFalse(h.isDirty());
        h.execute("B",transform(new Transform(2,2,3,1,1))); assertTrue(h.isDirty());
        h.undo(); assertFalse(h.isDirty()); h.undo(); assertTrue(h.isDirty()); h.redo(); assertFalse(h.isDirty()); h.redo(); assertTrue(h.isDirty());
        h.execute("C equivalent to saved",Commands.model(d->saved)); assertFalse(h.isDirty());
        h.undo(); assertTrue(h.isDirty()); h.redo(); assertFalse(h.isDirty());
        h.select(Selection.of(id(3))); assertFalse(h.isDirty());
        h.reset(DocumentSnapshot.EMPTY); unchanged(h,EditorState.of(DocumentSnapshot.EMPTY),0,0,false);
    }
    @Test void twentyPreviewUpdatesCommitOnceAndCancelRestoresEverything() {
        var h=new CommandHistory(hierarchy()); h.select(Selection.of(id(3))); EditorState initial=h.state();
        h.beginPreview("drag");
        for(int i=1;i<=20;i++) { h.updatePreview(transform(new Transform(i,i*2,0,1,1))); assertEquals(i,h.state().model().bone(id(1)).local().x()); assertEquals(0,h.undoCount()); }
        var preview=h.state(); assertTrue(h.isDirty());
        assertThrows(IllegalArgumentException.class,()->h.updatePreview(transform(new Transform(0,0,0,Double.MAX_VALUE,Double.MAX_VALUE)))); assertEquals(preview,h.state());
        assertThrows(IllegalStateException.class,h::undo); assertThrows(IllegalStateException.class,h::redo);
        assertThrows(IllegalStateException.class,h::markSaved); assertThrows(IllegalStateException.class,()->h.select(Selection.EMPTY));
        assertThrows(IllegalStateException.class,()->h.execute("nested",state->state)); assertThrows(IllegalStateException.class,()->h.beginPreview("nested"));
        h.commitPreview(); unchanged(h,preview,1,0,true); assertEquals("drag",h.undoLabel().orElseThrow());
        h.undo(); unchanged(h,initial,0,1,false);
        h.beginPreview("cancel"); for(int i=1;i<=20;i++) h.updatePreview(transform(new Transform(i,0,0,-1,0)));
        h.cancelPreview(); unchanged(h,initial,0,1,false);
        h.redo(); h.markSaved(); var saved=h.state();
        h.beginPreview("cancel after save"); h.updatePreview(transform(Transform.IDENTITY)); h.cancelPreview(); unchanged(h,saved,1,0,false);
    }
    @Test void previewNoopAndUnbalancedLifecycle() {
        var h=new CommandHistory(hierarchy());
        assertThrows(IllegalStateException.class,h::commitPreview); assertThrows(IllegalStateException.class,h::cancelPreview);
        assertThrows(IllegalStateException.class,()->h.updatePreview(state->state));
        h.beginPreview("no change"); h.commitPreview(); assertEquals(0,h.undoCount()); assertFalse(h.isDirty());
        h.beginPreview("roundtrip"); h.updatePreview(transform(Transform.IDENTITY)); h.updatePreview(state->state); h.commitPreview();
        assertEquals(0,h.undoCount()); assertFalse(h.isDirty());
        assertThrows(IllegalArgumentException.class,()->h.execute(" ",state->state));
    }
    @ParameterizedTest @ValueSource(doubles={0,1e-14,-1e-14})
    void singularWorldDragAndReparentRefuseWithoutPublishingAnyPreviewOrHistory(double scale) {
        var singular=ModelEdits.editBone(hierarchy(),id(1),b->b.withLocal(new Transform(0,0,30,scale,1)));
        var h=new CommandHistory(singular); h.select(Selection.of(id(2))); var before=h.state();
        EditCommand drag=Commands.model(d->ModelEdits.moveBoneWorld(d,id(2),new Vec2(10,20)));
        assertThrows(IllegalArgumentException.class,()->h.execute("world drag",drag)); unchanged(h,before,0,0,false);
        h.beginPreview("world drag"); assertThrows(IllegalArgumentException.class,()->h.updatePreview(drag));
        h.cancelPreview(); unchanged(h,before,0,0,false);
        assertThrows(IllegalArgumentException.class,()->h.execute("reparent",Commands.model(d->ModelEdits.reparent(d,id(4),id(1),ModelEdits.ReparentPolicy.KEEP_WORLD))));
        unchanged(h,before,0,0,false);
        h.execute("local move",Commands.model(d->ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(5,6,0,1,1)))));
        assertTrue(h.isDirty()); h.undo(); unchanged(h,before,0,1,false);
    }
    @Test void worldTranslationRetainsRotationAndScaleAndIsUndoableUnderMirroredShearedParent() {
        var h=new CommandHistory(hierarchy()); var before=h.state(); var t=before.model().bone(id(3)).local();
        h.execute("world move",Commands.model(d->ModelEdits.moveBoneWorld(d,id(3),new Vec2(125,-42))));
        var world=h.state().model().evaluateWorld().bones().get(id(3)).origin();
        assertEquals(125,world.x(),1e-9); assertEquals(-42,world.y(),1e-9);
        var after=h.state().model().bone(id(3)).local(); assertEquals(t.rotation(),after.rotation()); assertEquals(t.scaleX(),after.scaleX()); assertEquals(t.scaleY(),after.scaleY());
        h.undo(); assertEquals(before,h.state());
    }
}
