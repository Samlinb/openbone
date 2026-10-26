package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCodecTest {
    @TempDir Path temporary;
    private final ProjectCodec codec=new ProjectCodec();
    private final ObjectMapper mapper=new ObjectMapper();
    private ObjectNode valid() throws Exception { return (ObjectNode)mapper.readTree(codec.encode(hierarchy())); }
    private static ObjectNode bone(ObjectNode root) { return (ObjectNode)root.path("bones").get(0); }
    private static ObjectNode slot(ObjectNode root) { return (ObjectNode)root.path("slots").get(0); }
    private static ObjectNode attachment(ObjectNode root) { return (ObjectNode)root.path("attachments").get(0); }
    private static ObjectNode local(ObjectNode root) { return (ObjectNode)bone(root).get("local"); }

    @Test void deterministicRoundTripEmptyNegativeZeroAndChineseResources() throws Exception {
        List<DocumentSnapshot> models=List.of(DocumentSnapshot.EMPTY,hierarchy(),
                ModelEdits.editBone(hierarchy(),id(1),b->b.withLocal(new Transform(-0.0,2,720,0,-2))),
                ModelEdits.editBone(hierarchy(),id(1),b->b.withLocal(new Transform(1,2,-720,1e-14,0))));
        for(DocumentSnapshot model:models) {
            byte[] bytes=codec.encode(model); assertEquals(model,codec.decode(bytes)); assertArrayEquals(bytes,codec.encode(codec.decode(bytes)));
            assertEquals(model.evaluateWorld(),codec.decode(bytes).evaluateWorld());
        }
        Path path=temporary.resolve("中文 项目.json"); var document=new ProjectDocument(hierarchy()); document.save(path);
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY); reopened.open(path);
        assertEquals(hierarchy(),reopened.history().state().model()); assertFalse(reopened.history().isDirty());
        assertEquals("贴图/角色.png",((RegionAttachment)reopened.history().state().model().attachments().get(0)).source());
        // Missing resources are preserved references: P2 does not load textures.
        assertEquals("missing.png",((RegionAttachment)reopened.history().state().model().attachments().get(1)).source());
    }
    @TestFactory Stream<DynamicTest> rejectsBadStructureTypesReferencesAndVersionsWithoutReplacingDocument() {
        Map<String,Consumer<ObjectNode>> mutations=new LinkedHashMap<>();
        mutations.put("unknown version",r->r.put("schemaVersion",99));
        mutations.put("overflow version",r->r.put("schemaVersion",Long.MAX_VALUE));
        mutations.put("negative version",r->r.put("schemaVersion",-1));
        mutations.put("string version",r->r.put("schemaVersion","1"));
        mutations.put("float version",r->r.put("schemaVersion",1.0));
        mutations.put("null version",r->r.putNull("schemaVersion"));
        mutations.put("missing version",r->r.remove("schemaVersion"));
        mutations.put("unknown root field",r->r.put("futureAnimationField","unsupported"));
        mutations.put("missing bones",r->r.remove("bones"));
        mutations.put("null bones",r->r.putNull("bones"));
        mutations.put("object bones",r->r.set("bones",mapper.createObjectNode()));
        mutations.put("nonobject bone",r->((ArrayNode)r.get("bones")).set(0,mapper.getNodeFactory().textNode("bad")));
        mutations.put("unknown bone field",r->bone(r).put("inherit","onlyTranslation"));
        mutations.put("missing name",r->bone(r).remove("name"));
        mutations.put("null name",r->bone(r).putNull("name"));
        mutations.put("numeric name",r->bone(r).put("name",123));
        mutations.put("blank name",r->bone(r).put("name"," "));
        mutations.put("long name",r->bone(r).put("name","x".repeat(257)));
        mutations.put("duplicate bone name",r->bone(r).put("name","child"));
        mutations.put("duplicate bone ID",r->bone(r).put("id",id(2).value()));
        mutations.put("malformed ID",r->bone(r).put("id","1-1-1-1-1"));
        mutations.put("null ID",r->bone(r).putNull("id"));
        mutations.put("numeric ID",r->bone(r).put("id",1));
        mutations.put("missing parent field",r->bone(r).remove("parentId"));
        mutations.put("dangling parent",r->bone(r).put("parentId",id(99).value()));
        mutations.put("self parent",r->bone(r).put("parentId",id(1).value()));
        mutations.put("cycle",r->bone(r).put("parentId",id(3).value()));
        mutations.put("null local",r->bone(r).putNull("local"));
        mutations.put("array local",r->bone(r).set("local",mapper.createArrayNode()));
        mutations.put("unknown local field",r->local(r).put("shear",1));
        mutations.put("missing scale",r->local(r).remove("scaleY"));
        mutations.put("string scale",r->local(r).put("scaleX","1"));
        mutations.put("boolean scale",r->local(r).put("scaleX",true));
        mutations.put("null scale",r->local(r).putNull("scaleX"));
        mutations.put("negative length",r->bone(r).put("length",-1));
        mutations.put("world overflow",r->local(r).put("scaleX",Double.MAX_VALUE));
        mutations.put("slot null",r->r.putNull("slots"));
        mutations.put("unknown slot field",r->slot(r).put("blend","additive"));
        mutations.put("slot missing bone",r->slot(r).put("boneId",id(99).value()));
        mutations.put("slot wrong kind bone",r->slot(r).put("boneId",id(11).value()));
        mutations.put("duplicate slot name",r->slot(r).put("name","other"));
        mutations.put("duplicate slot ID",r->slot(r).put("id",id(11).value()));
        mutations.put("cross kind ID",r->slot(r).put("id",id(1).value()));
        mutations.put("slot missing active field",r->slot(r).remove("activeAttachmentId"));
        mutations.put("dangling active",r->slot(r).put("activeAttachmentId",id(99).value()));
        mutations.put("foreign slot active",r->slot(r).put("activeAttachmentId",id(22).value()));
        mutations.put("attachment null",r->r.putNull("attachments"));
        mutations.put("attachment unknown kind",r->attachment(r).put("type","mesh"));
        mutations.put("attachment missing kind",r->attachment(r).remove("type"));
        mutations.put("attachment unknown field",r->attachment(r).put("uvs",true));
        mutations.put("attachment dangling slot",r->attachment(r).put("slotId",id(99).value()));
        mutations.put("attachment duplicate ID",r->attachment(r).put("id",id(21).value()));
        mutations.put("attachment duplicate name",r->attachment(r).put("name","inactive"));
        mutations.put("blank source",r->attachment(r).put("source"," "));
        mutations.put("oversize source",r->attachment(r).put("source","x".repeat(4097)));
        mutations.put("zero width",r->attachment(r).put("width",0));
        mutations.put("negative height",r->attachment(r).put("height",-1));
        mutations.put("string width",r->attachment(r).put("width","64"));
        return mutations.entrySet().stream().map(entry->DynamicTest.dynamicTest(entry.getKey(),()->{
            var r=valid(); entry.getValue().accept(r);
            Path bad=temporary.resolve("坏数据.json"); Files.write(bad,mapper.writeValueAsBytes(r));
            var document=new ProjectDocument(hierarchy()); Path oldPath=temporary.resolve("已保存.json"); document.save(oldPath);
            document.history().select(Selection.of(id(3)));
            document.history().execute("move",Commands.model(d->ModelEdits.editBone(d,id(1),b->b.withLocal(Transform.IDENTITY))));
            document.history().undo(); var before=document.history().state(); byte[] old=Files.readAllBytes(oldPath);
            var failure=assertThrows(ProjectFormatException.class,()->document.open(bad)); assertFalse(failure.getMessage().isBlank());
            assertEquals(before,document.history().state()); assertEquals(0,document.history().undoCount()); assertEquals(1,document.history().redoCount());
            assertFalse(document.history().isDirty()); assertEquals(oldPath.toAbsolutePath(),document.path().orElseThrow()); assertArrayEquals(old,Files.readAllBytes(oldPath));
        }));
    }
    @TestFactory Stream<DynamicTest> malformedJsonNeverCoercesOrAcceptsTrailingData() throws Exception {
        String valid=new String(codec.encode(hierarchy()),StandardCharsets.UTF_8);
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("empty",""); cases.put("truncated",valid.substring(0,valid.length()-5)); cases.put("trailing",valid+" {}");
        cases.put("duplicate root key",valid.replace("\"schemaVersion\" : 2","\"schemaVersion\" : 2, \"schemaVersion\" : 2"));
        cases.put("duplicate nested key",valid.replaceFirst("\"length\" : 80.0","\"length\" : 80.0, \"length\" : 80.0"));
        cases.put("NaN",valid.replaceFirst("\"scaleX\" : -1.2","\"scaleX\" : NaN"));
        cases.put("Infinity",valid.replaceFirst("\"scaleX\" : -1.2","\"scaleX\" : Infinity"));
        cases.put("overflow number",valid.replaceFirst("\"scaleX\" : -1.2","\"scaleX\" : 1e999"));
        cases.put("comment","/*comment*/"+valid); cases.put("null","null"); cases.put("array","[]");
        cases.put("excess nesting","[".repeat(130)+"0"+"]".repeat(130));
        cases.put("oversize string","{\"x\":\""+"a".repeat(1_010_000)+"\"}");
        return cases.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->assertThrows(ProjectFormatException.class,()->codec.decode(e.getValue().getBytes(StandardCharsets.UTF_8)))));
    }
    @Test void sizeLimitsAndNativeFormatsAndReadFailures() throws Exception {
        assertThrows(ProjectFormatException.class,()->codec.decode(new byte[ProjectCodec.MAX_BYTES+1]));
        Path nativePath=temporary.resolve("project.spine"); Files.write(nativePath,codec.encode(hierarchy()));
        assertThrows(ProjectFormatException.class,()->codec.read(nativePath));
        assertThrows(ProjectFormatException.class,()->new ProjectDocument(hierarchy()).save(nativePath));
        Path binary=temporary.resolve("project.SKEL"); Files.write(binary,new byte[]{0,1,2});
        assertThrows(ProjectFormatException.class,()->codec.read(binary));
        var document=new ProjectDocument(hierarchy()); var before=document.history().state();
        assertThrows(java.io.IOException.class,()->document.open(temporary.resolve("missing.json"))); assertEquals(before,document.history().state());
        assertThrows(java.io.IOException.class,()->document.open(temporary)); assertEquals(before,document.history().state());
    }
}
