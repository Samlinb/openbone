package io.github.samlinb.openbone.io;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.viewport.*;
import static org.junit.jupiter.api.Assertions.*;

class SlotColorTimelineTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    byte[] source() throws Exception { try(var in=getClass().getResourceAsStream("/spine38/dandao_1.json")) { return in.readAllBytes(); } }
    DocumentSnapshot sample() throws Exception { return new SkeletonJsonCodec().decode(source(),temp); }
    static Slot slot(DocumentSnapshot d,String name) { return d.slots().stream().filter(s->s.name().equals(name)).findFirst().orElseThrow(); }
    static Rgba color(DocumentSnapshot d,String name,double time,boolean loop) {
        return d.resolvedSlots(d.animations().get(0).id(),time,loop).stream().filter(s->s.name().equals(name)).findFirst().orElseThrow().color();
    }
    static void same(Rgba a,Rgba b) { for(int c=0;c<4;c++) assertEquals(a.values().get(c),b.values().get(c),1e-10); }

    @Test void dandaoColorsMatchEveryOriginalLinearAndSteppedSegmentIncludingSeekingAndLooping() throws Exception {
        var d=sample(); var rows=json.readTree(source()).path("animations").path("attack_1").path("slots");
        assertEquals(14,d.animations().get(0).discrete().colors().size());
        for(var it=rows.fields();it.hasNext();) {
            var row=it.next(); var keys=row.getValue().get("color"); if(keys==null) continue;
            var times=new TreeSet<Double>(); times.add(0.); times.add(.3333);
            for(var k:keys) { double t=k.path("time").asDouble(); times.add(t); times.add(Math.max(0,t-1e-7)); times.add(t+1e-7); }
            for(int i=0;i<40;i++) times.add(i/120.);
            for(double t:times) {
                Rgba expected=Rgba.WHITE;
                for(int i=0;i<keys.size();i++) {
                    var k=keys.get(i); double from=k.path("time").asDouble(); if(t<from) break;
                    expected=Rgba.parse(k.path("color").asText());
                    if(i+1<keys.size() && t<keys.get(i+1).path("time").asDouble() && !k.path("curve").asText().equals("stepped")) {
                        var to=Rgba.parse(keys.get(i+1).path("color").asText());
                        double p=(t-from)/(keys.get(i+1).path("time").asDouble()-from);
                        expected=new Rgba(expected.r()+(to.r()-expected.r())*p,expected.g()+(to.g()-expected.g())*p,
                                expected.b()+(to.b()-expected.b())*p,expected.a()+(to.a()-expected.a())*p);
                    }
                }
                same(expected,color(d,row.getKey(),t,false));
            }
        }
        assertEquals(.5,color(d,"attack_hit_huaban1",.08335,false).a(),1e-10);
        assertEquals(1,color(d,"attack_hit_huaban17",.2,false).a());
        assertEquals(0,color(d,"attack_hit_huaban17",.2667,false).a());
        same(color(d,"attack_hit_huaban1",.08335,false),color(d,"attack_hit_huaban1",d.animations().get(0).duration()+.08335,true));
        // Sampling after seeking backwards always starts from setup.
        color(d,"attack_hit_huaban1",.3,false); same(Rgba.WHITE,color(d,"attack_hit_huaban1",0,false));
        var draw=VisibleGeometry.evaluate(d,AnimationSampler.world(d,d.animations().get(0).id(),.08335,false)).stream()
                .filter(v->v.slotId().equals(slot(d,"attack_hit_huaban1").id())).findFirst().orElseThrow();
        assertEquals(.5,draw.color().a(),1e-10);
    }

    @Test void projectSchema7PreservesAllColorIdsAndCurvesAndEditsDoNotDropThem() throws Exception {
        var imported=sample(); var d=imported.withResources(imported.resources().withMetadata(SpineMetadata.EMPTY)); var codec=new ProjectCodec(); var bytes=codec.encode(d);
        assertEquals(7,json.readTree(bytes).path("schemaVersion").asInt()); assertEquals(d,codec.decode(bytes));
        var clip=d.animations().get(0); var target=slot(d,"attack_hit_huaban1");
        var edited=ExtendedEdits.attachment(d,clip.id(),target.id(),0,null);
        assertEquals(clip.discrete().colors(),edited.animations().get(0).discrete().colors());
        var added=ModelEdits.addSlot(d,new Slot(EntityId.create(),"extra",d.bones().get(0).id(),null),0);
        assertEquals(clip.discrete().colors(),added.animations().get(0).discrete().colors());
        var removed=ModelEdits.delete(d,target.id());
        assertEquals(13,removed.animations().get(0).discrete().colors().size());
        var duplicate=clip.discrete().duplicate(); assertEquals(14,duplicate.colors().size());
        assertNotEquals(clip.discrete().colors().get(0).keys().get(0).id(),duplicate.colors().get(0).keys().get(0).id());
    }

    @Test void dandaoExportRoundtripsColorPlaybackAtBothVersions() throws Exception {
        var d=sample(); var paths=new HashMap<EntityId,String>(); d.attachments().forEach(a->paths.put(a.id(),"page"));
        try(var in=getClass().getResourceAsStream("/spine38/dandao_1.png")) { Files.copy(in,temp.resolve("palette.png")); }
        Files.write(temp.resolve("page.png"),RuntimePng.readCompatible(temp.resolve("palette.png")));
        for(String version:List.of(SkeletonJsonCodec.VERSION_38,SkeletonJsonCodec.VERSION)) {
            var codec=new SkeletonJsonCodec(); byte[] bytes=codec.encode(d,paths,"",version);
            var tree=json.readTree(bytes).path("animations").path("attack_1").path("slots").path("attack_hit_huaban1");
            assertTrue(tree.has(version.equals(SkeletonJsonCodec.VERSION_38)?"color":"rgba"));
            var after=codec.decode(bytes,temp); assertEquals(14,after.animations().get(0).discrete().colors().size());
            for(var slot:d.slots()) for(double t:new double[]{0,.0667,.08335,.1,.16,.2333,.25,.2667,.3})
                same(color(d,slot.name(),t,false),color(after,slot.name(),t,false));
        }
    }

    @Test void alphaAndRgbTracksAreIndependentAndColorOnlyDurationLoopsCorrectly() throws Exception {
        String source="""
            {"skeleton":{"spine":"4.2.22"},"bones":[{"name":"root"}],"slots":[{"name":"s","bone":"root","color":"80402080"}],
            "skins":[{"name":"default","attachments":{}}],"animations":{"a":{"slots":{"s":{
            "rgb":[{"time":1,"color":"ff0000"},{"time":2,"color":"00ff00"}],
            "alpha":[{"value":0},{"time":2,"value":1}]}}}}}
            """;
        var codec=new SkeletonJsonCodec(); var d=codec.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),temp);
        assertEquals(2,d.animations().get(0).duration());
        same(new Rgba(128/255.,64/255.,32/255.,.25),color(d,"s",.5,false));
        same(new Rgba(.5,.5,0,.75),color(d,"s",1.5,false));
        same(color(d,"s",.5,false),color(d,"s",2.5,true));
        assertEquals(d,new ProjectCodec().decode(new ProjectCodec().encode(d)));
        var after=codec.decode(codec.encode(d,Map.of(),""),temp); same(color(d,"s",1.5,false),color(after,"s",1.5,false));
        assertThrows(ProjectFormatException.class,()->codec.encode(d,Map.of(),"",SkeletonJsonCodec.VERSION_38));
    }

    @Test void relative38BezierColorAndAlphaChannelsRoundtrip() throws Exception {
        String source="""
            {"skeleton":{"spine":"3.8.75"},"bones":[{"name":"root"}],"slots":[{"name":"s","bone":"root"}],
            "skins":[{"name":"default","attachments":{}}],"animations":{"a":{"slots":{"s":{"color":[
            {"color":"ff000000","curve":0.2,"c2":0.1,"c3":0.8,"c4":0.9},{"time":1,"color":"00ffffff"}]}}}}}
            """;
        var codec=new SkeletonJsonCodec(); var d=codec.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),temp);
        var curve=new Curve(Curve.Kind.BEZIER,.2,.1,.8,.9); double p=curve.progress(.25);
        same(new Rgba(1-p,p,p,p),color(d,"s",.25,false));
        for(String version:List.of(SkeletonJsonCodec.VERSION_38,SkeletonJsonCodec.VERSION)) {
            var after=codec.decode(codec.encode(d,Map.of(),"",version),temp);
            for(double t:new double[]{0,.1,.25,.5,.8,1}) same(color(d,"s",t,false),color(after,"s",t,false));
        }
    }

    @Test void malformedLegacyColorKeyIsFilteredWithoutLosingGoodColorKeys() throws Exception {
        var root=(ObjectNode)json.readTree(source());
        var keys=(ArrayNode)root.path("animations").path("attack_1").path("slots").path("attack_hit_huaban1").path("color");
        keys.addObject().put("time",.08).put("color","broken");
        var codec=new SkeletonJsonCodec(); var d=codec.decode(json.writeValueAsBytes(root),temp);
        assertEquals(14,d.animations().get(0).discrete().colors().size());
        assertTrue(codec.warnings().stream().anyMatch(w->w.contains("invalid color key")));
        assertEquals(.5,color(d,"attack_hit_huaban1",.08335,false).a(),1e-10);
    }
}
