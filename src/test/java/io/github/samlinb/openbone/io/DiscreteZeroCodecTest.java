package io.github.samlinb.openbone.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.P8ModelTest;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class DiscreteZeroCodecTest {
    @TempDir Path temp;
    private final ProjectCodec codec=new ProjectCodec();private final ObjectMapper json=new ObjectMapper();
    private ObjectNode tree()throws Exception{return (ObjectNode)json.readTree(codec.encode(P8ModelTest.sample()));}
    private ObjectNode discrete(ObjectNode tree){return (ObjectNode)tree.path("animations").get(0).path("discrete");}
    private ProjectDocument existing()throws Exception {
        var d=P8ModelTest.sample();var doc=new ProjectDocument(d);doc.save(temp.resolve("previous.json"));
        doc.history().execute("first",Commands.model(m->ModelEdits.editBone(m,id(1),b->b.withName("first"))));
        doc.history().execute("second",Commands.model(m->ModelEdits.editBone(m,id(1),b->b.withName("second"))));doc.history().undo();doc.history().select(Selection.of(id(20)));
        assertTrue(doc.history().isDirty());assertEquals(1,doc.history().undoCount());assertEquals(1,doc.history().redoCount());return doc;
    }
    private void rejectsAtomically(byte[] bytes)throws Exception {
        assertThrows(ProjectFormatException.class,()->codec.decode(bytes));Path bad=temp.resolve("bad.json");Files.write(bad,bytes);assertThrows(IOException.class,()->codec.read(bad));
        var doc=existing();var state=doc.history().state();var path=doc.path();var saved=Files.readAllBytes(path.orElseThrow());
        for(boolean resolved:new boolean[]{false,true}) {
            assertThrows(IOException.class,()->{if(resolved)doc.openResolved(bad);else doc.open(bad);});
            assertEquals(state,doc.history().state());assertEquals(path,doc.path());assertEquals(1,doc.history().undoCount());assertEquals(1,doc.history().redoCount());assertTrue(doc.history().isDirty());assertFalse(doc.history().isPreviewing());assertArrayEquals(saved,Files.readAllBytes(path.orElseThrow()));
        }
    }
    static Stream<Arguments> conflicts() {
        var result=Stream.<Arguments>builder();for(String kind:List.of("attachments","drawOrder"))for(double a:new double[]{0.0,-0.0})for(double b:new double[]{0.0,-0.0})result.add(Arguments.of(kind,a,b));return result.build();
    }
    @ParameterizedTest @MethodSource("conflicts")
    void ownConflictingTimesRejectAtDecodeReadAndPublicOpen(String kind,double first,double second)throws Exception {
        var root=tree();var keys=discrete(root).putArray(kind);
        for(int i=0;i<2;i++) {
            var key=keys.addObject().put("id",id(901+i).value()).put("time",i==0?first:second);
            if(kind.equals("attachments")){key.put("slotId",id(10).value());if(i==0)key.put("name","image");else key.putNull("name");}
            else {var slots=key.putArray("slots");for(int slot:i==0?new int[]{10,11,12}:new int[]{12,11,10})slots.add(id(slot).value());}
        }
        rejectsAtomically(json.writeValueAsBytes(root));
    }
    @ParameterizedTest @ValueSource(strings={"attachments","drawOrder","events"})
    void singleNegativeZeroLoadsAndEncodesCanonicalPositiveZero(String kind)throws Exception {
        var root=tree();((ObjectNode)discrete(root).path(kind).get(0)).put("time",-0.0);byte[] bytes=json.writeValueAsBytes(root);assertTrue(new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("-0.0"));
        var model=codec.decode(bytes);var encoded=json.readTree(codec.encode(model));assertEquals(0L,Double.doubleToRawLongBits(encoded.path("animations").get(0).path("discrete").path(kind).get(0).path("time").doubleValue()));
        Path file=temp.resolve("canonical.json");Files.write(file,bytes);assertEquals(model,codec.read(file));var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.openResolved(file);
        var tracks=doc.history().state().model().animations().get(0).discrete();double time=switch(kind){case "attachments"->tracks.attachments().get(0).time();case "drawOrder"->tracks.drawOrder().get(0).time();default->tracks.events().get(0).time();};assertEquals(0L,Double.doubleToRawLongBits(time));assertFalse(doc.history().isDirty());
    }
    @Test void ownRepeatedZeroEventsKeepInputOrderAndRemainRuntimeExportable()throws Exception {
        var root=tree();var events=discrete(root).putArray("events");for(int i=0;i<3;i++)events.addObject().put("id",id(901+i).value()).put("time",i%2==0?-0.0:0.0).put("eventId",id(40).value()).put("int",11*(i+1)).put("float",0).put("string","same-time").put("volume",1).put("balance",0);
        var model=codec.decode(json.writeValueAsBytes(root));var clip=model.animations().get(0);assertEquals(List.of(11,22,33),clip.discrete().events().stream().map(DiscreteTracks.EventKey::intValue).toList());assertEquals(List.of(11,22,33),EventSampler.between(clip,0,0,false,true).stream().map(f->f.key().intValue()).toList());
        assertEquals(model,codec.decode(codec.encode(model)));var paths=new LinkedHashMap<EntityId,String>();for(var a:model.attachments())paths.put(a.id(),a.name());var runtime=json.readTree(new SkeletonJsonCodec().encode(model,paths,"images/"));assertEquals(3,runtime.path("animations").path("walk").path("events").size());
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-zero-minimal.json","duplicate-zero-key.json"})
    void originalIndependentOwnFixturesAreRejectedWithoutPublication(String name)throws Exception {
        try(var in=getClass().getResourceAsStream("/regressions/p8-discrete-signed-zero/"+name)){assertNotNull(in);byte[] bytes=in.readAllBytes();assertTrue(new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("-0.0"));rejectsAtomically(bytes);}
    }
}
