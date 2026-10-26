package io.github.samlinb.openbone.viewport;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class ViewportMathTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(doubles={.3,1.4,4}) void cameraAnchorInversePanAndDeviceIndependence(double zoom){
        var c=new Camera2D();Vec2 a=c.world(623,243,800,600);c.zoomAt(zoom,623,243,800,600);
        assertEquals(a.x(),c.world(623,243,800,600).x(),1e-12);assertEquals(a.y(),c.world(623,243,800,600).y(),1e-12);
        for(int i=-500;i<500;i+=31){var p=new Vec2(i,i*.73);var s=c.screen(p,800,600);var q=c.world(s.x(),s.y(),800,600);assertEquals(p.x(),q.x(),1e-10);assertEquals(p.y(),q.y(),1e-10);}
        var s=c.screen(a,800,600);c.pan(17,-25);var p=c.screen(a,800,600);assertEquals(s.x()+17,p.x(),1e-9);assertEquals(s.y()-25,p.y(),1e-9);
        double before=c.zoom();assertThrows(IllegalArgumentException.class,()->c.zoomAt(Double.NaN,0,0,800,600));assertEquals(before,c.zoom());
    }
    @Test void alphaBoundaryMirrorAndReverseSlotOrder() throws Exception {
        var image=new BufferedImage(4,4,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<4;y++)for(int x=0;x<4;x++)image.setRGB(x,y,0xff00ff00);
        image.setRGB(1,1,0);image.setRGB(2,1,0x1000ff00);image.setRGB(3,1,0x1100ff00);
        var file=temp.resolve("中文透明.png");ImageIO.write(image,"png",file.toFile());
        var assets=new ImageAssets();assets.base(temp);
        var b=new Bone(EntityId.create(),"root",null,Transform.IDENTITY,80);
        var s1=new Slot(EntityId.create(),"back",b.id(),null);var s2=new Slot(EntityId.create(),"front",b.id(),null);
        var r1=new RegionAttachment(EntityId.create(),"back",s1.id(),"missing.png",Transform.IDENTITY,4,4);
        var r2=new RegionAttachment(EntityId.create(),"front",s2.id(),"中文透明.png",Transform.IDENTITY,4,4);
        var d=new DocumentSnapshot(List.of(b),List.of(s1.withActiveAttachment(r1.id()),s2.withActiveAttachment(r2.id())),List.of(r1,r2));
        assertEquals(r2.id(),Picker.region(d,new Vec2(-1.5,1.5),assets).orElseThrow());
        assertEquals(r1.id(),Picker.region(d,new Vec2(-.5,.5),assets).orElseThrow()); // hole
        assertEquals(r1.id(),Picker.region(d,new Vec2(.5,.5),assets).orElseThrow()); // alpha==16
        assertEquals(r2.id(),Picker.region(d,new Vec2(1.5,.5),assets).orElseThrow()); // alpha==17
        var mirrored=ModelEdits.editBone(d,b.id(),v->v.withLocal(new Transform(0,0,0,-1,1)));
        assertEquals(r2.id(),Picker.region(mirrored,new Vec2(-1.5,.5),assets).orElseThrow());
        assertEquals(r1.id(),Picker.region(mirrored,new Vec2(.5,.5),assets).orElseThrow());
        assertEquals(r1.id(),Picker.region(EditorEdits.order(d,s1.id(),1),new Vec2(-1.5,1.5),assets).orElseThrow());
        assertTrue(Picker.region(d,new Vec2(2,0),assets).isEmpty());assertTrue(Picker.region(d,new Vec2(0,-2),assets).isEmpty());
        var zero=ModelEdits.editBone(d,b.id(),v->v.withLocal(new Transform(0,0,0,0,1)));assertTrue(Picker.region(zero,new Vec2(0,0),assets).isEmpty());
    }
    @Test void missingInvalidReloadAndCacheRetention() throws Exception {
        var assets=new ImageAssets();assets.base(temp);var missing=assets.get("tiny.png");assertTrue(missing.missing());assertEquals(32,missing.pixels().getWidth());
        assertTrue(assets.get("bad\u0000path").missing());
        var png=temp.resolve("tiny.png");ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",png.toFile());
        assertSame(missing,assets.get("tiny.png"));assets.reload();assertFalse(assets.get("tiny.png").missing());
        assets.get("other.png");assertEquals(2,assets.size());assets.retain(Set.of(png));assertEquals(1,assets.size());
        Files.writeString(temp.resolve("bad.png"),"not PNG");assertThrows(java.io.IOException.class,()->ImageAssets.readPng(temp.resolve("bad.png")));
        var huge=temp.resolve("wide.png");ImageIO.write(new BufferedImage(8193,1,BufferedImage.TYPE_INT_ARGB),"png",huge.toFile());assertThrows(java.io.IOException.class,()->ImageAssets.readPng(huge));
    }
    @Test void finiteHugeModelCoordinatesStayOffscreenInsteadOfCrashingRenderer(){var c=new Camera2D();c.zoomAt(20,400,300,800,600);var p=c.screen(new Vec2(Double.MAX_VALUE,-Double.MAX_VALUE),800,600);assertTrue(Double.isFinite(p.x())&&p.x()>800);assertTrue(Double.isFinite(p.y())&&p.y()>600);}
    @Test void bonePickingUsesLogicalPixelToleranceAtAllZooms(){
        var b=new Bone(EntityId.create(),"b",null,new Transform(20,10,35,-2,.4),80);var d=new DocumentSnapshot(List.of(b),List.of(),List.of());var c=new Camera2D();
        for(double z:new double[]{.3,1.4,4}){c.zoomAt(z,400,300,800,600);var tip=c.screen(d.evaluateWorld().bones().get(b.id()).tip(),800,600);assertEquals(b.id(),Picker.bone(d,tip,c,800,600).orElseThrow());assertTrue(Picker.bone(d,new Vec2(0,0),c,800,600).isEmpty());}
    }
}
