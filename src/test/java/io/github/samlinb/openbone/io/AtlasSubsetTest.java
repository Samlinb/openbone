package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class AtlasSubsetTest {
    @TempDir Path temp;
    static final String RESOURCE="/regressions/p5-atlas/";
    Path fixture(String name) throws Exception {
        Path folder=temp.resolve(name);Files.createDirectories(folder.resolve("images"));
        for(String file:List.of("skeleton.json","skeleton.atlas","images/region_0.png")){
            try(var input=AtlasSubsetTest.class.getResourceAsStream(RESOURCE+name+"/"+file)){
                assertNotNull(input);Files.copy(input,folder.resolve(file));
            }
        }
        return folder.resolve("skeleton.json");
    }
    static String baseline() throws IOException {
        try(var input=AtlasSubsetTest.class.getResourceAsStream(RESOURCE+"control/skeleton.atlas")){
            return new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
        }
    }
    static String page(String source,String entry){return source.replace("format: RGBA8888",entry+"\nformat: RGBA8888");}
    static String region(String source,String entry){return source.replace("region_0\n","region_0\n"+entry+"\n");}
    static String modern(String source){return source.replace("  xy: 0,0\n  size: 64,48","  bounds: 0,0,64,48").replace("  orig: 64,48\n  offset: 0,0","  offsets: 0,0,64,48");}
    static Stream<Arguments> invalid() throws Exception {
        String b=baseline(),m=modern(b);List<Arguments> cases=new ArrayList<>();
        for(String field:List.of("rotate: false","index: -1","xy: 0,0","orig: 64,48","offset: 0,0","bounds: 0,0,64,48","offsets: 0,0,64,48"))
            cases.add(Arguments.of("page wrong scope "+field,page(b,field),".page[images/region_0.png]."+field.split(":")[0]));
        for(String field:List.of("format: RGBA8888","filter: Nearest,Nearest","repeat: none","pma: false"))
            cases.add(Arguments.of("region wrong scope "+field,region(b,field),".region[region_0]."+field.split(":")[0]));
        for(String field:List.of("size: 64,48","format: RGBA8888","filter: Nearest,Nearest","repeat: none","pma: false")){
            cases.add(Arguments.of("duplicate page "+field,page(b,field),"Duplicate atlas field"));
            cases.add(Arguments.of("duplicate page differing "+field,page(b,field.replaceFirst(":.*",": invalid")),"Duplicate atlas field"));
        }
        for(String field:List.of("rotate: false","xy: 0,0","size: 64,48","orig: 64,48","offset: 0,0","index: -1")){
            cases.add(Arguments.of("duplicate region "+field,region(b,field),"Duplicate atlas field"));
            cases.add(Arguments.of("duplicate region differing "+field,region(b,field.replaceFirst(":.*",": 47")),"Duplicate atlas field"));
        }
        for(String field:List.of("bounds: 0,0,64,48","offsets: 0,0,64,48"))
            cases.add(Arguments.of("duplicate modern "+field,region(m,field),"Duplicate atlas field"));
        for(String field:List.of("xy: 0,0","size: 64,48","offset: 0,0","orig: 64,48")){
            String modernField=field.startsWith("xy:")||field.startsWith("size:")?"  bounds: 0,0,64,48":"  offsets: 0,0,64,48";
            cases.add(Arguments.of("alias legacy first "+field,region(m,field),"Conflicting atlas fields"));
            cases.add(Arguments.of("alias modern first "+field,m.replace(modernField,modernField+"\n  "+field),"Conflicting atlas fields"));
        }
        for(String field:List.of("format: RGBA8888,ignored","filter: Nearest,Nearest,ignored","repeat: none,ignored","pma: false,","pma: False","repeat: ignored"))
            cases.add(Arguments.of("invalid page values "+field,b.replaceFirst("(?m)^"+field.split(":")[0]+":.*$",field),".page[images/region_0.png]."+field.split(":")[0]));
        for(String field:List.of("index:","index: -1,ignored","rotate: 0,ignored","xy: 0,0,ignored","size: 64;48","orig: 64,,48","offset: 0:0"))
            cases.add(Arguments.of("invalid region values "+field,b.replaceFirst("(?m)^  "+field.split(":")[0]+":.*$","  "+field),".region[region_0]."+field.split(":")[0]));
        cases.add(Arguments.of("ignored file header","format: RGBA8888\n\n"+b,"$atlas.header.format"));
        cases.add(Arguments.of("trimmed duplicate key",page(b," \tformat \t: RGBA8888"),"Duplicate atlas field"));
        cases.add(Arguments.of("second region duplicate",b.stripTrailing()+"\nsecond\nindex: -1\nindex: -1\n",".region[second].index"));
        cases.add(Arguments.of("page without region",b.substring(0,b.indexOf("region_0\n")),"Page has no region"));
        cases.add(Arguments.of("second page wrong scope",b+"\nother.png\nrotate:false\nother\nsize:64,48\n",".page[other.png].rotate"));
        cases.add(Arguments.of("missing page separator",b.stripTrailing()+"\nother.png\nformat:RGBA8888\n",".region[other.png].format"));
        cases.add(Arguments.of("duplicate page declaration",b+"\n"+b,"Duplicate atlas page"));
        cases.add(Arguments.of("unknown page field",page(b,"custom: 1"),".page[images/region_0.png].custom"));
        cases.add(Arguments.of("unknown region field",region(b,"custom: 1"),".region[region_0].custom"));
        cases.add(Arguments.of("case sensitive field",page(b,"Format: RGBA8888"),".page[images/region_0.png].Format"));
        cases.add(Arguments.of("integer overflow",b.replace("index: -1","index: 2147483648"),"Invalid atlas integer"));
        cases.add(Arguments.of("unicode separator is not CR/LF",b.replace("pma: false","pma: false\u2028rotate: true"),".page[images/region_0.png].pma"));
        return cases.stream();
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalid")
    void rejectsScopedDuplicatesAliasesAndMalformedValues(String name,String text,String location) throws Exception {
        Path json=fixture("control");Files.writeString(json.resolveSibling("skeleton.atlas"),text);
        String message=assertThrows(IOException.class,()->new SkeletonJsonCodec().read(json),name).getMessage();
        assertTrue(message.contains(location),message);assertTrue(message.matches("(?s).*skeleton\\.atlas:\\d+ .*"),message);
    }
    static Stream<Arguments> originals(){return Stream.of(Arguments.of("page-rotate",".rotate"),Arguments.of("page-index",".index"),Arguments.of("duplicate-page-format","Duplicate atlas field"));}
    @ParameterizedTest @MethodSource("originals")
    void originalIndependentRejectionsPreserveCleanAndDirtyRedoDocuments(String name,String location) throws Exception {
        Path control=fixture("control"),bad=fixture(name);var model=new SkeletonJsonCodec().read(control);assertEquals(3,model.bones().size());
        for(boolean dirty:List.of(false,true)){
            var doc=new ProjectDocument(model);Path old=temp.resolve("existing-"+dirty+".json");doc.savePortable(old);
            var history=doc.history();var bone=model.bones().get(0).id();
            if(dirty){history.execute("rename",Commands.model(m->ModelEdits.editBone(m,bone,b->b.withName("edited"))));history.execute("pending redo",Commands.model(m->ModelEdits.editBone(m,bone,b->b.withName("redo-pending"))));history.undo();}
            history.select(Selection.of(bone));history.selectKeys(Set.of(model.animations().get(0).timelines().get(0).keys().get(0).id()));
            var before=history.state();var path=doc.path();var undo=history.undoLabel();int u=history.undoCount(),r=history.redoCount();byte[] bytes=Files.readAllBytes(old);
            String message=assertThrows(IOException.class,()->doc.importSkeleton(bad)).getMessage();assertTrue(message.contains(location),message);
            assertEquals(before,history.state());assertEquals(path,doc.path());assertEquals(dirty,history.isDirty());assertEquals(u,history.undoCount());assertEquals(r,history.redoCount());assertEquals(undo,history.undoLabel());assertArrayEquals(bytes,Files.readAllBytes(old));
            if(dirty){assertTrue(history.redo());assertEquals("redo-pending",history.state().model().bone(bone).name());assertTrue(history.undo());assertEquals(before.model(),history.state().model());}
        }
    }
    @Test void originalIndependentInputsRemainByteExact() throws Exception {
        var manifest=new ObjectMapper().readTree(AtlasSubsetTest.class.getResourceAsStream(RESOURCE+"SOURCE.json"));
        for(var row:manifest){try(var input=AtlasSubsetTest.class.getResourceAsStream(RESOURCE+row.path("path").asText())){
            assertNotNull(input);assertEquals(row.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }}
    }
    @Test void normalMultiPageLegacyModernWhitespaceAndScopeResetRemainSupported() throws Exception {
        Path control=fixture("control");var root=new Bone(id(1),"root",null,Transform.IDENTITY,50);
        var model=new DocumentSnapshot(List.of(root),List.of(new Slot(id(10),"first",id(1),id(20)),new Slot(id(11),"second",id(1),id(21))),List.of(new RegionAttachment(id(20),"image",id(10),control.resolveSibling("images/region_0.png").toString(),Transform.IDENTITY,64,48),new RegionAttachment(id(21),"image",id(11),control.resolveSibling("images/region_0.png").toString(),Transform.IDENTITY,64,48)));
        Path json=new ExportPackage().publish(temp.resolve("multi-page"),model,temp),atlas=json.resolveSibling("skeleton.atlas");String b=Files.readString(atlas),m=modern(b);
        assertNotEquals(b,m); // Ensure the modern-path positive actually uses bounds/offsets.
        String defaults=m.replace("format: RGBA8888\n","").replace("filter: Nearest,Nearest\n","").replace("repeat: none\n","").replace("pma: false\n","");
        for(String text:List.of(b,m,defaults,"\n \t\n"+m.replaceAll("(?m)^ +", "").replace(":", " \t: \t").replace(",", " , ").replace("\n\n","\n \t\n\n"),m.replace("\n","\r\n"),m.replace("\n","\r"))){
            Files.writeString(atlas,text);var loaded=new SkeletonJsonCodec().read(json);assertEquals(2,loaded.attachments().size());assertEquals(2,loaded.slots().size());
        }
    }
}
