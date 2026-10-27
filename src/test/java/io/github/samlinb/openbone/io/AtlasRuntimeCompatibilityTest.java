package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.VisibleGeometry;
import static org.junit.jupiter.api.Assertions.*;

class AtlasRuntimeCompatibilityTest {
    @TempDir Path temp;

    private Path source(boolean pma, String scale, int pngWidth, int pngHeight) throws Exception {
        Path skeleton=temp.resolve("skeleton.json");
        Files.writeString(skeleton,"""
                {"skeleton":{"spine":"4.2.22"},"bones":[{"name":"root"}],
                 "slots":[{"name":"slot","bone":"root","attachment":"region"}],
                 "skins":[{"name":"default","attachments":{"slot":{"region":{"type":"region","width":4,"height":4}}}}],
                 "animations":{}}
                """);
        Files.writeString(temp.resolve("skeleton.atlas"),"page.png\nsize:4,4\nformat:RGBA8888\nfilter:Nearest,Nearest\npma:"+pma+"\nscale:"+scale+"\nregion\nbounds:0,0,4,4\n");
        var pixels=new BufferedImage(pngWidth,pngHeight,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<pngHeight;y++) for(int x=0;x<pngWidth;x++) pixels.setRGB(x,y,0x80643219);
        ImageIO.write(pixels,"png",temp.resolve("page.png").toFile());
        return skeleton;
    }

    @Test void packerScaleIsConsumedWithoutRescalingPackedCoordinates() throws Exception {
        Path path=source(false,"0.76",4,4);
        var atlas=AtlasSubset.read(path);
        assertNull(atlas.get("region").metadata(),"Full image remains a full image regardless of packer scale");
        assertEquals(1,new SkeletonJsonCodec().read(path).attachments().size());
        for(String value:List.of("0","-1","NaN","Infinity","1,2")) {
            source(false,value,4,4);
            assertThrows(java.io.IOException.class,()->AtlasSubset.read(path),value);
        }
    }

    @Test void pmaSurvivesProjectRoundtripPortableSaveAndExportWithoutRewritingPixels() throws Exception {
        Path path=source(true,"1",4,4);
        byte[] original=Files.readAllBytes(temp.resolve("page.png"));
        var model=new SkeletonJsonCodec().read(path);
        var metadata=model.resources().atlas().values().iterator().next();
        assertTrue(metadata.premultiplied());
        assertTrue(VisibleGeometry.evaluate(model,model.evaluateWorld()).get(0).premultiplied());
        var codec=new ProjectCodec();
        assertEquals(model,codec.decode(codec.encode(model)));
        var document=new ProjectDocument(model);Path saved=temp.resolve("saved.json");document.savePortable(saved);
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(saved);
        assertTrue(reopened.history().state().model().resources().atlas().values().iterator().next().premultiplied());
        Path exported=new ExportPackage().publish(temp.resolve("export"),model,temp);
        assertTrue(Files.readString(exported.resolveSibling("skeleton.atlas")).contains("pma: true"));
        var loaded=new SkeletonJsonCodec().read(exported);
        assertTrue(loaded.resources().atlas().values().iterator().next().premultiplied());
        assertArrayEquals(original,Files.readAllBytes(Path.of(loaded.attachments().get(0).source())));
        assertArrayEquals(original,Files.readAllBytes(temp.resolve("page.png")));
    }

    @Test void platformPageResizeRetainsDeclaredUvSpaceAndReportsPlaceholderArtwork() throws Exception {
        Path path=source(false,"1",2,2);
        assertThrows(java.io.IOException.class,()->AtlasSubset.read(path));
        var warnings=new ArrayList<String>();
        var atlas=AtlasSubset.read(path,true,warnings);
        assertNotNull(atlas.get("region"));assertTrue(warnings.stream().anyMatch(w->w.contains("different platform texture dimensions")));
        source(false,"1",1,1);warnings.clear();
        AtlasSubset.read(path,true,warnings);
        assertTrue(warnings.stream().anyMatch(w->w.contains("1x1 placeholder")));
    }

    @Test void allQuarterTurnsPreserveTexelsUvsAndPortableRoundtrips() throws Exception {
        Path path=source(false,"1",4,4);
        for(int degrees:List.of(0,90,180,270)) {
            var page=new BufferedImage(4,4,BufferedImage.TYPE_INT_ARGB);
            boolean swap=degrees==90||degrees==270;
            var region=new AtlasRegion(4,4,1,1,2,3,2,3,0,0,swap,false,degrees);
            // Encode distinct logical texels into the physical packed orientation.
            for(int y=0;y<3;y++) for(int x=0;x<2;x++) {
                var uv=region.packedUv((x+.5)/2,(y+.5)/3);
                int packedX=(int)Math.floor(uv.x()*4),packedY=(int)Math.floor(uv.y()*4);
                page.setRGB(packedX,packedY,0xff000000|((x+1)*70<<16)|((y+1)*50<<8));
            }
            ImageIO.write(page,"png",temp.resolve("page.png").toFile());
            Files.writeString(temp.resolve("skeleton.atlas"),"page.png\nsize:4,4\nformat:RGBA8888\nfilter:Nearest,Nearest\nregion\nrotate:"+degrees+"\nbounds:1,1,2,3\n");
            var atlas=AtlasSubset.read(path);
            assertEquals(region,atlas.get("region").metadata());
            var logical=AtlasSplitter.unpack(page,region);
            for(int y=0;y<3;y++) for(int x=0;x<2;x++) {
                assertEquals(0xff000000|((x+1)*70<<16)|((y+1)*50<<8),logical.getRGB(x,y));
                assertEquals(region.packedUv((x+.5)/2,(y+.5)/3),region.uv((x+.5)/2,(y+.5)/3));
            }
            var model=new SkeletonJsonCodec().read(path);
            var codec=new ProjectCodec();assertEquals(model,codec.decode(codec.encode(model)));
            Path exported=new ExportPackage().publish(temp.resolve("rotation-"+degrees),model,temp);
            assertEquals(region,new SkeletonJsonCodec().read(exported).resources().atlas().values().iterator().next());
        }
        assertThrows(IllegalArgumentException.class,()->new AtlasRegion(4,4,0,0,2,2,2,2,0,0,false,false,45));
        assertThrows(IllegalArgumentException.class,()->new AtlasRegion(4,4,0,0,2,2,2,2,0,0,false,false,270));
    }
}
