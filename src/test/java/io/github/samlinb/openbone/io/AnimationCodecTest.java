package io.github.samlinb.openbone.io;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationCodecTest {
    @TempDir Path temp;private final ProjectCodec codec=new ProjectCodec();private final ObjectMapper mapper=new ObjectMapper();
    static DocumentSnapshot animation(){
        var d=AnimationEdits.put(hierarchy(),AnimationClip.DEFAULT_ID,id(3),Channel.TRANSLATE,.123456789,List.of(10.,-20.));
        d=AnimationEdits.put(d,AnimationClip.DEFAULT_ID,id(3),Channel.TRANSLATE,1,List.of(-40.,80.));
        var first=d.animations().get(0).timelines().get(0).keys().get(0);
        d=AnimationEdits.curves(d,AnimationClip.DEFAULT_ID,Set.of(first.id()),List.of(new Curve(Curve.Kind.BEZIER,.2,0,.8,1),new Curve(Curve.Kind.BEZIER,.3,1,.7,0)));
        return AnimationEdits.put(d,AnimationClip.DEFAULT_ID,id(3),Channel.ROTATE,1,List.of(720.));
    }
    @Test void v2RoundtripAllIdsAndCurvesSavedPointAndSaveAsRemainExact()throws Exception{
        var d=animation();assertEquals(d,codec.decode(codec.encode(d)));var project=new ProjectDocument(d);var a=temp.resolve("中文工程.json");project.save(a);
        project.history().execute("Setup",Commands.model(v->ModelEdits.editBone(v,id(1),b->b.withName("新根"))));assertEquals(d.animations(),project.history().state().model().animations());
        var b=temp.resolve("另存.json");project.save(b);assertFalse(project.history().isDirty());project.history().undo();assertTrue(project.history().isDirty());project.history().redo();assertFalse(project.history().isDirty());
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.open(b,v->new DocumentSnapshot(v.bones(),v.slots(),v.attachments(),v.animations()));assertEquals(project.history().state().model(),reopened.history().state().model());
        for(double time:new double[]{0,.25,.5,.999,1})assertEquals(AnimationSampler.pose(d,AnimationClip.DEFAULT_ID,time,false),AnimationSampler.pose(codec.read(a),AnimationClip.DEFAULT_ID,time,false));
    }
    @Test void schema1MigrationPreservesEverySetupEntityAndRejectsUnknownFields()throws Exception{
        var node=(ObjectNode)mapper.readTree(codec.encode(hierarchy()));node.remove("animations");node.put("schemaVersion",1);var old=mapper.writeValueAsBytes(node);var d=codec.decode(old);
        assertEquals(hierarchy(),d);assertEquals(hierarchy().evaluateWorld(),d.evaluateWorld());assertEquals(2,mapper.readTree(codec.encode(d)).path("schemaVersion").asInt());
        node.putArray("animations");assertThrows(ProjectFormatException.class,()->codec.decode(mapper.writeValueAsBytes(node)));
    }
    @Test void schema1DefaultClipIdNeverCollidesWithExistingEntityIds()throws Exception{
        var id=AnimationClip.DEFAULT_ID;var bone=new Bone(id,"reserved-ID-bone",null,Transform.IDENTITY,1);
        var d=new DocumentSnapshot(List.of(bone),List.of(),List.of());assertEquals(bone,d.bones().get(0));assertNotEquals(id,d.animations().get(0).id());
        var node=(ObjectNode)mapper.readTree(codec.encode(d));node.remove("animations");node.put("schemaVersion",1);assertEquals(d,codec.decode(mapper.writeValueAsBytes(node)));
    }
    @Test void schema2EmptyClipListIsPreservedUntilTheUiRecordsAnAnimation()throws Exception{
        var d=hierarchy().withAnimations(List.of());assertEquals(d,codec.decode(codec.encode(d)));
    }
    private ObjectNode valid()throws Exception{return (ObjectNode)mapper.readTree(codec.encode(animation()));}
    private static ObjectNode clip(ObjectNode r){return (ObjectNode)r.path("animations").get(0);}
    private static ObjectNode track(ObjectNode r){return (ObjectNode)clip(r).path("timelines").get(0);}
    private static ObjectNode key(ObjectNode r){return (ObjectNode)track(r).path("keys").get(0);}
    private static ObjectNode curve(ObjectNode r){return (ObjectNode)key(r).path("curves").get(0);}
    @TestFactory Stream<DynamicTest> strictV2AndFailureAtomicity(){
        Map<String,Consumer<ObjectNode>> cases=new LinkedHashMap<>();
        cases.put("unknown clip field",r->clip(r).put("events",0));cases.put("duplicate clip ID",r->((ArrayNode)r.path("animations")).add(clip(r).deepCopy()));
        cases.put("unknown channel",r->track(r).put("channel","SHEAR"));cases.put("unknown track field",r->track(r).put("blend",1));cases.put("dangling bone",r->track(r).put("boneId",id(999).value()));
        cases.put("wrong bone kind",r->track(r).put("boneId",id(10).value()));cases.put("duplicate track",r->((ArrayNode)clip(r).path("timelines")).add(track(r).deepCopy()));
        cases.put("missing key values",r->key(r).remove("values"));cases.put("unknown key field",r->key(r).put("future",true));cases.put("negative time",r->key(r).put("time",-1));cases.put("string time",r->key(r).put("time","1"));
        cases.put("wrong component count",r->((ArrayNode)key(r).path("values")).remove(0));cases.put("wrong curve count",r->((ArrayNode)key(r).path("curves")).remove(0));cases.put("duplicate time",r->((ObjectNode)track(r).path("keys").get(1)).put("time",.123456789));
        cases.put("key global ID",r->key(r).put("id",id(1).value()));cases.put("unknown curve field",r->curve(r).put("extra",1));cases.put("unknown kind",r->curve(r).put("kind","EASE"));cases.put("bad handle time",r->curve(r).put("x1",.9));
        cases.put("string handle",r->curve(r).put("x2",".8"));cases.put("null handle",r->curve(r).putNull("x2"));
        return cases.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->{
            var node=valid();e.getValue().accept(node);var file=temp.resolve("bad.json");Files.write(file,mapper.writeValueAsBytes(node));var doc=new ProjectDocument(animation());var saved=temp.resolve("saved.json");doc.save(saved);
            doc.history().select(Selection.of(id(3)));doc.history().execute("rename",Commands.model(d->ModelEdits.editBone(d,id(3),b->b.withName("different"))));doc.history().undo();var before=doc.history().state();var bytes=Files.readAllBytes(saved);
            assertThrows(ProjectFormatException.class,()->doc.open(file));assertEquals(before,doc.history().state());assertFalse(doc.history().isDirty());assertEquals(1,doc.history().redoCount());assertArrayEquals(bytes,Files.readAllBytes(saved));assertEquals(saved,doc.path().orElseThrow());
        }));
    }
    @Test void animationIsIncludedInAtomicFailureProtection()throws Exception{
        var doc=new ProjectDocument(animation());var p=temp.resolve("saved.json");doc.save(p);byte[] original=Files.readAllBytes(p);var before=doc.history().state();
        assertThrows(java.io.IOException.class,()->doc.save(temp.resolve("missing").resolve("fail.json")));assertArrayEquals(original,Files.readAllBytes(p));assertEquals(before,doc.history().state());assertEquals(p,doc.path().orElseThrow());assertFalse(doc.history().isDirty());
    }
    @Test void codecRejectsFiniteAnimatedScaleThatWouldOverflowAnUntexturedBoneEndpoint()throws Exception{
        var d=new DocumentSnapshot(List.of(new Bone(id(101),"bare",null,Transform.IDENTITY,80)),List.of(),List.of());
        d=AnimationEdits.put(d,AnimationClip.DEFAULT_ID,id(101),Channel.SCALE,1,List.of(1.,1.));
        var node=(ObjectNode)mapper.readTree(codec.encode(d));((ArrayNode)key(node).path("values")).set(0,mapper.getNodeFactory().numberNode(Double.MAX_VALUE/4));
        var failure=assertThrows(ProjectFormatException.class,()->codec.decode(mapper.writeValueAsBytes(node)));assertTrue(failure.getMessage().contains("bone tip"));
    }
}
