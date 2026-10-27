package io.github.samlinb.openbone.application;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.core.model.EntityId;
import static io.github.samlinb.openbone.i18n.M.m;

/** Background encoding of revision-checked images from the shared live GL capture service. */
public final class AnimationMediaService implements AutoCloseable {
    public interface Source {
        void checkRevision(String revision) throws Exception;
        CompletableFuture<Frame> capture(EntityId animation, double time, String revision, boolean fit) throws Exception;
        default String description() { return "OpenBone live GL viewport"; }
        default String fitStrategy(){return "Fixed camera framed once at poster time";}
    }
    public record Frame(BufferedImage image, Map<String,Object> metadata) {}
    public record Request(String requestId, String revision, EntityId animation, Path directory,
            int fps, double duration, boolean fit, double fitTime) {
        public Request {
            Objects.requireNonNull(requestId);Objects.requireNonNull(revision);Objects.requireNonNull(animation);
            Objects.requireNonNull(directory);
            if(requestId.isBlank()||requestId.length()>128||revision.isBlank()||!directory.isAbsolute()
                    ||fps<1||fps>60||!Double.isFinite(duration)||duration<0||duration>600
                    ||!Double.isFinite(fitTime)||fitTime<0||fitTime>Math.max(0,duration))
                throw new IllegalArgumentException(m("media.invalidRequest"));
            directory=directory.normalize();
            if(directory.getParent()==null)throw new IllegalArgumentException(m("media.invalidRequest"));
        }
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString(); final Request request; final Source source;
        final CompletableFuture<Void> finished=new CompletableFuture<>();
        final Instant started=Instant.now();
        volatile String state="QUEUED",error="";volatile int frames,width,height,total,decoded,unique;
        volatile int foregroundMax,nonEmpty,empty,posterForeground,edgeFrames;
        volatile boolean cancel;volatile Process process;volatile Path stage;volatile CompletableFuture<Frame> pending;
        Job(Request request,Source source){this.request=request;this.source=source;}
        boolean terminal(){return Set.of("COMPLETE","FAILED","CANCELLED").contains(state);}
        Map<String,Object> snapshot(){
            var result=new LinkedHashMap<String,Object>();
            result.put("jobId",id);result.put("status",state);result.put("frames",frames);result.put("frameCount",total);
            result.put("decodedFrameCount",decoded);result.put("uniqueFrameCount",unique);
            result.put("maxForegroundPixels",foregroundMax);result.put("nonEmptyFrameCount",nonEmpty);result.put("emptyFrameCount",empty);
            result.put("posterForegroundPixels",posterForeground);result.put("visualStatus",nonEmpty>0?"NONEMPTY":"EMPTY");
            result.put("framesTouchingImageEdge",edgeFrames);
            result.put("fps",request.fps());result.put("duration",request.duration());
            result.put("renderedRevision",request.revision());result.put("animationId",request.animation().value());
            result.put("directory",request.directory().toString());result.put("width",width);result.put("height",height);
            result.put("encodedWidth",width+(width&1));result.put("encodedHeight",height+(height&1));
            result.put("mp4Path",request.directory().resolve("animation.mp4").toString());
            result.put("previewPath",request.directory().resolve("preview.png").toString());
            result.put("proofPath",request.directory().resolve("proof.json").toString());
            if(!error.isEmpty())result.put("error",error);
            return result;
        }
    }
    private final Map<String,Job> jobs=new LinkedHashMap<>(),requests=new LinkedHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"openbone-media-export");t.setDaemon(true);return t;});
    private Job active;private boolean closed;

    public synchronized Map<String,Object> start(Request request,Source source) throws Exception {
        if(closed)throw new IllegalStateException(m("media.closed"));
        Job prior=requests.get(request.requestId());
        if(prior!=null){if(!prior.request.equals(request))throw new IllegalArgumentException(m("media.requestConflict"));return prior.snapshot();}
        if(active!=null&&!active.terminal())throw new IllegalStateException(m("media.busy"));
        encoder(); // Missing configuration fails before allocating a job or touching its output.
        source.checkRevision(request.revision());
        if(Files.exists(request.directory()))throw new IOException(m("media.targetExists",request.directory()));
        var job=new Job(request,source);jobs.put(job.id,job);requests.put(request.requestId(),job);active=job;
        while(jobs.size()>128){String first=jobs.keySet().iterator().next();if(!jobs.get(first).terminal())break;jobs.remove(first);}
        while(requests.size()>128){String first=requests.keySet().iterator().next();if(!requests.get(first).terminal())break;requests.remove(first);}
        worker.submit(()->run(job));return job.snapshot();
    }
    public synchronized boolean busy(){return active!=null&&!active.terminal();}
    public Map<String,Object> status(String id,int waitMs) throws Exception {
        if(waitMs<0||waitMs>5000)throw new IllegalArgumentException(m("media.invalidWait"));
        Job job; synchronized(this){job=jobs.get(id);}
        if(job==null)throw new IllegalArgumentException(m("media.unknownJob"));
        if(waitMs>0&&!job.terminal())try{job.finished.get(waitMs,TimeUnit.MILLISECONDS);}catch(TimeoutException timeout){}
        return job.snapshot();
    }
    public Map<String,Object> cancel(String id) throws Exception {
        Job job;synchronized(this){job=jobs.get(id);}
        if(job==null)throw new IllegalArgumentException(m("media.unknownJob"));
        if(!job.terminal()){job.cancel=true;Process process=job.process;if(process!=null)process.destroyForcibly();var pending=job.pending;if(pending!=null)pending.cancel(false);}
        return job.snapshot();
    }
    public static Path encoder() throws IOException {
        String configured=System.getProperty("openbone.ffmpeg","");
        if(configured.isBlank())throw new IOException(m("media.encoderRequired"));
        Path path=Path.of(configured);
        if(!path.isAbsolute()||!Files.isRegularFile(path)||!Files.isExecutable(path))throw new IOException(m("media.encoderRequired"));
        return path.toAbsolutePath().normalize();
    }
    private static void check(Job job) throws Exception {
        if(job.cancel||Thread.currentThread().isInterrupted())throw new CancellationException(m("media.cancelled"));
        job.source.checkRevision(job.request.revision());
    }
    private static Frame frame(Job job,double time,boolean fit) throws Exception {
        check(job);var future=job.source.capture(job.request.animation(),time,job.request.revision(),fit);job.pending=future;
        if(job.cancel)future.cancel(false);
        try {
            Frame frame=future.get(15,TimeUnit.SECONDS);check(job);
            var meta=frame.metadata();
            if(!job.request.revision().equals(meta.get("revision"))
                    ||!job.request.animation().value().equals(meta.get("animationId"))
                    ||!(meta.get("time") instanceof Number n)||Math.abs(n.doubleValue()-time)>1e-7)
                throw new IllegalStateException(m("media.captureIdentity"));
            return frame;
        } finally {if(!future.isDone())future.cancel(false);job.pending=null;}
    }
    private static BufferedImage limited(BufferedImage input){
        double ratio=Math.min(1,1024./Math.max(input.getWidth(),input.getHeight()));
        if(ratio==1)return input;
        var result=new BufferedImage(Math.max(1,(int)(input.getWidth()*ratio)),Math.max(1,(int)(input.getHeight()*ratio)),BufferedImage.TYPE_INT_RGB);
        var graphics=result.createGraphics();graphics.drawImage(input,0,0,result.getWidth(),result.getHeight(),null);graphics.dispose();return result;
    }
    private void run(Job job) {
        Process process=null;
        try {
            check(job);job.state="RENDERING";
            Path directory=job.request.directory(),parent=directory.getParent();Files.createDirectories(parent);
            if(Files.exists(directory))throw new IOException(m("media.targetExists",directory));
            job.stage=Files.createTempDirectory(parent,".openbone-media-");
            Path mp4=job.stage.resolve("animation.mp4"),log=job.stage.resolve("encoder.log");
            Frame fitted=frame(job,job.request.fitTime(),job.request.fit());
            BufferedImage poster=limited(fitted.image());job.width=poster.getWidth();job.height=poster.getHeight();
            if(job.width<1||job.height<1||(long)job.width*job.height>1_048_576)throw new IOException(m("media.frameSize"));
            if(!ImageIO.write(poster,"PNG",job.stage.resolve("preview.png").toFile()))throw new IOException(m("media.pngWriter"));
            for(int y=0;y<poster.getHeight();y++)for(int x=0;x<poster.getWidth();x++)if(foreground(poster.getRGB(x,y)))job.posterForeground++;
            int intervals=(int)Math.ceil(job.request.duration()*job.request.fps());
            job.total=intervals==0?job.request.fps():intervals+1;
            var command=new ArrayList<>(List.of(encoder().toString(),"-hide_banner","-loglevel","error","-nostdin","-n",
                    "-f","rawvideo","-pixel_format","rgb24","-video_size",job.width+"x"+job.height,
                    "-framerate",Integer.toString(job.request.fps()),"-i","pipe:0","-an","-vf","pad=ceil(iw/2)*2:ceil(ih/2)*2",
                    "-c:v","libx264","-preset","veryfast","-threads","2","-crf","18","-pix_fmt","yuv420p","-movflags","+faststart",mp4.toString()));
            process=new ProcessBuilder(command).redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();job.process=process;
            int[] pixels=new int[job.width*job.height];byte[] rgb=new byte[pixels.length*3];var unique=new HashSet<Long>();
            try(var pipe=new BufferedOutputStream(process.getOutputStream(),1<<20)) {
                BufferedImage still=intervals==0?poster:null;
                for(int index=0;index<job.total;index++) {
                    double time=intervals==0?0:Math.min(job.request.duration(),index/(double)job.request.fps());
                    check(job);
                    BufferedImage image=still!=null?still:limited(frame(job,time,false).image());
                    if(image.getWidth()!=job.width||image.getHeight()!=job.height)throw new IOException(m("media.frameSizeChanged"));
                    image.getRGB(0,0,job.width,job.height,pixels,0,job.width);
                    int foreground=0;
                    for(int i=0,j=0;i<pixels.length;i++){int pixel=pixels[i];rgb[j++]=(byte)(pixel>>>16);rgb[j++]=(byte)(pixel>>>8);rgb[j++]=(byte)pixel;if(foreground(pixel))foreground++;}
                    job.foregroundMax=Math.max(job.foregroundMax,foreground);if(foreground>0)job.nonEmpty++;else job.empty++;
                    boolean edge=false;
                    for(int x=0;x<job.width&&!edge;x++)edge=foreground(pixels[x])||foreground(pixels[(job.height-1)*job.width+x]);
                    for(int y=0;y<job.height&&!edge;y++)edge=foreground(pixels[y*job.width])||foreground(pixels[y*job.width+job.width-1]);
                    if(edge)job.edgeFrames++;
                    var checksum=new CRC32();checksum.update(rgb);unique.add(checksum.getValue());
                    pipe.write(rgb);job.frames=index+1;job.unique=unique.size();
                }
            }
            check(job);job.state="ENCODING";
            if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException(m("media.encoderTimeout"));}
            if(process.exitValue()!=0)throw new IOException(m("media.encoderFailed",tail(log)));
            job.process=null;check(job);job.state="VERIFYING";
            job.decoded=decodedFrames(job,mp4,job.stage.resolve("decoder.log"));
            if(job.decoded!=job.total)throw new IOException(m("media.frameCountMismatch",job.total,job.decoded));
            check(job);
            var proof=new LinkedHashMap<String,Object>(job.snapshot());proof.put("status","COMPLETE");
            proof.put("source",job.source.description());proof.put("startedUtc",job.started.toString());proof.put("completedUtc",Instant.now().toString());
            proof.put("includesEndpoint",true);proof.put("zeroDurationHoldSeconds",intervals==0?1:0);
            proof.put("videoDuration",job.total/(double)job.request.fps());proof.put("fit",job.request.fit());proof.put("fitTime",job.request.fitTime());
            proof.put("fitStrategy",job.request.fit()?job.source.fitStrategy():"Current camera retained without fitting");
            proof.put("uniqueFrameCountMethod","CRC32 of complete pre-encode RGB frames; fingerprints may theoretically collide");
            proof.put("foregroundMethod","RGB difference greater than 8 summed channel levels from the actual viewport clear color (25,31,39)");
            proof.put("visualWarnings",job.nonEmpty==0?List.of(m("media.emptyRender")):List.of());
            proof.put("posterMetadata",fitted.metadata());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(job.stage.resolve("proof.json").toFile(),proof);
            Files.deleteIfExists(log);Files.deleteIfExists(job.stage.resolve("decoder.log"));
            check(job);if(Files.exists(directory))throw new IOException(m("media.targetExists",directory));
            Files.move(job.stage,directory,StandardCopyOption.ATOMIC_MOVE);job.stage=null;job.state="COMPLETE";
        } catch(Throwable failed) {
            job.error=Objects.toString(failed.getMessage(),failed.getClass().getSimpleName());
            job.state=job.cancel||failed instanceof CancellationException?"CANCELLED":"FAILED";
        } finally {
            if(process!=null&&process.isAlive())process.destroyForcibly();
            job.process=null;
            Path stage=job.stage;
            if(stage!=null&&stage.getParent().equals(job.request.directory().getParent())&&stage.getFileName().toString().startsWith(".openbone-media-")) {
                try(var files=Files.walk(stage)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
                catch(IOException ignored){}
            }
            job.finished.complete(null);
        }
    }
    private static int decodedFrames(Job job,Path path,Path log) throws Exception {
        Process decoder=new ProcessBuilder(encoder().toString(),"-hide_banner","-loglevel","error","-nostdin",
                "-i",path.toString(),"-map","0:v:0","-f","null","-","-progress","pipe:1","-nostats")
                .redirectError(log.toFile()).start();job.process=decoder;
        int count=0;
        try(var reader=decoder.inputReader(StandardCharsets.UTF_8)) {
            for(String line;(line=reader.readLine())!=null;)if(line.startsWith("frame="))count=Integer.parseInt(line.substring(6).trim());
        }
        if(!decoder.waitFor(60,TimeUnit.SECONDS)){decoder.destroyForcibly();throw new IOException(m("media.encoderTimeout"));}
        if(decoder.exitValue()!=0)throw new IOException(m("media.encoderFailed",tail(log)));
        job.process=null;check(job);return count;
    }
    private static String tail(Path path) throws IOException {String text=Files.readString(path);return text.substring(Math.max(0,text.length()-4000));}
    private static boolean foreground(int pixel){return Math.abs(((pixel>>>16)&255)-25)+Math.abs(((pixel>>>8)&255)-31)+Math.abs((pixel&255)-39)>8;}
    @Override public synchronized void close(){closed=true;if(active!=null&&!active.terminal()){active.cancel=true;if(active.process!=null)active.process.destroyForcibly();if(active.pending!=null)active.pending.cancel(false);}worker.shutdownNow();}
}
