package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class AffineTest {
    private static void close(double expected,double actual) { assertEquals(expected,actual,1e-9+1e-10*Math.abs(expected)); }
    private static void close(Affine2 e,Affine2 a) {
        close(e.a(),a.a()); close(e.b(),a.b()); close(e.c(),a.c()); close(e.d(),a.d()); close(e.tx(),a.tx()); close(e.ty(),a.ty());
    }
    @Test void all105ExternalFixtureCasesMatricesAndEndpoints() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("samples/p1/transforms.json")),"external oracle samples not present");
        ObjectMapper mapper=new ObjectMapper(); JsonNode fixture=mapper.readTree(Path.of("samples/p1/transforms.json").toFile());
        double maxError=0; int cases=0,components=0,mirrors=0;
        ArrayNode outputs=mapper.createArrayNode();
        for(JsonNode sample:fixture.get("cases")) {
            List<Bone> bones=new ArrayList<>(); int index=0;
            for(JsonNode row:sample.get("bones")) {
                JsonNode t=row.get("local");
                bones.add(bone(index+1,"bone"+index,index==0?null:id(index),new Transform(t.get(0).asDouble(),t.get(1).asDouble(),t.get(2).asDouble(),t.get(3).asDouble(),t.get(4).asDouble()))); index++;
            }
            var model=new DocumentSnapshot(bones,List.of(),List.of()); var world=model.evaluateWorld();
            ObjectNode output=outputs.addObject(); output.put("name",sample.get("name").asText()); ArrayNode values=output.putArray("worlds");
            double expectedDet=1;
            for(int i=0;i<bones.size();i++) {
                var actual=world.bones().get(id(i+1)); var m=actual.matrix(); var row=sample.get("bones").get(i);
                double[] data={m.a(),m.b(),m.c(),m.d(),m.tx(),m.ty(),actual.tip().x(),actual.tip().y()};
                ArrayNode out=values.addArray();
                for(int k=0;k<8;k++) {
                    double expected=k<6 ? row.get("matrix").get(k).asDouble() : row.get("tip").get(k-6).asDouble();
                    close(expected,data[k]); maxError=Math.max(maxError,Math.abs(expected-data[k])); components++; out.add(data[k]);
                }
                expectedDet*=bones.get(i).local().scaleX()*bones.get(i).local().scaleY();
                if(expectedDet!=0) assertEquals(Math.signum(expectedDet),Math.signum(m.determinant()));
                if(expectedDet<0) mirrors++;
            }
            cases++;
        }
        assertEquals(105,cases); assertEquals(2520,components); assertTrue(mirrors>0);
        ObjectNode report=mapper.createObjectNode(); report.put("cases",cases); report.put("components",components); report.put("maximumAbsoluteError",maxError); report.put("mirroredBones",mirrors); report.set("actual",outputs);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/p2-math-results.json").toFile(),report);
    }
    @Test void compositionKeepsShearAndColumnConvention() {
        Affine2 m=new Transform(0,0,0,2,1).matrix().multiply(new Transform(3,4,45,1,1).matrix());
        close(6,m.tx()); close(4,m.ty());
        assertTrue(Math.abs(m.a()*m.b()+m.c()*m.d())>.5);
        assertThrows(IllegalArgumentException.class,m::toTrsOrThrow);
        var p=new Transform(10,20,90,2,3).matrix().apply(1,0); close(10,p.x()); close(22,p.y());
    }
    @ParameterizedTest @ValueSource(doubles={0,1e-14,-1e-14})
    void zeroAndNearZeroAreDrawableButNotInvertible(double scale) {
        var bone=bone(1,"root",null,new Transform(1,2,30,scale,1));
        var d=new DocumentSnapshot(List.of(bone),List.of(),List.of());
        assertTrue(d.evaluateWorld().bones().get(id(1)).matrix().tryInverse().isEmpty());
        assertTrue(Double.isFinite(d.evaluateWorld().bones().get(id(1)).tip().x()));
        assertThrows(IllegalArgumentException.class,()->bone.local().matrix().inverseOrThrow());
    }
    @ParameterizedTest @ValueSource(doubles={1,1e6,1e150,1e200})
    void inverseClassificationIsScaleRelatedWithoutOverflow(double scale) {
        assertTrue(new Affine2(scale,0,0,scale*1e-14,0,0).tryInverse().isEmpty());
        var m=new Affine2(scale,0,0,-scale,0,0); close(Affine2.IDENTITY,m.multiply(m.inverseOrThrow()));
    }
    @Test void thresholdAndUnrepresentableInverse() {
        assertTrue(new Affine2(1,0,0,1e-12,0,0).tryInverse().isEmpty());
        assertTrue(new Affine2(1,0,0,1e-10,0,0).tryInverse().isPresent());
        assertTrue(new Affine2(1e-7,0,0,1e-7,0,0).tryInverse().isEmpty());
        assertTrue(new Affine2(1e-5,0,0,1e-5,0,0).tryInverse().isPresent());
        assertTrue(new Affine2(1e-5,0,0,1e-5,Double.MAX_VALUE,0).tryInverse().isEmpty());
    }
    @Test void randomizedInverseRoundTrips() {
        Random random=new Random(731942);
        for(int i=0;i<500;i++) {
            Transform t=new Transform(random.nextDouble()*200-100,random.nextDouble()*200-100,random.nextDouble()*1440-720,
                    (random.nextBoolean()?1:-1)*(.2+random.nextDouble()*3),(.2+random.nextDouble()*3));
            Affine2 m=t.matrix(),inverse=m.inverseOrThrow(); close(Affine2.IDENTITY,m.multiply(inverse));
            var p=m.apply(13,-9); var original=inverse.apply(p.x(),p.y()); close(13,original.x()); close(-9,original.y());
            close(m,m.toTrsOrThrow().matrix());
        }
    }
    @Test void explicitReparentPoliciesAndSingularRefusal() {
        var d=hierarchy(); var local=ModelEdits.reparent(d,id(3),id(4),ModelEdits.ReparentPolicy.KEEP_LOCAL);
        assertEquals(d.bone(id(3)).local(),local.bone(id(3)).local());
        close(d.evaluateWorld().bones().get(id(3)).matrix(),ModelEdits.reparent(d,id(3),id(4),ModelEdits.ReparentPolicy.KEEP_WORLD).evaluateWorld().bones().get(id(3)).matrix()); // shear is preserved
        var simple=new DocumentSnapshot(List.of(bone(1,"a",null,new Transform(4,5,30,-2,2)),bone(2,"b",null,new Transform(2,1,60,3,3)),bone(3,"c",id(1),new Transform(5,2,45,1,2))),List.of(),List.of());
        var preserved=ModelEdits.reparent(simple,id(3),id(2),ModelEdits.ReparentPolicy.KEEP_WORLD);
        close(simple.evaluateWorld().bones().get(id(3)).matrix(),preserved.evaluateWorld().bones().get(id(3)).matrix());
        var singular=ModelEdits.editBone(simple,id(2),b->b.withLocal(new Transform(0,0,0,0,1)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.reparent(singular,id(3),id(2),ModelEdits.ReparentPolicy.KEEP_WORLD));
        assertDoesNotThrow(()->ModelEdits.reparent(singular,id(3),id(2),ModelEdits.ReparentPolicy.KEEP_LOCAL));
        for(Transform t:List.of(new Transform(1,2,30,0,0),new Transform(1,2,60,0,-2),new Transform(1,2,75,-1,0))) close(t.matrix(),t.matrix().toTrsOrThrow().matrix());
    }
}
