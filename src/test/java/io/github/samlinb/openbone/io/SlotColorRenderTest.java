package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.nio.file.Files;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.*;
import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl.LwjglNativesLoader;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.ScreenUtils;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.viewport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native check of the production renderer, using a windowless Pbuffer. */
@EnabledIfSystemProperty(named="openbone.verifyGl",matches="true")
class SlotColorRenderTest {
    @TempDir Path temp;
    static final int W=384,H=300;
    @Test void productionRendererMultipliesTintAndAlphaAndRendersDandaoContactSheet() throws Exception {
        LwjglNativesLoader.load();
        assertTrue((Pbuffer.getCapabilities()&Pbuffer.PBUFFER_SUPPORTED)!=0,"Pbuffer unavailable");
        var oldGl=Gdx.gl; var oldGl20=Gdx.gl20; var oldGraphics=Gdx.graphics; var oldApp=Gdx.app;
        var buffer=new Pbuffer(W,H,new PixelFormat(8,0,0),null,null);
        PolygonSpriteBatch batch=null; var textures=new HashMap<String,Texture>();
        try {
            buffer.makeCurrent();
            var ctor=Class.forName("com.badlogic.gdx.backends.lwjgl.LwjglGL20").getDeclaredConstructor(); ctor.setAccessible(true);
            Gdx.gl20=(com.badlogic.gdx.graphics.GL20)ctor.newInstance(); Gdx.gl=Gdx.gl20;
            Gdx.graphics=(Graphics)Proxy.newProxyInstance(Graphics.class.getClassLoader(),new Class[]{Graphics.class},(p,m,a)-> {
                if(List.of("getWidth","getHeight","getBackBufferWidth","getBackBufferHeight").contains(m.getName())) return m.getName().contains("Width")?W:H;
                if(m.getName().equals("getGLVersion")) return new GLVersion(Application.ApplicationType.Desktop,
                        GL11.glGetString(GL11.GL_VERSION),GL11.glGetString(GL11.GL_VENDOR),GL11.glGetString(GL11.GL_RENDERER));
                if(m.getReturnType()==boolean.class) return false;
                if(m.getReturnType()==int.class) return 0;
                if(m.getReturnType()==float.class) return 0f;
                return null;
            });
            Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class[]{Application.class},(p,m,a)-> {
                if(m.getName().equals("equals")) return p==a[0];
                if(m.getName().equals("hashCode")) return System.identityHashCode(p);
                if(m.getName().equals("getType")) return Application.ApplicationType.Desktop;
                if(m.getReturnType()==int.class) return 0;
                return null;
            });
            batch=new PolygonSpriteBatch(); batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0,0,W,H));
            var tex=new Pixmap(1,1,Pixmap.Format.RGBA8888); tex.setBlending(Pixmap.Blending.None);
            tex.drawPixel(0,0,0xcc996680); textures.put("test",new Texture(tex)); tex.dispose();
            var geometry=new PolygonClipper.Geometry(List.of(new Vec2(0,0),new Vec2(0,H),new Vec2(W,H),new Vec2(W,0)),
                    List.of(new Vec2(0,1),new Vec2(0,0),new Vec2(1,0),new Vec2(1,1)),List.of(0,1,2,2,3,0));
            var id=EntityId.create();
            for(double alpha:new double[]{0,.5,1}) {
                clear(0,0,0);
                TexturedScene.draw(batch,List.of(new VisibleGeometry.Draw(id,id,"test",geometry,Slot.Blend.NORMAL,new Rgba(.5,.25,1,alpha))),textures::get,v->v);
                int pixel=read().getRGB(W/2,H/2); double effective=(128/255.)*alpha;
                assertEquals(Math.round(255*.8*.5*effective),(pixel>>>16)&255,2);
                assertEquals(Math.round(255*.6*.25*effective),(pixel>>>8)&255,2);
                assertEquals(Math.round(255*.4*effective),pixel&255,2);
            }
            for(String extension:List.of("json","atlas","png")) try(var in=getClass().getResourceAsStream("/spine38/dandao_1."+extension)) { Files.copy(in,temp.resolve("dandao_1."+extension)); }
            var d=new SkeletonJsonCodec().read(temp.resolve("dandao_1.json"));
            new io.github.samlinb.openbone.application.ProjectDocument(d).savePortable(Path.of("target/dandao-color-project.json"));
            var split=AtlasSplitter.split(temp.resolve("dandao_1.json"),d).document();
            var renderAttachments=new ArrayList<>(d.attachments());renderAttachments.addAll(split.attachments());
            for(var attachment:renderAttachments) if(!textures.containsKey(attachment.source())) {
                var image=ImageAssets.readPng(Path.of(attachment.source()));
                var pixels=new Pixmap(image.getWidth(),image.getHeight(),Pixmap.Format.RGBA8888); pixels.setBlending(Pixmap.Blending.None);
                for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) { int argb=image.getRGB(x,y); pixels.drawPixel(x,y,(argb<<8)|(argb>>>24)); }
                var texture=new Texture(pixels); pixels.dispose();texture.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest); textures.put(attachment.source(),texture);
            }
            double[] times={0,.0667,.08335,.1,.1667,.2333,.2667,.3333};
            var frames=new ArrayList<List<VisibleGeometry.Draw>>(); double minX=0,minY=0,maxX=0,maxY=0;
            for(double time:times) {
                var draws=VisibleGeometry.evaluate(d,AnimationSampler.world(d,d.animations().get(0).id(),time,false)); frames.add(draws);
                for(var draw:draws) if(draw.color().a()>0) for(var v:draw.geometry().vertices()) { minX=Math.min(minX,v.x());maxX=Math.max(maxX,v.x());minY=Math.min(minY,v.y());maxY=Math.max(maxY,v.y()); }
            }
            final double scale=Math.min((W-40)/(maxX-minX),(H-60)/(maxY-minY)),cx=(minX+maxX)/2,cy=(minY+maxY)/2;
            var sheet=new BufferedImage(W*4,H*2,BufferedImage.TYPE_INT_RGB); var canvas=sheet.createGraphics();
            for(int i=0;i<times.length;i++) {
                clear(17/255f,20/255f,28/255f);
                TexturedScene.draw(batch,frames.get(i),textures::get,v->new Vec2(W/2.+(v.x()-cx)*scale,H/2.+(v.y()-cy)*scale));
                var before=read();
                canvas.drawImage(before,(i%4)*W,(i/4)*H,null);canvas.setColor(java.awt.Color.WHITE);
                canvas.drawString(String.format(Locale.ROOT,"t = %.4f s",times[i]),(i%4)*W+12,(i/4)*H+20);
                clear(17/255f,20/255f,28/255f);
                var unpacked=VisibleGeometry.evaluate(split,AnimationSampler.world(split,split.animations().get(0).id(),times[i],false));
                TexturedScene.draw(batch,unpacked,textures::get,v->new Vec2(W/2.+(v.x()-cx)*scale,H/2.+(v.y()-cy)*scale));
                var after=read(); long difference=0;
                for(int y=0;y<H;y++) for(int x=0;x<W;x++) {
                    int a=before.getRGB(x,y),b=after.getRGB(x,y);
                    for(int channel=0;channel<3;channel++) difference+=Math.abs(((a>>>(channel*8))&255)-((b>>>(channel*8))&255));
                }
                // Nearest sampling can round boundary texels differently after transparent padding is restored.
                assertTrue(difference/(double)(W*H*3)<.5,"Split image rendering changed at time "+times[i]);
            }
            canvas.dispose();Path target=Path.of("target/dandao-color-preview.png");Files.createDirectories(target.getParent());ImageIO.write(sheet,"PNG",target.toFile());
            if(Boolean.getBoolean("openbone.spineboy")) renderSpineboy(batch,textures);
        } finally {
            if(batch!=null) batch.dispose(); textures.values().forEach(Texture::dispose); buffer.destroy();
            Gdx.gl=oldGl;Gdx.gl20=oldGl20;Gdx.graphics=oldGraphics;Gdx.app=oldApp;
        }
    }
    static void renderSpineboy(PolygonSpriteBatch batch,Map<String,Texture> textures) throws Exception {
        Path project=Path.of("target/spineboy-openbone/spineboy-pro.json").toAbsolutePath();
        if(!Files.exists(project)) {
            Files.createDirectories(project.getParent());
            var document=new io.github.samlinb.openbone.application.ProjectDocument(DocumentSnapshot.EMPTY);
            Path source=Path.of("res/spineboy/spineboy-pro.spine");document.openResolved(source);document.savePortable(project);
            Files.copy(source.resolveSibling("license.txt"),project.resolveSibling("license.txt"),StandardCopyOption.REPLACE_EXISTING);
        }
        var d=ResourcePaths.absolute(new ProjectCodec().read(project),project.getParent());
        var clip=d.animations().stream().filter(a->a.name().equals("run")).findFirst().orElseThrow();
        var frames=new ArrayList<List<VisibleGeometry.Draw>>();double minX=0,minY=0,maxX=0,maxY=0;
        for(int frame=0;frame<4;frame++) {
            var draws=VisibleGeometry.evaluate(d,AnimationSampler.world(d,clip.id(),clip.duration()*frame/4,false));frames.add(draws);
            for(var draw:draws) {
                for(var v:draw.geometry().vertices()) {minX=Math.min(minX,v.x());maxX=Math.max(maxX,v.x());minY=Math.min(minY,v.y());maxY=Math.max(maxY,v.y());}
                if(!textures.containsKey(draw.source())) {
                    var image=ImageAssets.readPng(Path.of(draw.source()));var pixels=new Pixmap(image.getWidth(),image.getHeight(),Pixmap.Format.RGBA8888);pixels.setBlending(Pixmap.Blending.None);
                    for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {int argb=image.getRGB(x,y);pixels.drawPixel(x,y,(argb<<8)|(argb>>>24));}
                    var texture=new Texture(pixels);pixels.dispose();textures.put(draw.source(),texture);
                }
            }
        }
        assertFalse(frames.get(0).isEmpty());
        final double scale=Math.min((W-40)/(maxX-minX),(H-50)/(maxY-minY)),cx=(minX+maxX)/2,cy=(minY+maxY)/2;
        var sheet=new BufferedImage(W*4,H,BufferedImage.TYPE_INT_RGB);var canvas=sheet.createGraphics();
        for(int i=0;i<4;i++) {
            clear(17/255f,20/255f,28/255f);
            TexturedScene.draw(batch,frames.get(i),textures::get,v->new Vec2(W/2.+(v.x()-cx)*scale,H/2.+(v.y()-cy)*scale));
            canvas.drawImage(read(),i*W,0,null);canvas.setColor(java.awt.Color.WHITE);canvas.drawString(String.format(Locale.ROOT,"run · %.2f s",clip.duration()*i/4),i*W+12,20);
        }
        canvas.dispose();ImageIO.write(sheet,"PNG",project.resolveSibling("preview.png").toFile());
    }
    static void clear(float r,float g,float b) { Gdx.gl.glViewport(0,0,W,H);Gdx.gl.glClearColor(r,g,b,1);Gdx.gl.glClear(GL11.GL_COLOR_BUFFER_BIT); }
    static BufferedImage read() {
        byte[] pixels=ScreenUtils.getFrameBufferPixels(0,0,W,H,true);var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB);
        int[] row=new int[W];for(int y=0;y<H;y++) {for(int x=0;x<W;x++) {int i=(y*W+x)*4;row[x]=((pixels[i+3]&255)<<24)|((pixels[i]&255)<<16)|((pixels[i+1]&255)<<8)|(pixels[i+2]&255);}image.setRGB(0,y,W,1,row,0,W);}return image;
    }
}
