package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderedConstraintImportTest {
    @TempDir Path temp;

    private TransformConstraint constraint(int id,String name,int order,int target) {
        return new TransformConstraint(id(id),name,order,true,List.of(id(2)),id(target),false,false,
                List.of(0.,.5,0.,0.,0.,0.),Collections.nCopies(6,0.));
    }

    private DocumentSnapshot sample(boolean ordered,boolean reversed) {
        var bones=List.of(bone(1,"root",null,Transform.IDENTITY),bone(2,"driven",id(1),Transform.IDENTITY),
                bone(3,"target-a",id(1),new Transform(10,0,0,1,1)),
                bone(4,"target-b",id(1),new Transform(20,0,0,1,1)),
                bone(5,"child",id(2),new Transform(2,0,0,1,1)));
        var a=constraint(101,"first",reversed?1:0,3);
        var b=constraint(102,"second",reversed?0:1,4);
        var resources=Resources.EMPTY.withMetadata(SpineMetadata.EMPTY.withOrderedConstraintWrites(ordered));
        return new DocumentSnapshot(bones,List.of(),List.of(),List.of(),List.of(a,b),resources);
    }

    @Test void orderedWorldMixesUseBothPriorOutputsAndRebuildChildren() throws Exception {
        assertThrows(IllegalArgumentException.class,()->sample(false,false));
        var model=sample(true,false);
        assertEquals(12.5,model.evaluateWorld().bones().get(id(2)).origin().x());
        assertEquals(14.5,model.evaluateWorld().bones().get(id(5)).origin().x());
        assertEquals(10.,sample(true,true).evaluateWorld().bones().get(id(2)).origin().x());
        assertEquals(2,model.constraints().size());
        var codec=new ProjectCodec();byte[] data=codec.encode(model);
        assertEquals(14,new ObjectMapper().readTree(data).path("schemaVersion").asInt());
        var reopened=codec.decode(data);
        assertEquals(model,reopened);assertEquals(model.evaluateWorld(),reopened.evaluateWorld());
        assertTrue(model.resources().metadata().retain(Set.of()).orderedConstraintWrites());
    }

    @Test void sourceImportExplicitlyMarksWorldWriterOverlapWithoutDroppingEitherConstraint() throws Exception {
        var source=temp.resolve("ordered.json");
        Files.writeString(source,"""
            {"skeleton":{"spine":"4.0.64"},"bones":[{"name":"root"},{"name":"driven","parent":"root"},
             {"name":"target-a","parent":"root","x":10},{"name":"target-b","parent":"root","x":20}],
             "slots":[],"skins":[{"name":"default","attachments":{}}],
             "transform":[{"name":"first","order":0,"bones":["driven"],"target":"target-a","mixRotate":0,"mixX":0.5,"mixY":0,"mixScaleX":0,"mixScaleY":0,"mixShearY":0},
                          {"name":"second","order":1,"bones":["driven"],"target":"target-b","mixRotate":0,"mixX":0.5,"mixY":0,"mixScaleX":0,"mixScaleY":0,"mixShearY":0}],
             "animations":{"idle":{}}}
            """);
        var model=new SkeletonJsonCodec().read(source);
        assertTrue(model.resources().metadata().orderedConstraintWrites());
        assertEquals(List.of("first","second"),model.constraints().stream().map(Constraint::name).toList());
        var driven=model.bones().stream().filter(b->b.name().equals("driven")).findFirst().orElseThrow();
        assertEquals(12.5,model.evaluateWorld().bones().get(driven.id()).origin().x());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="openbone.verifyNinja",matches="true")
    void actualSnakeKeepsSixteenOrderedConstraintsAndAllSixtySixClips() throws Exception {
        Path root=Path.of(System.getProperty("openbone.ninjaOutput","E:/resource/ninja3-assets-047097ea/OpenBone项目与预览"));
        Path source=root.resolve("02_Boss/snake__751d5bc9a9/_导入源/snake.skel");
        var model=new SkeletonJsonCodec().read(source);
        assertEquals(16,model.constraints().size());assertEquals(66,model.animations().size());
        assertTrue(model.resources().metadata().orderedConstraintWrites());
        for(var clip:model.animations()) {
            var pose=io.github.samlinb.openbone.core.animation.AnimationSampler.world(model,clip.id(),clip.duration()*.5,false);
            assertEquals(model.bones().size(),pose.bones().size());
        }
        var codec=new ProjectCodec();assertEquals(model,codec.decode(codec.encode(model)));
    }
}
