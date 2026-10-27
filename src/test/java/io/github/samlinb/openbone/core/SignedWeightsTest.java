package io.github.samlinb.openbone.core;

import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignedWeightsTest {
    @Test void signedCoefficientsArePreservedInGeometryAndProjectRoundtrip() throws Exception {
        var root=EntityId.create();var other=EntityId.create();var slot=EntityId.create();var id=EntityId.create();
        var vertices=List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(0,1));
        var weights=vertices.stream().map(p->List.of(new Influence(root,p,-.25),new Influence(other,p,1.25))).toList();
        var mesh=new MeshAttachment(id,"mesh",slot,"test.png",vertices,vertices,3,List.of(0,1,2),weights);
        var d=new DocumentSnapshot(List.of(new Bone(root,"root",null,Transform.IDENTITY,0),
                new Bone(other,"other",root,new Transform(10,10,0,1,1),0)),List.of(new Slot(slot,"slot",root,id)),List.of(mesh),List.of());
        var pose=d.evaluateWorld().meshes().get(id).vertices();
        assertEquals(new Vec2(12.5,12.5),pose.get(0)); assertEquals(new Vec2(13.5,12.5),pose.get(1));
        var codec=new ProjectCodec();byte[] encoded=codec.encode(d);
        assertEquals(12,new com.fasterxml.jackson.databind.ObjectMapper().readTree(encoded).path("schemaVersion").asInt()); assertEquals(d,codec.decode(encoded));
        assertDoesNotThrow(()->RuntimeRange.validate(d));
        var runtime=new SkeletonJsonCodec().encode(d,Map.of(id,"test"),"");
        assertTrue(new String(runtime,java.nio.charset.StandardCharsets.UTF_8).contains("-0.25"));
        assertThrows(IllegalArgumentException.class,()->Influence.normalize(List.of(new Influence(root,new Vec2(0,0),-1),new Influence(other,new Vec2(0,0),1))));
    }
}
