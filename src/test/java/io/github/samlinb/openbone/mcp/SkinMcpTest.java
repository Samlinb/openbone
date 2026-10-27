package io.github.samlinb.openbone.mcp;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.Transform;
import java.nio.file.Path;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SkinMcpTest {
    @TempDir Path temp;
    EditorMcpServerTest protocol;
    DocumentSnapshot model;
    @BeforeEach void start() throws Exception {
        var root=EntityId.create(); var arm=EntityId.create();
        var activation=new SkinActivation(Set.of(arm),Set.of(),Map.of("weapon",new SkinActivation.Members(Set.of(arm),Set.of())));
        model=new DocumentSnapshot(List.of(new Bone(root,"root",null,Transform.IDENTITY,0),
                new Bone(arm,"arm",root,Transform.IDENTITY,10)),List.of(),List.of(),List.of(),List.of(),
                new Resources(List.of(new Skin("default",Map.of()),new Skin("weapon",Map.of())),"default",List.of(),Map.of(),SpineMetadata.EMPTY,activation));
        protocol=new EditorMcpServerTest(); protocol.temp=temp;
        SwingUtilities.invokeAndWait(()->protocol.host.installProject(new ProjectDocument(model))); protocol.start();
    }
    @AfterEach void stop() { if(protocol!=null && protocol.server!=null)protocol.close(); }
    @Test void querySelectionRetryConflictAtomicFailureAndUndoUseSharedHistory() throws Exception {
        var skins=protocol.ok("openbone_query_scene",Map.of("kind","skins","offset",1,"limit",1));
        assertEquals(2,skins.path("total").asInt()); assertEquals("weapon",skins.path("items").get(0).path("name").asText());
        assertEquals(1,skins.path("items").get(0).path("boneIds").size());
        var bones=protocol.ok("openbone_query_scene",Map.of("kind","bones"));
        assertFalse(bones.path("items").get(1).path("skinActive").asBoolean());
        String original=protocol.revision();
        var args=Map.of("requestId","skin","expectedRevision",original,"edits",List.of(Map.of("op","set_skin","name","weapon")));
        var after=protocol.ok("openbone_apply_edits",args); assertEquals(after,protocol.ok("openbone_apply_edits",args));
        assertEquals("weapon",after.path("activeSkin").asText()); assertEquals(1,after.path("undoCount").asInt());
        assertTrue(protocol.ok("openbone_query_scene",Map.of("kind","bones")).path("items").get(1).path("skinActive").asBoolean());
        var stale=protocol.call("openbone_apply_edits",Map.of("requestId","stale","expectedRevision",original,
                "edits",List.of(Map.of("op","set_skin","name","default")))); assertTrue(stale.path("isError").asBoolean());
        var undoArgs=Map.of("requestId","undo","expectedRevision",protocol.revision());
        var undone=protocol.ok("openbone_undo",undoArgs); assertEquals(undone,protocol.ok("openbone_undo",undoArgs));
        assertEquals(model,protocol.host.history.state().model());
        String before=protocol.revision();
        var bad=protocol.call("openbone_apply_edits",Map.of("requestId","bad","expectedRevision",before,
                "edits",List.of(Map.of("op","set_skin","name","weapon"),Map.of("op","set_skin","name","missing"))));
        assertTrue(bad.path("isError").asBoolean()); assertEquals(before,protocol.revision());
        assertEquals(model,protocol.host.history.state().model()); assertEquals(1,protocol.host.history.redoCount());
    }
}
