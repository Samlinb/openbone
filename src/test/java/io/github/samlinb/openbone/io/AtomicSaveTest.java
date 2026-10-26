package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class AtomicSaveTest {
    @TempDir Path temporary;
    private static void replace(Path source,Path target) throws IOException {
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static EditCommand edit() { return Commands.model(d->ModelEdits.editBone(d,id(1),b->b.withLocal(Transform.IDENTITY))); }
    private long leftoverTemps() throws IOException {
        try(var list=Files.list(temporary)) { return list.filter(p->p.getFileName().toString().endsWith(".tmp")).count(); }
    }
    @ParameterizedTest @EnumSource(AtomicProjectFile.Stage.class)
    void eachWriteFailureLeavesOldFileDocumentPathSelectionRedoAndSavePointUntouched(AtomicProjectFile.Stage stage) throws Exception {
        Path target=temporary.resolve("旧 工程.json"), savedPath=temporary.resolve("当前.json");
        byte[] old=new ProjectCodec().encode(DocumentSnapshot.EMPTY); Files.write(target,old);
        final boolean[] fail={false};
        var writer=new AtomicProjectFile((at,temp)-> {
            if(fail[0] && at==stage) {
                if(stage==AtomicProjectFile.Stage.AFTER_CREATE) Files.write(temp,new byte[]{'{','"','x'}); // real partial file before injected disk failure
                throw new IOException("Injected "+at);
            }
        },AtomicSaveTest::replace);
        var document=new ProjectDocument(hierarchy(),new ProjectCodec(),writer);
        document.save(savedPath); document.history().select(Selection.of(id(3)));
        document.history().execute("A",edit()); document.history().execute("B",Commands.model(d->ModelEdits.delete(d,id(20)))); document.history().undo();
        var before=document.history().state(); assertTrue(document.history().isDirty()); fail[0]=true;
        assertThrows(IOException.class,()->document.save(target));
        assertArrayEquals(old,Files.readAllBytes(target)); assertEquals(before,document.history().state());
        assertTrue(document.history().isDirty()); assertEquals(1,document.history().undoCount()); assertEquals(1,document.history().redoCount());
        assertEquals(savedPath.toAbsolutePath(),document.path().orElseThrow()); assertEquals(0,leftoverTemps());
        document.history().undo(); assertFalse(document.history().isDirty());
    }
    @Test void unsupportedAtomicMoveAndAccessDeniedNeverFallBack() throws Exception {
        for(IOException failure:List.of(new AtomicMoveNotSupportedException("source","target","injected"),new AccessDeniedException("injected"))) {
            Path target=temporary.resolve("保留.json"); byte[] old={1,2,3}; Files.write(target,old);
            var writer=new AtomicProjectFile((stage,temp)->{},(source,destination)->{throw failure;});
            var document=new ProjectDocument(hierarchy(),new ProjectCodec(),writer); document.history().execute("edit",edit());
            assertThrows(failure.getClass(),()->document.save(target)); assertArrayEquals(old,Files.readAllBytes(target));
            assertTrue(document.history().isDirty()); assertTrue(document.path().isEmpty()); assertEquals(0,leftoverTemps());
        }
    }
    @Test void realMissingDirectoryAndDirectoryTargetErrorsPreserveDocument() throws Exception {
        var document=new ProjectDocument(hierarchy()); document.history().execute("edit",edit()); var before=document.history().state();
        assertThrows(IOException.class,()->document.save(temporary.resolve("不存在").resolve("file.json")));
        Path directory=temporary.resolve("directory.json"); Files.createDirectory(directory); Files.writeString(directory.resolve("keep.txt"),"keep");
        assertThrows(IOException.class,()->document.save(directory)); assertEquals("keep",Files.readString(directory.resolve("keep.txt")));
        assertEquals(before,document.history().state()); assertTrue(document.path().isEmpty()); assertTrue(document.history().isDirty()); assertEquals(0,leftoverTemps());
    }
    @Test void successfulReplaceChangesSavePointWithoutClearingHistoryAndOpenResetsState() throws Exception {
        Path target=temporary.resolve("中文保存.json"); var document=new ProjectDocument(hierarchy()); document.save(target);
        var saved=document.history().state().model(); document.history().select(Selection.of(id(3))); document.history().execute("edit",edit());
        var next=document.history().state().model(); document.save(target);
        assertEquals(next,new ProjectCodec().read(target)); assertFalse(document.history().isDirty()); assertEquals(1,document.history().undoCount());
        document.history().undo(); assertEquals(saved,document.history().state().model()); assertTrue(document.history().isDirty());
        document.history().redo(); assertFalse(document.history().isDirty());
        document.history().execute("more",Commands.model(d->ModelEdits.delete(d,id(2)))); document.open(target);
        assertEquals(next,document.history().state().model()); assertEquals(Selection.EMPTY,document.history().state().selection());
        assertFalse(document.history().isDirty()); assertEquals(0,document.history().undoCount()); assertEquals(0,document.history().redoCount()); assertEquals(0,leftoverTemps());
    }
    @Test void savingAndOpeningDuringPreviewRefuseBeforeIo() throws Exception {
        var document=new ProjectDocument(hierarchy()); var history=document.history(); history.beginPreview("drag"); history.updatePreview(edit());
        Path target=temporary.resolve("never-created.json");
        assertThrows(IllegalStateException.class,()->document.save(target)); assertFalse(Files.exists(target));
        assertThrows(IllegalStateException.class,()->document.open(temporary.resolve("missing.json")));
        history.cancelPreview(); assertFalse(history.isDirty()); assertEquals(0,leftoverTemps());
    }
    @Test void oversizedEncodingFailsBeforeAnyFileOrDocumentChange() throws Exception {
        Path target=temporary.resolve("keep.json"); byte[] old=new ProjectCodec().encode(DocumentSnapshot.EMPTY); Files.write(target,old);
        List<Attachment> attachments=new ArrayList<>(); String reference="中".repeat(4096);
        for(int i=0;i<1400;i++) attachments.add(new RegionAttachment(id(1000+i),"region"+i,id(10),reference,Transform.IDENTITY,1,1));
        var model=new DocumentSnapshot(List.of(bone(1,"a",null,Transform.IDENTITY)),List.of(new Slot(id(10),"s",id(1),null)),attachments);
        var document=new ProjectDocument(model); document.history().execute("edit",Commands.model(d->ModelEdits.editBone(d,id(1),b->b.withName("edited"))));
        var before=document.history().state();
        assertThrows(ProjectFormatException.class,()->document.save(target));
        assertArrayEquals(old,Files.readAllBytes(target)); assertEquals(before,document.history().state());
        assertTrue(document.history().isDirty()); assertTrue(document.path().isEmpty()); assertEquals(0,leftoverTemps());
    }
}
