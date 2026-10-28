package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.viewport.VisibleGeometry;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Produces an actual exported package and desktop oracle for the independent web runtime. */
@EnabledIfSystemProperty(named = "openbone.webRuntime", matches = "true")
class WebRuntimeFixtureTest {
    @TempDir Path temp;

    @Test void exportNumericFixture() throws Exception {
        var pixels = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<32;y++) for (int x=0;x<32;x++)
            pixels.setRGB(x,y,x<16 ? (y<16 ? 0xff49c5eb : 0xff1866a0) : (y<16 ? 0xfff9c04d : 0xffe06045));
        ImageIO.write(pixels,"png",temp.resolve("quad.png").toFile());
        Path source = temp.resolve("skeleton.json");
        Files.writeString(source,"""
            {"skeleton":{"spine":"4.2.22"},
             "bones":[{"name":"root","x":10,"y":20,"rotation":15},
                      {"name":"child","parent":"root","x":120,"rotation":-20,"scaleX":1.2,"scaleY":0.8}],
             "slots":[{"name":"body","bone":"root","attachment":"quad"},
                      {"name":"mesh","bone":"child","attachment":"weighted","color":"c0ffffff","blend":"additive"}],
             "skins":[{"name":"default","attachments":{
               "body":{"quad":{"type":"region","path":"quad","width":96,"height":64,"x":5,"rotation":12}},
               "mesh":{"weighted":{"type":"mesh","path":"quad","hull":3,
                  "uvs":[0,0,1,0,0.5,1],"triangles":[0,1,2],
                  "vertices":[2,0,80,20,0.25,1,-20,0,0.75,1,1,30,0,1,1,1,0,40,1]}}}}],
             "animations":{"motion":{
               "bones":{"root":{"rotate":[{"value":0,"curve":[0.25,10,0.75,150]},{"time":1,"value":180}]},
                 "child":{"translatex":[{"value":0},{"time":1,"value":40}],
                   "scaley":[{"value":1,"curve":"stepped"},{"time":1,"value":-1}]}},
               "slots":{"mesh":{"alpha":[{"value":1},{"time":1,"value":0.4}]}},
               "attachments":{"default":{"mesh":{"weighted":{"deform":[{},
                 {"time":1,"offset":2,"vertices":[8,6,0,0,-4,3]}]}}}}}}}
            """);
        var document = new SkeletonJsonCodec().read(source);
        Path output = Path.of("target/web-runtime-fixture").toAbsolutePath();
        new ExportPackage().publish(output,document,temp,"4.2.22",ExportPackage.Format.JSON);
        var frames = new ArrayList<Map<String,Object>>();
        var animation = document.animations().get(0);
        for (double time : new double[]{0,0.1,0.25,0.5,0.75,1}) {
            var pose = document.evaluateAnimatedWorld(animation.id(),time,false);
            var matrices = document.bones().stream().map(b -> {
                var m = pose.bones().get(b.id()).matrix();
                return List.of(m.a(),m.b(),m.c(),m.d(),m.tx(),m.ty());
            }).toList();
            var draws = VisibleGeometry.evaluate(document,pose).stream().map(draw -> {
                var g=draw.geometry(); var c=draw.color();
                return Map.of("vertices",g.vertices().stream().map(p -> List.of(p.x(),p.y())).toList(),
                    "uvs",g.uvs().stream().map(p -> List.of(p.x(),p.y())).toList(),
                    "color",List.of(c.r(),c.g(),c.b(),c.a()));
            }).toList();
            assertEquals(2,draws.size());
            frames.add(Map.of("animation",animation.name(),"time",time,"bones",matrices,"draws",draws));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("expected.json").toFile(),frames);
        var paths=new HashMap<io.github.samlinb.openbone.core.model.EntityId,String>();document.attachments().forEach(a->paths.put(a.id(),"quad"));
        for(String version:List.of("3.8.99","4.0.64","4.1.24","4.2.22","4.3.26")){
            Files.write(output.resolve(version+".json"),new SkeletonJsonCodec().encode(document,paths,"",version));
            Files.write(output.resolve(version+".skel"),SpineBinaryWriter.encode(document,paths,"",version));
        }
        Files.copy(temp.resolve("quad.png"),output.resolve("quad.png"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(output.resolve("input.atlas"),"quad.png\nsize:32,32\nfilter:Nearest,Nearest\nquad\nbounds:0,0,32,32\n");
        assertTrue(Files.isRegularFile(output.resolve("current.json")));
    }
}
