package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SkinActivationTest {
    @TempDir Path temp;
    static final String JSON = """
        {"skeleton":{"spine":"4.1.24"},"bones":[{"name":"root"},
         {"name":"parent","parent":"root","skin":true},{"name":"arm","parent":"parent","length":10,"skin":true},
         {"name":"goal","parent":"root","x":10,"y":10}],"slots":[],
         "ik":[{"name":"aim","bones":["arm"],"target":"goal","skin":true}],
         "skins":[{"name":"default","attachments":{}},{"name":"weapon","bones":["arm"],"ik":["aim"]}],
         "animations":{"idle":{}}}
        """;
    DocumentSnapshot fixture() throws Exception {
        return new SkeletonJsonCodec().decode(JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8),temp);
    }
    @Test void membershipActivatesAncestorsAndConstraintAndSurvivesEditsSaveAndUndo() throws Exception {
        var d=fixture(); assertEquals(2,d.activeBones().size()); assertTrue(d.activeConstraints(d.constraints()).isEmpty());
        var history=new CommandHistory(d);
        history.execute("skin",Commands.model(model -> ExtendedEdits.skin(model,"weapon")));
        var active=history.state().model(); assertEquals(4,active.activeBones().size());
        assertEquals(1,active.activeConstraints(active.constraints()).size());
        var arm=active.bones().stream().filter(b -> b.name().equals("arm")).findFirst().orElseThrow();
        assertNotEquals(d.evaluateWorld().bones().get(arm.id()),active.evaluateWorld().bones().get(arm.id()));
        var renamed=ModelEdits.editBone(active,arm.id(),b -> b.withName("renamed"));
        assertEquals(active.resources().activation(),renamed.resources().activation());
        var codec=new ProjectCodec(); assertEquals(renamed,codec.decode(codec.encode(renamed)));
        history.undo(); assertEquals(d,history.state().model()); history.redo(); assertEquals(active,history.state().model());
        assertEquals(1,history.undoCount());
        var deleted=ModelEdits.delete(active,arm.id());
        assertFalse(deleted.resources().activation().requiredBones().contains(arm.id()));
        assertTrue(deleted.resources().activation().requiredConstraints().isEmpty());
        assertEquals(active.resources().activation(),ExtendedEdits.addSkin(active,"extra").resources().activation());
    }
    @Test void malformedMembershipPreservesRevisionSelectionAndRedo() throws Exception {
        var document=new ProjectDocument(fixture());
        document.history().execute("skin",Commands.model(d -> ExtendedEdits.skin(d,"weapon"))); document.history().undo();
        var before=document.history().state(); String revision=document.history().revision();
        for (String invalid : List.of(JSON.replace("\"bones\":[\"arm\"],\"ik\"","\"bones\":[\"missing\"],\"ik\""),
                JSON.replace("\"ik\":[\"aim\"]","\"ik\":[\"missing\"]"),JSON.replace("\"skin\":true","\"skin\":\"true\""))) {
            Path path=temp.resolve("invalid.json"); Files.writeString(path,invalid);
            assertThrows(ProjectFormatException.class,()->document.openResolved(path));
            assertEquals(before,document.history().state()); assertEquals(revision,document.history().revision());
            assertEquals(1,document.history().redoCount());
        }
    }
    @Test void defaultSkinMembersAndStable42ImportRetainActivationInJsonExport() throws Exception {
        String single=JSON.replace("4.1.24","4.2.22").replace("{\"name\":\"default\",\"attachments\":{}},{\"name\":\"weapon\"",
                "{\"name\":\"default\",\"attachments\":{}");
        var d=new SkeletonJsonCodec().decode(single.getBytes(java.nio.charset.StandardCharsets.UTF_8),temp);
        assertEquals(4,d.activeBones().size()); assertEquals(1,d.activeConstraints(d.constraints()).size());
        var codec=new ProjectCodec(); assertEquals(d,codec.decode(codec.encode(d)));
        assertDoesNotThrow(()->RuntimeRange.validate(d));
        var restored=new SkeletonJsonCodec().decode(new SkeletonJsonCodec().encode(d,Map.of(),""),temp);
        assertEquals(4,restored.activeBones().size());
    }
    @Test void namedSkinActivationRoundtripsInEveryModernWireFamily() throws Exception {
        var d=fixture();
        for(String version:List.of("4.0.64","4.1.24","4.2.22","4.3.26")) {
            var path=temp.resolve(version+".skel");Files.write(path,SpineBinaryWriter.encode(d,Map.of(),"",version));
            var imported=new SkeletonJsonCodec().read(path);
            assertEquals(2,imported.resources().activation().requiredBones().size());
            assertEquals(1,imported.resources().activation().requiredConstraints().size());
            assertEquals(2,imported.activeBones().size());
            var selected=ExtendedEdits.skin(imported,"weapon");
            assertEquals(4,selected.activeBones().size());assertEquals(1,selected.activeConstraints(selected.constraints()).size());
        }
    }
    @Test void skinRequiredPhysicsImportsInactiveAndRoundtrips() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); var codec=new SkeletonJsonCodec();
        var tree=mapper.readTree(codec.encode(PhysicsCodecTest.fixture(0),Map.of(),""));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("physics").get(0)).put("skin",true);
        var d=codec.decode(mapper.writeValueAsBytes(tree),temp);
        assertEquals(1,d.resources().activation().requiredConstraints().size());
        assertTrue(d.activeConstraints(d.constraints()).isEmpty());
        var own=new ProjectCodec(); assertEquals(d,own.decode(own.encode(d)));
    }
}
