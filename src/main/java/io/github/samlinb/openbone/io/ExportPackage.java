package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.core.model.*;

/**
 * Immutable generation + one atomic entry point. Never replace multiple live
 * files in sequence.
 * Consumers resolve current.json once, then load its immutable generation. Old
 * generations and
 * interrupted .staging-* directories are retained; no automatic garbage
 * collection of user files.
 */
public final class ExportPackage {
    public enum Format { JSON, BINARY, NATIVE }
    public enum Stage {
        AFTER_STAGE_CREATE, AFTER_IMAGE, AFTER_JSON, AFTER_ATLAS, AFTER_MANIFEST, AFTER_GENERATION, BEFORE_ENTRY
    }

    @FunctionalInterface
    public interface Checkpoint {
        void at(Stage stage, Path stageDirectory) throws IOException;
    }

    private final Checkpoint checkpoint;
    private final AtomicProjectFile entry;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ExportPackage() {
        this((s, p) -> {
        }, new AtomicProjectFile());
    }

    public ExportPackage(Checkpoint checkpoint, AtomicProjectFile entry) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.entry = Objects.requireNonNull(entry);
    }

    public Path publish(Path directory, DocumentSnapshot d, Path imageBase) throws IOException {
        return publish(directory, d, imageBase, SkeletonJsonCodec.VERSION, false);
    }

    public Path publish(Path directory, DocumentSnapshot d, Path imageBase, String version) throws IOException {
        return publish(directory, d, imageBase, version, true);
    }

    public Path publish(Path directory, DocumentSnapshot d, Path imageBase, String version, Format format) throws IOException {
        return publish(directory,d,imageBase,version,true,Objects.requireNonNull(format));
    }

    private Path publish(Path directory, DocumentSnapshot d, Path imageBase, String version, boolean compatibleImages) throws IOException {
        return publish(directory,d,imageBase,version,compatibleImages,Format.JSON);
    }

    private Path publish(Path directory, DocumentSnapshot d, Path imageBase, String version, boolean compatibleImages, Format format) throws IOException {
        RuntimeRange.validate(d);
        boolean legacyAtlas = SpineVersion.target(version) == SpineVersion.V38;
        Map<EntityId, String> plannedPaths = new LinkedHashMap<>();
        record SourceImage(byte[] bytes, int width, int height, String digest) {}
        record PlannedImage(byte[] bytes, int width, int height, String digest) {}
        Map<Path,SourceImage> sources = new HashMap<>();
        Map<EntityId, PlannedImage> plannedImages = new LinkedHashMap<>();
        int plannedIndex = 0;
        for (var a : d.attachments())
            if (a.source() != null) {
                plannedPaths.put(a.id(), "region_" + plannedIndex++);
                Path source = ResourcePaths.resolve(imageBase, a.source());
                SourceImage image=sources.get(source);
                if(image==null) {
                    byte[] sourceBytes=compatibleImages ? RuntimePng.readCompatible(source) : RuntimePng.read(source);
                    var decoded=io.github.samlinb.openbone.viewport.ImageAssets.decodePng(sourceBytes);
                    image=new SourceImage(sourceBytes,decoded.getWidth(),decoded.getHeight(),ResourcePaths.sha(sourceBytes));
                    sources.put(source,image);
                }
                byte[] bytes=image.bytes();int width=image.width(),height=image.height();String digest=image.digest();
                var region = d.resources().atlas().get(a.id());
                if (legacyAtlas && region != null && region.premultiplied())
                    throw new IOException("Spine 3.8 atlas cannot declare premultiplied alpha; convert the texture to straight alpha before export.");
                if (region != null
                        && (width != region.pageWidth() || height != region.pageHeight()))
                    throw new IOException("Atlas page dimensions changed: " + a.name());
                if(format==Format.NATIVE&&region!=null) {
                    if (region.premultiplied()) throw new IOException(io.github.samlinb.openbone.i18n.M.m("atlas.nativePmaConversion"));
                    var output=new ByteArrayOutputStream();
                    var pixels=AtlasSplitter.unpack(io.github.samlinb.openbone.viewport.ImageAssets.decodePng(bytes),region);
                    javax.imageio.ImageIO.write(pixels,"png",output);
                    bytes=output.toByteArray();
                    digest=ResourcePaths.sha(bytes);width=pixels.getWidth();height=pixels.getHeight();
                }
                plannedImages.put(a.id(), new PlannedImage(bytes,width,height,digest));
            }
        byte[] plannedSkeleton = new SkeletonJsonCodec().encode(d, plannedPaths, "images/", version);
        byte[] plannedBinary = format==Format.BINARY?SpineBinaryWriter.encode(d,plannedPaths,"images/",version):null;
        if(format==Format.NATIVE)SpineCliBridge.requireAvailable();
        Path root = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root))
            throw new IOException("Export directory may not be a symlink");
        if (Files.exists(root.resolve("current.json")))
            resolve(root.resolve("current.json")); // refuse unrelated or damaged entry
        Files.createDirectories(root);
        String id = UUID.randomUUID().toString(), generation = "generation-" + id;
        Path stage = root.resolve(".staging-" + id);
        Files.createDirectory(stage);
        checkpoint.at(Stage.AFTER_STAGE_CREATE, stage);
        Files.createDirectory(stage.resolve("images"));
        Map<String, String> hashes = new LinkedHashMap<>();
        StringBuilder atlas = new StringBuilder();
        // Region names and skeleton ordering stay attachment-based. Only pages
        // whose full bytes and all emitted page settings match can be shared.
        record PageKey(String digest, int width, int height, boolean pma,
                       String format, String filter, String wrap) {}
        record Entry(String name, AtlasRegion region, int width, int height) {}
        class Page {
            final String relative; final byte[] png; final PageKey key; final List<Entry> entries = new ArrayList<>();
            Page(String relative, byte[] png, PageKey key) { this.relative=relative; this.png=png; this.key=key; }
        }
        Map<PageKey,Page> pages = new LinkedHashMap<>();
        int index = 0;
        for (var attachment : d.attachments()) {
            if (attachment.source() == null)
                continue;
            var r = attachment;
            String name = "region_" + index++;
            var planned=plannedImages.get(r.id());byte[] png=planned.bytes();
            var region = format==Format.NATIVE?null:d.resources().atlas().get(r.id());
            var key = new PageKey(planned.digest(),planned.width(),planned.height(),region!=null&&region.premultiplied(),"RGBA8888","Nearest,Nearest","none");
            Page page=pages.get(key);
            if(page==null) { page=new Page("images/"+name+".png",png,key);pages.put(key,page); }
            else if(!Arrays.equals(page.png,png)) throw new IOException("PNG digest collision; refusing to combine pages");
            page.entries.add(new Entry(name,region,planned.width(),planned.height()));
        }
        for (Page page : pages.values()) {
            write(stage.resolve(page.relative),page.png);hashes.put(page.relative,page.key.digest());
            checkpoint.at(Stage.AFTER_IMAGE,stage);
            atlas.append(page.relative).append('\n').append("size: ").append(page.key.width()).append(',').append(page.key.height())
                    .append("\nformat: ").append(page.key.format()).append("\nfilter: ").append(page.key.filter())
                    .append("\nrepeat: ").append(page.key.wrap()).append('\n');
            if (!legacyAtlas) atlas.append("pma: ").append(page.key.pma()).append('\n');
            for(Entry e:page.entries) {
                var region=e.region();
                atlas.append(e.name());
                if(region!=null) atlas.append("\n  rotate: ").append(region.degrees()==0 ? "false" : region.degrees()==90 ? "true" : Integer.toString(region.degrees()))
                        .append("\n  xy: ").append(region.x()).append(',').append(region.y()).append("\n  size: ").append(region.width())
                        .append(',').append(region.height()).append("\n  orig: ").append(region.originalWidth())
                        .append(',').append(region.originalHeight()).append("\n  offset: ").append(region.offsetX()).append(',').append(region.offsetY());
                else atlas.append("\n  rotate: false\n  xy: 0,0\n  size: ").append(e.width()).append(',').append(e.height())
                        .append("\n  orig: ").append(e.width()).append(',').append(e.height()).append("\n  offset: 0,0");
                atlas.append("\n  index: -1\n");
            }
            atlas.append('\n');
        }
        byte[] skeleton = plannedSkeleton;
        write(stage.resolve("skeleton.json"), skeleton);
        hashes.put("skeleton.json", ResourcePaths.sha(skeleton));
        if(plannedBinary!=null){write(stage.resolve("skeleton.skel"),plannedBinary);hashes.put("skeleton.skel",ResourcePaths.sha(plannedBinary));}
        if(format==Format.NATIVE){
            SpineCliBridge.importProject(stage.resolve("skeleton.json"),stage.resolve("skeleton.spine"),version);
            hashes.put("skeleton.spine",ResourcePaths.sha(ResourcePaths.bytes(stage.resolve("skeleton.spine"),64*1024*1024)));
        }
        checkpoint.at(Stage.AFTER_JSON, stage);
        byte[] atlasBytes = atlas.toString().getBytes(StandardCharsets.UTF_8);
        write(stage.resolve("skeleton.atlas"), atlasBytes);
        hashes.put("skeleton.atlas", ResourcePaths.sha(atlasBytes));
        checkpoint.at(Stage.AFTER_ATLAS, stage);
        byte[] manifest = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                Map.of("format", "openbone-export-generation-1", "spine", version, "files", hashes));
        write(stage.resolve("manifest.json"), manifest);
        checkpoint.at(Stage.AFTER_MANIFEST, stage);
        Path published = root.resolve(generation);
        Files.move(stage, published, StandardCopyOption.ATOMIC_MOVE);
        checkpoint.at(Stage.AFTER_GENERATION, published);
        byte[] pointer = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(Map.of("format", "openbone-export-1", "generation", generation, "json",
                        generation + "/skeleton.json", "atlas", generation + "/skeleton.atlas", "manifestSHA256",
                        ResourcePaths.sha(manifest)));
        checkpoint.at(Stage.BEFORE_ENTRY, published);
        entry.write(root.resolve("current.json"), pointer);
        return published.resolve(format==Format.BINARY?"skeleton.skel":format==Format.NATIVE?"skeleton.spine":"skeleton.json");
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try (var c = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining())
                c.write(b);
            c.force(true);
        }
    }

    /**
     * Resolve and verify all package bytes before an editing import or external
     * loader uses them.
     */
    public static Path resolve(Path current) throws IOException {
        var n = new ProjectCodec().parseTree(ResourcePaths.bytes(current, ProjectCodec.MAX_BYTES));
        ProjectCodec.fields(n, "$", "format", "generation", "json", "atlas", "manifestSHA256");
        if (!ProjectCodec.text(n, "format", "$").equals("openbone-export-1"))
            throw new IOException("Not a Openbone package entry");
        String generation = ProjectCodec.text(n, "generation", "$");
        if (!generation.matches("generation-[0-9a-f-]{36}"))
            throw new IOException("Unsafe generation");
        if (!ProjectCodec.text(n, "json", "$").equals(generation + "/skeleton.json")
                || !ProjectCodec.text(n, "atlas", "$").equals(generation + "/skeleton.atlas"))
            throw new IOException("Invalid package filenames");
        Path dir = ResourcePaths.contained(current.toAbsolutePath().getParent(), generation);
        Path manifestPath = ResourcePaths.contained(dir, "manifest.json");
        byte[] manifest = ResourcePaths.bytes(manifestPath, ProjectCodec.MAX_BYTES);
        if (!ResourcePaths.sha(manifest).equals(ProjectCodec.text(n, "manifestSHA256", "$")))
            throw new IOException("Package manifest SHA mismatch");
        var m = new ProjectCodec().parseTree(manifest);
        ProjectCodec.fields(m, "$", "format", "spine", "files");
        if (!m.path("format").asText().equals("openbone-export-generation-1")
                || SpineVersion.identify(m.path("spine").asText()).isEmpty() || !m.path("files").isObject())
            throw new IOException("Invalid generation manifest");
        if (!m.get("files").has("skeleton.json") || !m.get("files").has("skeleton.atlas"))
            throw new IOException("Incomplete generation manifest");
        for (var it = m.get("files").fields(); it.hasNext();) {
            var e = it.next();
            Path p = ResourcePaths.contained(dir, e.getKey());
            if (!e.getValue().isTextual()
                    || !ResourcePaths.sha(ResourcePaths.bytes(p, 64 * 1024 * 1024)).equals(e.getValue().textValue()))
                throw new IOException("Package resource SHA mismatch: " + e.getKey());
        }
        return dir.resolve("skeleton.json");
    }
}
