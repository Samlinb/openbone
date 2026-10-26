package io.github.samlinb.openbone.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class AtlasOverflowBoundaryTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private Path fixture(String name)throws Exception {
        Path target=temp.resolve(name);
        try(var source=getClass().getResourceAsStream("/regressions/p8-atlas-overflow/"+name)) {
            assertNotNull(source);Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
    private ObjectNode own(int x,int y,boolean rotated)throws Exception {
        var root=(ObjectNode)json.readTree(fixture("invalid-project.json").toFile());
        ((ObjectNode)root.path("resources").path("atlas").get(0)).put("offsetX",x).put("offsetY",y).put("rotated",rotated);
        return root;
    }
    private ProjectDocument existing(boolean rotated)throws Exception {
        fixture("page.png");Path valid=temp.resolve("valid.json");json.writeValue(valid.toFile(),own(0,0,rotated));
        var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.openResolved(valid);doc.save(temp.resolve("previous-project.json"));
        var bone=doc.history().state().model().bones().get(0).id();
        doc.history().execute("first",Commands.model(d->ModelEdits.editBone(d,bone,b->b.withName("first"))));
        doc.history().execute("second",Commands.model(d->ModelEdits.editBone(d,bone,b->b.withName("second"))));
        doc.history().undo();doc.history().select(Selection.of(doc.history().state().model().attachments().get(0).id()));
        assertTrue(doc.history().isDirty());assertEquals(1,doc.history().undoCount());assertEquals(1,doc.history().redoCount());return doc;
    }
    private void atomicRejection(ProjectDocument doc,org.junit.jupiter.api.function.Executable operation) {
        var state=doc.history().state();var path=doc.path();int undo=doc.history().undoCount(),redo=doc.history().redoCount();boolean dirty=doc.history().isDirty();
        assertThrows(IOException.class,operation);assertEquals(state,doc.history().state());assertEquals(path,doc.path());
        assertEquals(undo,doc.history().undoCount());assertEquals(redo,doc.history().redoCount());assertEquals(dirty,doc.history().isDirty());assertFalse(doc.history().isPreviewing());
    }
    private Path runtime(int x,int y,boolean rotated,boolean compact)throws Exception {
        fixture("page.png");Path skeleton=fixture("skeleton.json");String atlas=Files.readString(fixture("skeleton.atlas"));
        atlas=atlas.replace("rotate: false","rotate: "+(rotated?"90":"0"));
        atlas=compact?atlas.replace("orig: 4, 4\noffset: 2147483647, 2147483647","offsets: "+x+", "+y+", 4, 4")
                     :atlas.replace("offset: 2147483647, 2147483647","offset: "+x+", "+y);
        Files.writeString(skeleton.resolveSibling("skeleton.atlas"),atlas);return skeleton;
    }
    static Stream<Arguments> invalidOffsets() {
        var cases=Stream.<Arguments>builder();
        for(boolean rotated:new boolean[]{false,true})for(boolean horizontal:new boolean[]{false,true})
            for(int offset:new int[]{3,Integer.MAX_VALUE-2,Integer.MAX_VALUE-1,Integer.MAX_VALUE})cases.add(Arguments.of(rotated,horizontal,offset));
        return cases.build();
    }
    @ParameterizedTest @MethodSource("invalidOffsets")
    void publicOwnAndRuntimeBoundariesRejectBeforePublication(boolean rotated,boolean horizontal,int offset)throws Exception {
        int x=horizontal?offset:0,y=horizontal?0:offset;byte[] bad=json.writeValueAsBytes(own(x,y,rotated));
        var codec=new ProjectCodec();assertThrows(ProjectFormatException.class,()->codec.decode(bad));
        var doc=existing(rotated);Path invalid=temp.resolve("bad-own.json");Files.write(invalid,bad);
        assertThrows(IOException.class,()->codec.read(invalid));atomicRejection(doc,()->doc.openResolved(invalid));
        var checkpoints=new AtomicInteger();var publisher=new ExportPackage((stage,path)->checkpoints.incrementAndGet(),new AtomicProjectFile());Path out=temp.resolve("must-not-publish");
        assertThrows(IOException.class,()->publisher.publish(out,codec.decode(bad),temp));assertFalse(Files.exists(out));assertEquals(0,checkpoints.get());
        for(boolean compact:new boolean[]{false,true}) {
            Path skeleton=runtime(x,y,rotated,compact);assertThrows(IOException.class,()->new SkeletonJsonCodec().read(skeleton));
            atomicRejection(doc,()->doc.importSkeleton(skeleton));
            assertThrows(IOException.class,()->publisher.publish(out,new SkeletonJsonCodec().read(skeleton),temp));assertFalse(Files.exists(out));assertEquals(0,checkpoints.get());
        }
        // UI commands share the same immutable record gate and cannot truncate redo on failure.
        var state=doc.history().state();int undo=doc.history().undoCount(),redo=doc.history().redoCount();var path=doc.path();
        assertThrows(IllegalArgumentException.class,()->doc.history().execute("invalid atlas edit",Commands.model(d->d.withResources(new Resources(d.resources().skins(),d.resources().activeSkin(),d.resources().events(),Map.of(d.attachments().get(0).id(),new AtlasRegion(4,4,0,0,2,2,4,4,x,y,rotated)))))));
        assertEquals(state,doc.history().state());assertEquals(undo,doc.history().undoCount());assertEquals(redo,doc.history().redoCount());assertTrue(doc.history().isDirty());assertEquals(path,doc.path());
    }
    @Test void originalIndependentFilesAreRejectedThroughActualPublicFileApis()throws Exception {
        Path own=fixture("invalid-project.json"),runtime=fixture("skeleton.json");fixture("skeleton.atlas");fixture("page.png");
        var doc=existing(false);atomicRejection(doc,()->doc.openResolved(own));atomicRejection(doc,()->doc.importSkeleton(runtime));
        assertThrows(IOException.class,()->new SkeletonJsonCodec().read(runtime));
    }
    @Test void legalEdgeTrimStillSavesLoadsPublishesAndImportsBothRotationsAndSpellings()throws Exception {
        for(boolean rotated:new boolean[]{false,true})for(boolean compact:new boolean[]{false,true}) {
            Path skeleton=runtime(2,2,rotated,compact);var d=new SkeletonJsonCodec().read(skeleton);
            var region=d.resources().atlas().values().iterator().next();assertEquals(new AtlasRegion(4,4,0,0,2,2,4,4,2,2,rotated),region);
            Path saved=temp.resolve("edge-"+rotated+"-"+compact+".json");new ProjectDocument(d).savePortable(saved);var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(saved);assertEquals(d.resources(),reopened.history().state().model().resources());
            Path out=temp.resolve("valid-export-"+rotated+"-"+compact);Path exported=new ExportPackage().publish(out,reopened.history().state().model(),temp);var imported=new SkeletonJsonCodec().read(exported);assertEquals(region,imported.resources().atlas().values().iterator().next());
            var vertices=imported.evaluateWorld().regions().values().iterator().next().vertices();assertTrue(vertices.stream().allMatch(v->Math.abs(v.x())<=2&&Math.abs(v.y())<=2));
        }
    }
}
