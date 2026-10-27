package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.samlinb.openbone.io.SpineImportPolicy.*;

class SpineImportPolicyTest {
    @TempDir Path temp;

    @Test void onlyExplicitVersionRoutesAreKnownAndFallbackPrefersNearestNewerOfSameFormat() throws Exception {
        assertTrue(plan("3.8.55",Format.JSON,"hash").known());
        assertTrue(plan("4.2.22",Format.JSON,"hash").known());
        assertTrue(plan("4.0.21",Format.BINARY,"hash").known());
        for(String source:List.of("4.0.21","4.1.9","4.2.21","4.2.23","4.3.26"))assertTrue(plan(source,Format.JSON,"hash").known());
        for(String source:List.of("4.4.9","4.10.1","5.0.1")) {
            var selection=plan(source,Format.JSON,"hash");
            assertFalse(selection.known());assertEquals(Parser.JSON_43,selection.parser());
        }
        assertEquals(Parser.JSON_38,plan("3.7.99",Format.JSON,"hash").parser());
        assertEquals(Parser.JSON_38,plan("3.8.54-beta",Format.JSON,"hash").parser());
        assertFalse(plan("3.8.75",Format.BINARY,"hash").known());
        for(String source:List.of("4.4.9","5.0.1")) {
            var selection=plan(source,Format.BINARY,"hash");
            assertFalse(selection.known());assertEquals(Parser.BINARY_43,selection.parser());
        }
        assertThrows(ProjectFormatException.class,()->plan("garbage",Format.JSON,"hash"));
    }

    @Test void unknownJsonRequiresApprovalAndPreservesOriginalVersionAndBytes() throws Exception {
        Path file=json("4.4.9");byte[] bytes=Files.readAllBytes(file);
        var codec=new SkeletonJsonCodec();
        var risk=assertThrows(SpineVersionRiskException.class,()->codec.read(file));
        assertEquals("4.4.9",risk.selection().sourceVersion());
        assertEquals(Parser.JSON_43,risk.selection().parser());
        assertTrue(codec.sourceVersion().isEmpty());
        var model=codec.read(file,temp,risk.selection().approval());
        assertEquals(1,model.bones().size());
        assertEquals(Optional.of("4.4.9"),codec.sourceVersion());
        assertTrue(codec.warnings().stream().anyMatch(w->w.contains("4.4.9")&&w.contains("4.3.26")));
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }

    @Test void knownJsonImportsNormallyAndForcedOlderJsonUses38Translation() throws Exception {
        for(String version:List.of("3.8.75","4.2.22")) {
            var codec=new SkeletonJsonCodec();codec.read(json(version));
            assertEquals(Optional.of(version),codec.sourceVersion());assertTrue(codec.warnings().isEmpty());
        }
        Path file=json("3.7.99");var codec=new SkeletonJsonCodec();
        var risk=assertThrows(SpineVersionRiskException.class,()->codec.read(file));
        assertEquals(Parser.JSON_38,risk.selection().parser());
        assertEquals(1,codec.read(file,temp,risk.selection().approval()).bones().size());
        assertEquals(Optional.of("3.7.99"),codec.sourceVersion());
    }

    @Test void riskChangedSourceWrongParserAndInvalidForcedDataPreserveDocumentHistory() throws Exception {
        Path file=json("4.4.9");
        var document=new ProjectDocument(DocumentSnapshot.EMPTY);
        var bone=new io.github.samlinb.openbone.core.model.Bone(io.github.samlinb.openbone.core.model.EntityId.create(),"existing",null,io.github.samlinb.openbone.core.math.Transform.IDENTITY,10);
        document.history().execute("edit",Commands.model(d->new DocumentSnapshot(List.of(bone),List.of(),List.of())));
        document.history().undo();
        var state=document.history().state();String revision=document.history().revision();
        var risk=assertThrows(SpineVersionRiskException.class,()->document.openResolved(file));
        assertSame(state,document.history().state());assertEquals(revision,document.history().revision());
        assertEquals(1,document.history().redoCount());assertTrue(document.path().isEmpty());
        var wrong=new Approval(risk.selection().sourceHash(),Parser.JSON_38.name());
        assertThrows(ProjectFormatException.class,()->document.openResolved(file,wrong));
        Files.writeString(file,Files.readString(file)+" ");
        assertThrows(ProjectFormatException.class,()->document.openResolved(file,risk.selection().approval()));
        Files.write(file,new ProjectCodec().encode(DocumentSnapshot.EMPTY));
        assertThrows(ProjectFormatException.class,()->document.openResolved(file,risk.selection().approval()));
        Files.writeString(file,"{\"skeleton\":{\"spine\":\"4.4.9\"},\"bones\":[{\"name\":\"root\",\"parent\":\"missing\"}],\"slots\":[],\"skins\":[{\"name\":\"default\",\"attachments\":{}}]}");
        var invalid=assertThrows(SpineVersionRiskException.class,()->document.openResolved(file));
        assertThrows(ProjectFormatException.class,()->document.openResolved(file,invalid.selection().approval()));
        assertSame(state,document.history().state());assertEquals(revision,document.history().revision());
        assertEquals(1,document.history().redoCount());assertTrue(document.path().isEmpty());
    }

    @Test void unknownBinaryIsGatedBeforeBodyAndForceStillValidatesActualWireStructure() throws Exception {
        var model=new SkeletonJsonCodec().read(Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel"));
        var paths=new LinkedHashMap<io.github.samlinb.openbone.core.model.EntityId,String>();for(var a:model.attachments())if(a.source()!=null)paths.put(a.id(),a.name());
        byte[] source=SpineBinaryWriter.encode(model,paths,"","4.3.26");
        // Synthetic header change only: this fixture does NOT claim support for actual 4.4 wire data.
        byte[] changed=new byte[source.length-1];System.arraycopy(source,0,changed,0,8);
        changed[8]=6;System.arraycopy("4.4.9".getBytes(StandardCharsets.UTF_8),0,changed,9,5);
        System.arraycopy(source,15,changed,14,source.length-15);
        var risk=assertThrows(SpineVersionRiskException.class,()->Spine40Binary.decode(changed));
        assertEquals("4.4.9",risk.selection().sourceVersion());assertEquals(Parser.BINARY_43,risk.selection().parser());
        var decoded=Spine40Binary.decode(changed,risk.selection().approval());
        assertEquals("4.4.9",decoded.sourceVersion());assertEquals(17,decoded.tree().path("bones").size());
        byte[] truncated=Arrays.copyOf(changed,changed.length-1);
        var broken=assertThrows(SpineVersionRiskException.class,()->Spine40Binary.decode(truncated));
        assertThrows(ProjectFormatException.class,()->Spine40Binary.decode(truncated,broken.selection().approval()));
    }

    private Path json(String version)throws Exception {
        Path file=temp.resolve("source.json");
        Files.writeString(file,"{\"skeleton\":{\"spine\":\""+version+"\"},\"bones\":[{\"name\":\"root\"}],\"slots\":[],\"skins\":[{\"name\":\"default\",\"attachments\":{}}]}");
        return file;
    }
}
