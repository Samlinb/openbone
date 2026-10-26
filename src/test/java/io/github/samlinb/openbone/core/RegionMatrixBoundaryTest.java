package io.github.samlinb.openbone.core;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for independent P4 FAIL: tiny dimensions must not hide a nonfinite matrix. */
class RegionMatrixBoundaryTest {
    @TempDir Path temp;
    static final EntityId BONE=id(101),SLOT=id(102),REGION=id(103),CLIP=AnimationClip.DEFAULT_ID;
    private record Case(String name,Transform region,double size,double x,double y,boolean parent,boolean active,boolean accepted,String error){}
    private static List<Case> cases(){return List.of(
        new Case("independent literal overflow",new Transform(0,0,0,1e200,1e200),1e-200,1e200,1e200,false,true,false,"region matrix"),
        new Case("adjacent finite 1e308",new Transform(0,0,0,1e200,1e200),1e-200,1e108,1e108,false,true,true,""),
        new Case("adjacent overflowing 2e308",new Transform(0,0,0,1e200,1e200),1e-200,2e108,2e108,false,true,false,"region matrix"),
        new Case("finite negative nonuniform inactive",new Transform(0,0,0,-1e200,.5e200),1e-200,-1e108,.5e108,false,false,true,""),
        new Case("overflow negative nonuniform inactive",new Transform(0,0,0,-1e200,.5e200),1e-200,-2e108,.5e108,false,false,false,"region matrix"),
        new Case("finite parent chain rotated region",new Transform(1e100,-1e100,29,-1e200,.5e200),1e-200,1e54,.5e54,true,false,true,""),
        new Case("overflow parent chain inactive",new Transform(0,0,0,1e200,1e200),1e-200,2e54,2e54,true,false,false,"region matrix"),
        new Case("finite translation near 1e308",new Transform(1e200,0,0,1,1),1e-200,1e108,1e108,false,true,true,""),
        new Case("overflow translation independent of dimensions",new Transform(1e200,0,0,1,1),1e-200,2e108,2e108,false,false,false,"region translation"),
        new Case("finite large vertices",new Transform(0,0,0,1e100,1e100),1e100,1e108,1e108,false,true,true,""),
        new Case("overflow vertices with finite matrix",new Transform(0,0,0,1e100,1e100),1e100,4e108,4e108,false,false,false,"region vertex radius"),
        new Case("zero scale with huge attachment",new Transform(0,0,17,1e200,-1e200),1e-200,0,0,false,true,true,"")
    );}
    private static DocumentSnapshot setup(Case c){
        var bones=new ArrayList<Bone>();if(c.parent())bones.add(new Bone(id(100),"parent",null,new Transform(1e50,-1e50,0,1e54,1e54),0));
        bones.add(new Bone(BONE,"zero",c.parent()?id(100):null,Transform.IDENTITY,0));
        return new DocumentSnapshot(bones,List.of(new Slot(SLOT,"slot",BONE,c.active()?REGION:null)),
                List.of(new RegionAttachment(REGION,"tiny",SLOT,"tiny.png",c.region(),c.size(),c.size())));
    }
    private static DocumentSnapshot animated(DocumentSnapshot d,double x,double y){return AnimationEdits.put(d,CLIP,BONE,Channel.SCALE,1,List.of(x,y));}
    private static void finitePose(DocumentSnapshot d){
        for(double t:new double[]{0,.25,.5,.999,1,2}){
            var pose=d.evaluateWorld(AnimationSampler.pose(d,CLIP,t,false));
            for(var b:pose.bones().values()){finite(b.matrix());finite(b.origin());finite(b.tip());}
            var r=pose.regions().get(REGION);assertNotNull(r,"inactive regions are evaluated too");finite(r.matrix());r.vertices().forEach(RegionMatrixBoundaryTest::finite);
        }
    }
    private static void finite(Affine2 m){for(double v:new double[]{m.a(),m.b(),m.c(),m.d(),m.tx(),m.ty()})assertTrue(Double.isFinite(v));}
    private static void finite(Vec2 p){assertTrue(Double.isFinite(p.x()));assertTrue(Double.isFinite(p.y()));}
    @TestFactory Stream<DynamicTest> publishedMatricesTranslationsAndVerticesAreIndependentlySafe(){
        return cases().stream().map(c->DynamicTest.dynamicTest(c.name(),()->{
            var d=setup(c);assertDoesNotThrow(()->{d.evaluateWorld();});
            if(c.accepted()){
                var a=animated(d,c.x(),c.y());finitePose(a);var codec=new ProjectCodec();assertEquals(a,codec.decode(codec.encode(a)));finitePose(codec.decode(codec.encode(a)));
                if(c.name().equals("adjacent finite 1e308"))assertEquals(1e308,a.evaluateWorld(AnimationSampler.pose(a,CLIP,1,false)).regions().get(REGION).matrix().a(),1e294);
            }else{
                var e=assertThrows(IllegalArgumentException.class,()->animated(d,c.x(),c.y()));assertTrue(e.getMessage().contains(c.error()),e.getMessage());
                // Encode a known-safe key, then mutate only raw JSON: invalid models cannot be constructed.
                var codec=new ProjectCodec();var mapper=new ObjectMapper();var node=mapper.readTree(codec.encode(animated(d,1,1)));
                var values=(ArrayNode)node.path("animations").get(0).path("timelines").get(0).path("keys").get(0).path("values");
                values.set(0,mapper.getNodeFactory().numberNode(c.x()));values.set(1,mapper.getNodeFactory().numberNode(c.y()));
                assertThrows(ProjectFormatException.class,()->codec.decode(mapper.writeValueAsBytes(node)));
            }
        }));
    }
    @Test void exactIndependentFixtureIsRejectedByStrictDecodeAndOpenWithoutTouchingHistoryOrSavedFile()throws Exception{
        byte[] invalid;try(var stream=getClass().getResourceAsStream("/regressions/p4-region-matrix-overflow.json")){assertNotNull(stream);invalid=stream.readAllBytes();}
        var codec=new ProjectCodec();var failure=assertThrows(ProjectFormatException.class,()->codec.decode(invalid));assertTrue(failure.getMessage().contains("region matrix"));
        Path bad=temp.resolve("独立失败样本.json");Files.write(bad,invalid);
        var d=setup(cases().get(0));d=AnimationEdits.put(d,CLIP,BONE,Channel.ROTATE,0,List.of(5.));
        var doc=new ProjectDocument(d);Path saved=temp.resolve("原工程.json");doc.save(saved);var h=doc.history();
        h.select(Selection.of(REGION));h.selectKeys(Set.of(d.animation(CLIP).timelines().get(0).keys().get(0).id()));
        h.execute("rename",Commands.model(m->ModelEdits.editBone(m,BONE,b->b.withName("changed"))));h.undo();
        var before=h.state();byte[] bytes=Files.readAllBytes(saved);
        assertThrows(ProjectFormatException.class,()->doc.open(bad));unchanged(doc,before,saved,bytes);
        assertThrows(IllegalArgumentException.class,()->h.execute("overflow",Commands.model(m->animated(m,1e200,1e200))));unchanged(doc,before,saved,bytes);
        h.beginPreview("overflow drag");assertThrows(IllegalArgumentException.class,()->h.updatePreview(Commands.model(m->animated(m,1e200,1e200))));assertEquals(before,h.state());h.cancelPreview();unchanged(doc,before,saved,bytes);
    }
    private static void unchanged(ProjectDocument doc,EditorState before,Path saved,byte[] bytes)throws Exception{
        var h=doc.history();assertEquals(before,h.state());assertEquals(0,h.undoCount());assertEquals(1,h.redoCount());assertFalse(h.isDirty());assertFalse(h.isPreviewing());
        assertEquals(saved,doc.path().orElseThrow());assertArrayEquals(bytes,Files.readAllBytes(saved));
    }
    @TestFactory Stream<DynamicTest> subnormalRoundingCannotHideAnAmplifiedMatrix(){
        return Stream.of(1e14,3e15).map(scale->DynamicTest.dynamicTest("subnormal root, grand scale "+scale,()->{
            var bones=List.of(new Bone(id(201),"root",null,new Transform(0,0,45,Double.MIN_VALUE,Double.MIN_VALUE),0),
                    new Bone(id(202),"child",id(201),Transform.IDENTITY,0),new Bone(id(203),"grand",id(202),new Transform(0,0,0,scale,scale),0));
            var d=new DocumentSnapshot(bones,List.of(new Slot(id(204),"slot",id(203),id(205))),
                    List.of(new RegionAttachment(id(205),"tiny",id(204),"tiny.png",new Transform(0,0,45,1e308,1e308),1e-200,1e-200)));
            assertDoesNotThrow(()->{d.evaluateWorld();});
            if(scale==1e14){
                var a=AnimationEdits.put(d,CLIP,id(202),Channel.SCALE,1,List.of(1e308,1e308));var codec=new ProjectCodec();assertEquals(a,codec.decode(codec.encode(a)));
                for(double t:new double[]{0,.5,1,2}){var pose=a.evaluateWorld(AnimationSampler.pose(a,CLIP,t,false));finite(pose.regions().get(id(205)).matrix());pose.regions().get(id(205)).vertices().forEach(RegionMatrixBoundaryTest::finite);}
            }else{
                assertThrows(IllegalArgumentException.class,()->AnimationEdits.put(d,CLIP,id(202),Channel.SCALE,1,List.of(1e308,1e308)));
                var safe=AnimationEdits.put(d,CLIP,id(202),Channel.SCALE,1,List.of(1.,1.));var codec=new ProjectCodec();var mapper=new ObjectMapper();var node=mapper.readTree(codec.encode(safe));
                var values=(ArrayNode)node.path("animations").get(0).path("timelines").get(0).path("keys").get(0).path("values");values.set(0,mapper.getNodeFactory().numberNode(1e308));values.set(1,mapper.getNodeFactory().numberNode(1e308));
                assertThrows(ProjectFormatException.class,()->codec.decode(mapper.writeValueAsBytes(node)));
            }
        }));
    }
}
