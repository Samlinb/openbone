package io.github.samlinb.openbone.runtime;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.glutils.*;
import com.badlogic.gdx.math.Matrix4;
import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Vec2;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.viewport.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static io.github.samlinb.openbone.i18n.M.m;

/** Separate JVM: only validated exported runtime assets enter this renderer. */
public final class RuntimePreview extends ApplicationAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode request;
    private final Path output;
    private final boolean interactive;
    private final DocumentSnapshot model;
    private final EntityId animation;
    private final String skeletonHash;
    private final List<String> warnings;
    private final int width, height;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final Map<String,Texture> textures = new HashMap<>();
    private final PhysicsPlayer player = new PhysicsPlayer();
    private PolygonSpriteBatch batch;
    private ShapeRenderer shapes;
    private FrameBuffer framebuffer;
    private double cx, cy, zoom = 1, elapsed;
    private boolean disposed;
    private boolean exitAfterCapture = true;

    RuntimePreview(JsonNode request, Path output, boolean interactive) throws Exception {
        this.request = request; this.output = output; this.interactive = interactive;
        if (!request.path("protocol").isIntegralNumber() || request.path("protocol").asInt() != 1 || !request.path("entry").isTextual()
                || !request.path("animation").isTextual()) throw new IllegalArgumentException(m("runtime.request"));
        width = dimension(request,"width"); height = dimension(request,"height");
        double time = request.path("time").asDouble(Double.NaN);
        if (!request.path("time").isNumber() || !Double.isFinite(time) || time < 0 || time > 600) throw new IllegalArgumentException(m("runtime.request"));
        for(String flag:List.of("fit","fitAnimation","showBones","showGrid"))
            if(request.has(flag)&&!request.get(flag).isBoolean())throw new IllegalArgumentException(m("runtime.request"));
        Path skeleton = ExportPackage.resolve(Path.of(request.get("entry").asText()));
        skeletonHash = ResourcePaths.sha(Files.readAllBytes(skeleton));
        if (request.has("skeletonSHA256") && !request.path("skeletonSHA256").asText().equals(skeletonHash))
            throw new IllegalArgumentException(m("runtime.snapshot"));
        var codec = new SkeletonJsonCodec();
        model = codec.read(skeleton);
        warnings = codec.warnings();
        animation = model.animations().stream().filter(a -> a.name().equals(request.get("animation").asText()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(m("runtime.animation"))).id();
        PhysicsPlayer.validateTime(model,animation,time);
    }

    static int dimension(JsonNode request,String field) {
        JsonNode value = request.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 16 || value.asInt() > 2048)
            throw new IllegalArgumentException(m("runtime.request"));
        return value.asInt();
    }

    @Override public void create() {
        try {
            batch = new PolygonSpriteBatch(); shapes = new ShapeRenderer();
            framebuffer = new FrameBuffer(Pixmap.Format.RGBA8888,width,height,false);
            for (var attachment : model.attachments()) if (attachment.source() != null && !textures.containsKey(attachment.source())) {
                var decoded = ImageAssets.decodePng(ResourcePaths.bytes(Path.of(attachment.source()),64*1024*1024));
                var pixels = new Pixmap(decoded.getWidth(),decoded.getHeight(),Pixmap.Format.RGBA8888);
                try {
                    pixels.setBlending(Pixmap.Blending.None);
                    for (int y=0;y<decoded.getHeight();y++) for (int x=0;x<decoded.getWidth();x++) {
                        int argb = decoded.getRGB(x,y); pixels.drawPixel(x,y,(argb<<8)|(argb>>>24));
                    }
                    var texture = new Texture(pixels);
                    texture.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest);
                    textures.put(attachment.source(),texture);
                } finally { pixels.dispose(); }
            }
            configureCamera();
        } catch (Throwable failure) { fail(failure); }
    }

    private void configureCamera() {
        if (request.has("camera") && !request.path("fit").asBoolean() && !request.path("fitAnimation").asBoolean() && !request.has("bounds")) {
            cx=request.path("camera").path("x").asDouble();cy=request.path("camera").path("y").asDouble();zoom=request.path("camera").path("zoom").asDouble();
            if (!Double.isFinite(cx)||!Double.isFinite(cy)||!Double.isFinite(zoom)||zoom<=0) throw new IllegalArgumentException(m("runtime.request"));
            return;
        }
        if (request.has("bounds")) {
            var bounds=request.get("bounds");
            fit(bounds.path("minX").asDouble(Double.NaN),bounds.path("minY").asDouble(Double.NaN),
                    bounds.path("maxX").asDouble(Double.NaN),bounds.path("maxY").asDouble(Double.NaN));
        } else if (request.path("fitAnimation").asBoolean() || interactive) {
            var bounds=AnimationFraming.sample(model,animation,24,request.path("time").asDouble());
            if(bounds!=null)fit(bounds.minX(),bounds.minY(),bounds.maxX(),bounds.maxY());
            else fitPose(request.path("time").asDouble());
        } else if (request.path("fit").asBoolean()) fitPose(request.path("time").asDouble());
    }

    private void fitPose(double time) {
        var pose=player.seek(model,animation,time,false);
        var points=new ArrayList<Vec2>();
        for(var draw:VisibleGeometry.evaluate(model,pose))if(draw.color().a()>0)points.addAll(draw.geometry().vertices());
        if(points.isEmpty())for(var bone:pose.bones().values()){points.add(bone.origin());points.add(bone.tip());}
        if(!points.isEmpty())fit(points.stream().mapToDouble(Vec2::x).min().orElse(0),points.stream().mapToDouble(Vec2::y).min().orElse(0),
                points.stream().mapToDouble(Vec2::x).max().orElse(0),points.stream().mapToDouble(Vec2::y).max().orElse(0));
    }

    private void fit(double minX,double minY,double maxX,double maxY) {
        var camera=new Camera2D(); camera.fit(minX,minY,maxX,maxY,width,height,.7);
        cx=camera.x();cy=camera.y();zoom=camera.zoom();
    }
    private Vec2 screen(Vec2 point) { return new Vec2(width/2.+(point.x()-cx)*zoom,height/2.+(point.y()-cy)*zoom); }

    @Override public void render() {
        if (finished.isDone() || disposed) return;
        try {
            double time=request.path("time").asDouble();
            if(interactive) {elapsed+=Gdx.graphics.getDeltaTime();time+=elapsed;}
            var pose=player.seek(model,animation,time,interactive);
            framebuffer.begin();
            try { draw(pose); if(!interactive)writeResult(time); }
            finally { framebuffer.end(); }
            if(interactive) {
                Gdx.gl.glClearColor(25/255f,31/255f,39/255f,1);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                var projection=new Matrix4().setToOrtho2D(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
                batch.setProjectionMatrix(projection);batch.begin();
                batch.draw(framebuffer.getColorBufferTexture(),0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight(),0,1,1,0);
                batch.end();
                if(Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.ESCAPE))Gdx.app.exit();
            } else { finished.complete(null); if (exitAfterCapture) Gdx.app.exit(); }
        } catch(Throwable failure) {fail(failure);}
    }

    private void draw(WorldPose pose) {
        Gdx.gl.glClearColor(25/255f,31/255f,39/255f,1);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        var projection=new Matrix4().setToOrtho2D(0,0,width,height);batch.setProjectionMatrix(projection);shapes.setProjectionMatrix(projection);
        if(request.path("showGrid").asBoolean()) {
            shapes.begin(ShapeRenderer.ShapeType.Line);shapes.setColor(.18f,.21f,.25f,1);
            for(int x=0;x<width;x+=50)shapes.line(x,0,x,height);
            for(int y=0;y<height;y+=50)shapes.line(0,y,width,y);shapes.end();
        }
        TexturedScene.draw(batch,VisibleGeometry.evaluate(model,pose),textures::get,this::screen);
        if(request.path("showBones").asBoolean()) {
            shapes.begin(ShapeRenderer.ShapeType.Line);shapes.setColor(.5f,.85f,.75f,1);
            for(var bone:pose.bones().values()) {
                var a=screen(bone.origin());var b=screen(bone.tip());
                shapes.line((float)a.x(),(float)a.y(),(float)b.x(),(float)b.y());
                shapes.circle((float)a.x(),(float)a.y(),3);
            }
            shapes.end();
        }
    }

    private void writeResult(double time) throws Exception {
        byte[] rgba=com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0,0,width,height,true);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);int[] pixels=new int[width*height];
        for(int i=0;i<pixels.length;i++){int k=i*4;pixels[i]=((rgba[k]&255)<<16)|((rgba[k+1]&255)<<8)|(rgba[k+2]&255);}
        image.setRGB(0,0,width,height,pixels,0,width);
        Path png=output.resolve("preview.png");
        if(Files.exists(png)||Files.exists(output.resolve("result.json")))throw new IllegalArgumentException(m("runtime.outputExists"));
        ImageIO.write(image,"PNG",png.toFile());
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("protocol",1);receipt.put("runtime","openbone-runtime");receipt.put("runtimeVersion","1.0.1");
        receipt.put("runtimePid",ProcessHandle.current().pid());
        receipt.put("sourceRevision",request.path("sourceRevision").asText());receipt.put("animationId",request.path("animationId").asText());
        receipt.put("animation",request.path("animation").asText());receipt.put("time",time);receipt.put("width",width);receipt.put("height",height);
        receipt.put("skeletonSHA256",skeletonHash);receipt.put("imageSHA256",ResourcePaths.sha(Files.readAllBytes(png)));
        receipt.put("camera",Map.of("x",cx,"y",cy,"zoom",zoom));receipt.put("warnings",warnings);
        receipt.put("glVendor",Gdx.gl.glGetString(GL20.GL_VENDOR));receipt.put("glRenderer",Gdx.gl.glGetString(GL20.GL_RENDERER));
        receipt.put("showBones",request.path("showBones").asBoolean());receipt.put("showGrid",request.path("showGrid").asBoolean());
        new AtomicProjectFile().write(output.resolve("result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
    }

    private void fail(Throwable failure) { finished.completeExceptionally(failure); if (exitAfterCapture) Gdx.app.exit(); }
    @Override public void dispose() {
        disposed=true;
        if(framebuffer!=null)framebuffer.dispose();if(batch!=null)batch.dispose();if(shapes!=null)shapes.dispose();
        textures.values().forEach(Texture::dispose);textures.clear();
        if(interactive)finished.complete(null);
    }

    public static void main(String[] args) {
        try {
            if(args.length==1&&args[0].equals("--worker")) { runWorker(); return; }
            boolean interactive;
            JsonNode request;Path output=null;
            if(args.length==2&&args[0].equals("--request")) {
                Path input=Path.of(args[1]).toAbsolutePath().normalize();
                request=JSON.readTree(ResourcePaths.bytes(input,65536));output=input.getParent();interactive=false;
            } else if(args.length==4&&args[0].equals("--package")&&args[2].equals("--animation")) {
                request=JSON.valueToTree(Map.of("protocol",1,"entry",Path.of(args[1]).toAbsolutePath().toString(),"animation",args[3],
                        "width",768,"height",768,"time",0,"fit",true));interactive=true;
            } else throw new IllegalArgumentException(m("runtime.usage"));
            var app=new RuntimePreview(request,output,interactive);
            LwjglApplicationConfiguration.disableAudio=true;
            var config=new LwjglApplicationConfiguration();config.title=m("runtime.title");
            config.width=app.width;config.height=app.height;config.forceExit=false;config.resizable=false;
            config.foregroundFPS=60;config.backgroundFPS=60;
            var backend=new LwjglApplication(app,config);
            try { app.finished.get(interactive?365:15,interactive?TimeUnit.DAYS:TimeUnit.SECONDS); }
            finally { backend.exit(); }
        } catch(Throwable failure) {
            Throwable cause=failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure;
            System.err.println(Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));System.exit(2);
        }
    }

    /** One GL context per worker process; newline JSON paths submit immutable requests. */
    private static void runWorker() throws Exception {
        var ready=new CompletableFuture<Void>();
        LwjglApplicationConfiguration.disableAudio=true;
        var config=new LwjglApplicationConfiguration();config.title=m("runtime.title");
        config.width=64;config.height=64;config.forceExit=false;config.resizable=false;
        config.foregroundFPS=30;config.backgroundFPS=30;
        var backend=new LwjglApplication(new ApplicationAdapter(){
            @Override public void create(){ready.complete(null);}
        },config);
        try(var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))) {
            ready.get(10,TimeUnit.SECONDS);
            String line;
            while((line=input.readLine())!=null) {
                if(line.length()>65536)throw new IllegalArgumentException(m("runtime.request"));
                Path path=Path.of(JSON.readTree(line).path("request").asText()).toAbsolutePath().normalize();
                var done=new CompletableFuture<Void>();
                Gdx.app.postRunnable(()->{
                    RuntimePreview renderer=null;
                    try {
                        var request=JSON.readTree(ResourcePaths.bytes(path,65536));
                        renderer=new RuntimePreview(request,path.getParent(),false);renderer.exitAfterCapture=false;
                        renderer.create();renderer.render();renderer.finished.join();
                    } catch(Throwable failure) {
                        try { new AtomicProjectFile().write(path.getParent().resolve("error.json"),JSON.writeValueAsBytes(Map.of("error",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())))); }
                        catch(Exception logFailure) { System.err.println(logFailure.getMessage()); }
                    } finally {if(renderer!=null)renderer.dispose();done.complete(null);}
                });
                done.get(15,TimeUnit.SECONDS);
            }
        } finally {backend.exit();}
    }
}
