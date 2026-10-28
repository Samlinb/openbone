package io.github.samlinb.openbone.headless;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.mcp.EditorMcpTools;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static io.github.samlinb.openbone.i18n.M.m;

/** File protocol to a separate runtime JVM. No GL classes are initialized in this process. */
final class RuntimePreviewBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path runtimeJar;
    private final Path work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "openbone-runtime-export"); thread.setDaemon(true); return thread;
    });
    private volatile Process running;
    private java.io.BufferedWriter commands;
    private String cachedRevision;
    private Path cachedEntry;
    private JsonNode cachedCamera;
    private boolean closed;
    private final Set<CompletableFuture<EditorMcpTools.Preview>> pending = ConcurrentHashMap.newKeySet();

    RuntimePreviewBridge(Path runtimeJar) throws IOException {
        this.runtimeJar = runtimeJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.runtimeJar)) throw new IOException(m("headless.runtimeMissing", this.runtimeJar));
        work = Files.createTempDirectory("openbone-runtime-");
    }

    synchronized CompletableFuture<EditorMcpTools.Preview> capture(DocumentSnapshot snapshot, Path base,
            EntityId animation, double time, String revision, EditorMcpTools.CaptureOptions options) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException(m("headless.closed")));
        String name = snapshot.animation(animation).name();
        var result = new CompletableFuture<EditorMcpTools.Preview>();
        pending.add(result);
        Future<?> task = worker.submit(() -> {
            Process process = null;
            Path requestDir = null;
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (!revision.equals(cachedRevision)) {
                    Path destination = work.resolve("package-" + UUID.randomUUID());
                    new ExportPackage().publish(destination, snapshot, base, "4.2.22", ExportPackage.Format.JSON);
                    cachedEntry = destination.resolve("current.json");
                    cachedRevision = revision;
                    cachedCamera = null;
                }
                Path skeleton = ExportPackage.resolve(cachedEntry);
                String skeletonHash = ResourcePaths.sha(Files.readAllBytes(skeleton));
                requestDir = Files.createDirectory(work.resolve("capture-" + UUID.randomUUID()));
                var request = new LinkedHashMap<String,Object>();
                request.put("protocol",1); request.put("entry",cachedEntry.toString());
                request.put("skeletonSHA256",skeletonHash); request.put("sourceRevision",revision);
                request.put("animation",name); request.put("animationId",animation.value()); request.put("time",time);
                request.put("width",768); request.put("height",768);
                request.put("showBones",Boolean.TRUE.equals(options.showBones()));
                request.put("showGrid",Boolean.TRUE.equals(options.showGrid()));
                request.put("fit",options.fit()); request.put("fitAnimation",options.fitAnimation());
                if (options.framingBounds() != null) request.put("bounds",options.framingBounds());
                if (cachedCamera != null) request.put("camera",cachedCamera);
                Path input = requestDir.resolve("request.json"); JSON.writeValue(input.toFile(),request);
                process = runtime();
                commands.write(JSON.writeValueAsString(Map.of("request",input.toString())));commands.newLine();commands.flush();
                Path receiptFile = requestDir.resolve("result.json"), imageFile = requestDir.resolve("preview.png");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(!Files.isRegularFile(receiptFile)) {
                    if(Files.isRegularFile(requestDir.resolve("error.json"))) {
                        var error=JSON.readTree(ResourcePaths.bytes(requestDir.resolve("error.json"),65536));
                        throw new IOException(m("headless.runtimeFailed",work.resolve("runtime.log"))+" "+error.path("error").asText());
                    }
                    if(!process.isAlive())throw new IOException(m("headless.runtimeFailed",work.resolve("runtime.log")));
                    if(System.nanoTime()>deadline)throw new IOException(m("headless.runtimeTimeout"));
                    Thread.sleep(20);
                }
                JsonNode receipt = JSON.readTree(ResourcePaths.bytes(receiptFile,65536));
                if (receipt.path("protocol").asInt() != 1 || !receipt.path("sourceRevision").asText().equals(revision)
                        || !receipt.path("skeletonSHA256").asText().equals(skeletonHash)
                        || !receipt.path("animationId").asText().equals(animation.value()) || receipt.path("time").asDouble(-1) != time)
                    throw new IOException(m("headless.runtimeIdentity"));
                byte[] bytes = ResourcePaths.bytes(imageFile,16*1024*1024);
                if (!ResourcePaths.sha(bytes).equals(receipt.path("imageSHA256").asText())) throw new IOException(m("headless.runtimeIdentity"));
                var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (image == null || image.getWidth() != 768 || image.getHeight() != 768) throw new IOException(m("headless.runtimeIdentity"));
                Map<String,Object> metadata = new LinkedHashMap<>();
                receipt.fields().forEachRemaining(e -> metadata.put(e.getKey(),JSON.convertValue(e.getValue(),Object.class)));
                metadata.put("revision",revision); metadata.put("previewBackend","openbone-runtime");
                cachedCamera = receipt.path("camera").deepCopy();
                // Only delete known files in this uniquely created request directory.
                try { Files.deleteIfExists(imageFile);Files.deleteIfExists(receiptFile);Files.deleteIfExists(input);Files.deleteIfExists(requestDir); }
                catch(IOException retainedForDiagnosis) { /* The verified image remains valid. */ }
                result.complete(new EditorMcpTools.Preview(image,metadata));
            } catch (Exception failure) {
                if(process!=null&&process.isAlive())process.destroyForcibly();running=null;commands=null;
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((value, failure) -> { pending.remove(result); if (result.isCancelled()) task.cancel(true); });
        return result;
    }

    private Process runtime() throws IOException {
        if(running!=null&&running.isAlive())return running;
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        running=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Dopenbone.lang="+io.github.samlinb.openbone.i18n.M.tag(io.github.samlinb.openbone.i18n.M.locale()),
                "-jar",runtimeJar.toString(),"--worker").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("runtime.log").toFile())).start();
        commands=new java.io.BufferedWriter(new java.io.OutputStreamWriter(running.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
        return running;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.forEach(future -> future.cancel(true));
        worker.shutdownNow();
        var process = running;
        if (process != null) {
            try {
                process.getOutputStream().close();
                if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();
            } catch(IOException failure) {process.destroyForcibly();}
            catch(InterruptedException interrupted) {process.destroyForcibly();Thread.currentThread().interrupt();}
        }
        // Keep temporary artifacts and logs for diagnosis; never delete user export directories.
    }
}
