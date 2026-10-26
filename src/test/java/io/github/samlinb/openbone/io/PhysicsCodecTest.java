package io.github.samlinb.openbone.io;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;
class PhysicsCodecTest {
 static DocumentSnapshot fixture(int axis){var p=new PhysicsConstraint(id(5),"spring",0,true,id(2),List.of(axis==0?1.:0.,axis==1?1.:0.,axis==2?1.:0.,axis==3?1.:0.,axis==4?1.:0.),5000,List.of(1.,100.,.95,1.,.1,1.,.5));return new DocumentSnapshot(List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0),new Bone(id(2),"spring",id(1),Transform.IDENTITY,100),new Bone(id(3),"child",id(2),new Transform(100,0,0,1,1),20)),List.of(),List.of()).withConstraints(List.of(p));}
 @ParameterizedTest @ValueSource(ints={0,1,2,3,4}) void ownAndRuntimeRoundtripAllPhysicsFields(int axis)throws Exception{var d=fixture(axis);var own=new ProjectCodec();assertEquals(d,own.decode(own.encode(d)));var codec=new SkeletonJsonCodec();var restored=codec.decode(codec.encode(d,Map.of(),""),Path.of("D:/tmp"));var a=(PhysicsConstraint)d.constraints().get(0);var b=(PhysicsConstraint)restored.constraints().get(0);assertEquals(a.influence(),b.influence());assertEquals(a.parameters(),b.parameters());assertEquals(a.limit(),b.limit());assertEquals(100,restored.bones().get(1).length());}
 @Test void fixedFpsReferenceAndGlobalFieldsAreRefusedPrecisely()throws Exception{var json=new com.fasterxml.jackson.databind.ObjectMapper();var codec=new SkeletonJsonCodec();var source=codec.encode(fixture(0),Map.of(),"");for(String field:List.of("fps","windGlobal","skin","referenceScale")){var tree=json.readTree(source);var p=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("physics").get(0);if(field.equals("fps"))p.put(field,30);else if(field.equals("referenceScale"))((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("skeleton")).put(field,50);else p.put(field,true);var error=assertThrows(ProjectFormatException.class,()->codec.decode(json.writeValueAsBytes(tree),Path.of("D:/tmp")));assertTrue(error.getMessage().contains(field),error.getMessage());}}
 @Test void tinyScaleReachAndUnprovedDynamicParentRemainLossless()throws Exception{var d=fixture(3);var tiny=io.github.samlinb.openbone.core.model.ModelEdits.editBone(d,id(2),b->b.withLocal(new Transform(0,0,0,1e-10,1)));assertEquals(tiny,new ProjectCodec().decode(new ProjectCodec().encode(tiny)));assertTrue(assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(tiny)).getMessage().contains("scaleX"));var clip=d.animations().get(0).id();var movingParent=io.github.samlinb.openbone.application.AnimationEdits.put(d,clip,id(1),io.github.samlinb.openbone.core.animation.Channel.SCALE,1,List.of(2.,1.));assertEquals(movingParent,new ProjectCodec().decode(new ProjectCodec().encode(movingParent)));assertTrue(assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(movingParent)).getMessage().contains("parent"));}
}

