package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import static org.junit.jupiter.api.Assertions.*;

/** Golden observations come from an independently generated fixture, not another copy of the proof. */
class ClipFloatCertificateTest {
 @Test void actualRuntimeRegionOffsetsStayInsideLocalProofBoxesIncludingLargeAngles()throws Exception{
  var resource=getClass().getResourceAsStream("/regressions/p6-region-boxes.json");assertNotNull(resource);var data=new ObjectMapper().readTree(resource);int count=0;
  for(var row:data.path("golden")){var t=row.path("transform");var r=new RegionAttachment(EntityId.create(),"region",EntityId.create(),"not-loaded.png",new Transform(t.path("x").asDouble(),t.path("y").asDouble(),t.path("rotation").asDouble(),t.path("scaleX").asDouble(),t.path("scaleY").asDouble()),row.path("width").asDouble(),row.path("height").asDouble());var boxes=ClipFloatCertificate.regionQuad(r);for(int i=0;i<8;i++){double value=(float)row.path("actualOffsets").get(i).asDouble();var interval=i%2==0?boxes.get(i/2).x():boxes.get(i/2).y();assertTrue(value>=interval.lo()&&value<=interval.hi(),"rotation="+r.local().rotation()+" component="+i+" actual="+value+" interval="+interval);count++;}}
  assertEquals(96,count);
 }
}
