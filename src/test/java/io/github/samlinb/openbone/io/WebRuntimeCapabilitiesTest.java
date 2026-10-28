package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.animation.PhysicsPlayer;
import io.github.samlinb.openbone.viewport.VisibleGeometry;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real importer/exporter/solver oracles. No official runtime or authored expected poses. */
@EnabledIfSystemProperty(named="openbone.webRuntime",matches="true")
class WebRuntimeCapabilitiesTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<Map<String,Object>> cases=new ArrayList<>();
    private ObjectNode base() throws Exception {
        return (ObjectNode)mapper.readTree("""
          {"skeleton":{"spine":"4.2.22"},"bones":[
            {"name":"root"},{"name":"parent","parent":"root","length":40},
            {"name":"child","parent":"parent","x":40,"length":35},
            {"name":"target","parent":"root","x":45,"y":35}],
          "slots":[{"name":"body","bone":"child","attachment":"quad"}],
          "skins":[{"name":"default","attachments":{"body":{"quad":{
            "type":"region","path":"quad","width":30,"height":20}}}}],
          "animations":{"motion":{"bones":{"target":{"translate":[
            {"x":0,"y":0},{"time":1,"x":20,"y":-15}]}}}}}
          """);
    }
    private void write(String name,ObjectNode json,boolean export) throws Exception {
        Path source=temp.resolve(name+".json");Files.writeString(source,json.toString());
        var doc=new SkeletonJsonCodec().read(source);
        Path folder=Path.of("target/web-runtime-capabilities",name).toAbsolutePath();Files.createDirectories(folder);
        if(export)new ExportPackage().publish(folder,doc,temp,"4.2.22",ExportPackage.Format.JSON);
        // Also retain importer input: covers Java model domains outside conservative export certificates.
        Files.copy(source,folder.resolve("input.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(temp.resolve("quad.png"),folder.resolve("quad.png"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve("input.atlas"),"quad.png\nsize:32,32\nfilter:Nearest,Nearest\nquad\nbounds:0,0,32,32\n");
        var frames=new ArrayList<Map<String,Object>>();var clip=doc.animations().get(0);var player=new PhysicsPlayer();
        // Deliberately non-monotonic, repeated and immediately-before-tick seeks.
        for(var skin:doc.skins())for(double time:new double[]{0,Math.nextDown(1./60),1./60,.1,.25,.5,1,.75,.1,.5})for(boolean loop:new boolean[]{false,true}) {
            var selected=doc.withResources(doc.resources().skin(skin.name()));var pose=player.seek(selected,clip.id(),time,loop);
            var matrices=doc.bones().stream().map(b->{var m=pose.bones().get(b.id()).matrix();return List.of(m.a(),m.b(),m.c(),m.d(),m.tx(),m.ty());}).toList();
            var draws=VisibleGeometry.evaluate(selected,pose).stream().map(draw->{var g=draw.geometry();var c=draw.color();return Map.of(
                "vertices",g.vertices().stream().map(p->List.of(p.x(),p.y())).toList(),"uvs",g.uvs().stream().map(p->List.of(p.x(),p.y())).toList(),
                "triangles",g.triangles(),"color",List.of(c.r(),c.g(),c.b(),c.a()));}).toList();
            frames.add(Map.of("time",time,"loop",loop,"skin",skin.name(),"bones",matrices,"boneNames",doc.bones().stream().map(b->b.name()).toList(),"draws",draws));
        }
        mapper.writeValue(folder.resolve("expected.json").toFile(),frames);cases.add(Map.of("name",name,"export",export));
        if(export){
            var paths=new HashMap<io.github.samlinb.openbone.core.model.EntityId,String>();
            for(var attachment:doc.attachments())if(attachment instanceof io.github.samlinb.openbone.core.model.RegionAttachment||attachment instanceof io.github.samlinb.openbone.core.model.MeshAttachment)paths.put(attachment.id(),"quad");
            var versions=new ArrayList<String>();
            for(String version:List.of("3.8.99","4.0.64","4.1.24","4.2.22","4.3.26")){
                // These downgrades are deliberately outside Java's export contract.
                if(version.startsWith("3.8")&&(name.startsWith("physics-")||name.contains("proportional")||name.startsWith("transform-")))continue;
                if(name.startsWith("physics-")&&!version.startsWith("4.2")&&!version.startsWith("4.3"))continue;
                Files.write(folder.resolve(version+".json"),new SkeletonJsonCodec().encode(doc,paths,"",version));
                Files.write(folder.resolve(version+".skel"),SpineBinaryWriter.encode(doc,paths,"",version));versions.add(version);
            }
            mapper.writeValue(folder.resolve("versions.json").toFile(),versions);
        }
    }
    @Test void generateCapabilities() throws Exception {
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)image.setRGB(x,y,0xff49c5eb);ImageIO.write(image,"png",temp.resolve("quad.png").toFile());
        for(String mode:List.of("normal","onlyTranslation","noRotationOrReflection","noScale","noScaleOrReflection")){
            var n=base();((ObjectNode)n.withArray("bones").get(1)).put("rotation",32).put("scaleX",-1.3).put("scaleY",.7).put("shearY",14);
            ((ObjectNode)n.withArray("bones").get(2)).put("inherit",mode).put("rotation",21);write("inherit-"+mode,n,true);
        }
        for(int count:new int[]{1,2})for(boolean reflected:new boolean[]{false,true}){
            var n=base();var c=n.putArray("ik").addObject().put("name","aim").put("target","target").put("mix",.65).put("softness",count==2?5:0).put("stretch",true);
            c.putArray("bones").add("parent");if(count==2)c.withArray("bones").add("child");
            if(reflected)((ObjectNode)n.withArray("bones").get(1)).put("scaleX",-1.2).put("scaleY",.9);
            ((ObjectNode)n.path("animations").path("motion")).remove("bones");
            var keys=((ObjectNode)n.path("animations").path("motion")).putObject("ik").putArray("aim");keys.addObject().put("mix",.2).put("softness",count==2?2:0);keys.addObject().put("time",1).put("mix",.8).put("softness",count==2?4:0);
            ((ObjectNode)keys.get(0)).put("curve","stepped");write("ik-"+count+"-"+reflected,n,true);
        }
        for(boolean local:new boolean[]{false,true})for(boolean relative:new boolean[]{false,true}){
            var n=base();var c=n.putArray("transform").addObject().put("name","follow").put("target","target").put("local",local).put("relative",relative)
              .put("mixRotate",.5).put("mixX",.5).put("mixY",.5).put("mixScaleX",.5).put("mixScaleY",.5).put("mixShearY",.5).put("rotation",5).put("x",3).put("y",4).put("scaleX",.2).put("scaleY",.4).put("shearY",15);
            c.putArray("bones").add("parent");((ObjectNode)n.withArray("bones").get(1)).put("x",1).put("y",2).put("rotation",10).put("scaleX",2).put("scaleY",3);
            ((ObjectNode)n.withArray("bones").get(3)).put("x",20).put("y",30).put("rotation",40).put("scaleX",4).put("scaleY",5);
            write("transform-"+local+"-"+relative,n,true);
        }
        for(String rotate:List.of("tangent","chain","chainScale"))for(String spacing:List.of("length","fixed","percent","proportional")){
            var n=base();((ObjectNode)n.withArray("bones").get(2)).put("parent","root");n.withArray("slots").addObject().put("name","pathSlot").put("bone","root").put("attachment","curve");
            var p=((ObjectNode)n.path("skins").get(0).path("attachments")).putObject("pathSlot").putObject("curve");p.put("type","path").put("vertexCount",6).put("closed",false).put("constantSpeed",true);
            p.putArray("vertices").add(0).add(0).add(0).add(0).add(30).add(70).add(70).add(-20).add(100).add(30).add(100).add(30);p.putArray("lengths").add(120).add(0);
            var c=n.putArray("path").addObject().put("name","followPath").put("target","pathSlot").put("rotateMode",rotate).put("spacingMode",spacing).put("position",.2).put("spacing",spacing.equals("percent")?.15:12);
            var motion=(ObjectNode)n.path("animations").path("motion");motion.remove("bones");motion.putObject("slots").putObject("body").putArray("alpha").addObject().put("time",1).put("value",1);
            c.putArray("bones").add("parent").add("child");write("path-"+rotate+"-"+spacing,n,true);
        }
        for(String influence:List.of("x","y","rotate","scaleX","shearX")){
            var n=base();var c=n.putArray("physics").addObject().put("name","spring").put("bone","parent").put(influence,.7).put("wind",.5).put("gravity",.3).put("damping",.9).put("strength",60);
            var motion=(ObjectNode)n.path("animations").path("motion");motion.withObject("/bones").putObject("parent").putArray("translatex").addObject().put("value",0);((ArrayNode)motion.path("bones").path("parent").path("translatex")).addObject().put("time",1).put("value",20);
            var k=motion.putObject("physics").putObject("spring").putArray("mix");k.addObject().put("value",.4).put("curve","stepped");k.addObject().put("time",.5).put("value",0).put("curve","stepped");k.addObject().put("time",.75).put("value",1);
            write("physics-"+influence,n,true);
        }
        for(boolean concave:new boolean[]{false,true}){
            var n=base();var slots=n.putArray("slots");slots.addObject().put("name","clip").put("bone","root").put("attachment","mask");slots.addObject().put("name","body").put("bone","root").put("attachment","quad");slots.addObject().put("name","after").put("bone","root").put("attachment","quad");
            var attachments=(ObjectNode)n.path("skins").get(0).path("attachments");attachments.putObject("after").set("quad",attachments.path("body").path("quad").deepCopy());
            var c=attachments.putObject("clip").putObject("mask").put("type","clipping").put("end","body").put("vertexCount",concave?5:4);
            var v=c.putArray("vertices");for(double d:concave?new double[]{-12,-8,12,-8,0,0,12,8,-12,8}:new double[]{-12,-8,12,-8,12,8,-12,8})v.add(d);
            write("clip-"+concave,n,true);
        }
        var n=base();((ObjectNode)n.withArray("bones").get(2)).put("skin",true);n.withArray("skins").addObject().put("name","enabled").putArray("bones").add("child");write("skin",n,true);
        // 4.1 import exercises Java's model beyond conservative 4.2 export certificates.
        for(boolean closed:new boolean[]{false,true})for(boolean constant:new boolean[]{false,true}){
            n=base();((ObjectNode)n.path("skeleton")).put("spine","4.1.24");((ObjectNode)n.withArray("bones").get(2)).put("parent","root");
            n.withArray("slots").addObject().put("name","pathSlot").put("bone","root").put("attachment","curve");
            var p=((ObjectNode)n.path("skins").get(0).path("attachments")).putObject("pathSlot").putObject("curve").put("type","path").put("vertexCount",6).put("closed",closed).put("constantSpeed",constant);
            var v=p.putArray("vertices");for(double[] xy:new double[][]{{-20,0},{0,0},{30,70},{70,-20},{100,30},{120,30}})v.add(1).add(0).add(xy[0]).add(xy[1]).add(1);p.putArray("lengths").add(120).add(240);
            var c=n.putArray("path").addObject().put("name","follow").put("target","pathSlot").put("position",.3).put("spacing",20).put("rotateMode","chain");c.putArray("bones").add("parent").add("child");
            var motion=(ObjectNode)n.path("animations").path("motion");var deform=motion.putObject("attachments").putObject("default").putObject("pathSlot").putObject("curve").putArray("deform");deform.addObject();deform.addObject().put("time",1).put("offset",4).putArray("vertices").add(10).add(-20).add(20).add(10);
            write("weighted-path-"+closed+"-"+constant,n,false);
        }
        n=base();((ObjectNode)n.path("skeleton")).put("spine","4.1.24");
        var slots=n.putArray("slots");slots.addObject().put("name","clip").put("bone","root").put("attachment","mask");slots.addObject().put("name","body").put("bone","root").put("attachment","quad");
        var c=((ObjectNode)n.path("skins").get(0).path("attachments")).putObject("clip").putObject("mask").put("type","clipping").put("vertexCount",4).put("end","body");
        var v=c.putArray("vertices");for(double[] xy:new double[][]{{-12,-8},{12,-8},{12,8},{-12,8}})v.add(1).add(0).add(xy[0]).add(xy[1]).add(1);
        var deform=((ObjectNode)n.path("animations").path("motion")).putObject("attachments").putObject("default").putObject("clip").putObject("mask").putArray("deform");deform.addObject();deform.addObject().put("time",1).putArray("vertices").add(2).add(0).add(-2).add(0);
        write("clip-weighted-deform",n,false);
        Path out=Path.of("target/web-runtime-capabilities");mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("cases.json").toFile(),cases);assertEquals(38,cases.size());
    }
}
