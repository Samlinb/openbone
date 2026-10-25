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

    private Path publish(Path directory, DocumentSnapshot d, Path imageBase, String version, boolean compatibleImages) throws IOException {
        RuntimeRange.validate(d);
        Map<EntityId, String> plannedPaths = new LinkedHashMap<>();
        Map<EntityId, byte[]> plannedImages = new LinkedHashMap<>();
        int plannedIndex = 0;
        for (var a : d.attachments())
            if (a.source() != null) {
                plannedPaths.put(a.id(), "region_" + plannedIndex++);
                Path source = ResourcePaths.resolve(imageBase, a.source());
                byte[] bytes = compatibleImages ? RuntimePng.readCompatible(source) : RuntimePng.read(source);
                var pixels = io.github.samlinb.openbone.viewport.ImageAssets.decodePng(bytes);
                var region = d.resources().atlas().get(a.id());
                if (region != null
                        && (pixels.getWidth() != region.pageWidth() || pixels.getHeight() != region.pageHeight()))
                    throw new IOException("Atlas page dimensions changed: " + a.name());
                plannedImages.put(a.id(), bytes);
            }
        byte[] plannedSkeleton = new SkeletonJsonCodec().encode(d, plannedPaths, "images/", version);
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
        Map<EntityId, String> paths = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        StringBuilder atlas = new StringBuilder();
        int index = 0;
        for (var attachment : d.attachments()) {
            if (attachment.source() == null)
                continue;
            var r = attachment;
            String name = "region_" + index++;
            paths.put(r.id(), name);
            byte[] png = plannedImages.get(r.id());
            var pixels = io.github.samlinb.openbone.viewport.ImageAssets.decodePng(png);
            String relative = "images/" + name + ".png";
            write(stage.resolve(relative), png);
            hashes.put(relative, ResourcePaths.sha(png));
            checkpoint.at(Stage.AFTER_IMAGE, stage);
            // Copy an original page per attachment; preserve rotation/trim metadata and PNG
            // bytes.
            var region = d.resources().atlas().get(r.id());
            if (region != null) {
                atlas.append(relative).append('\n').append("size: ").append(region.pageWidth()).append(',')
                        .append(region.pageHeight())
                        .append("\nformat: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\npma: false\n").append(name)
                        .append("\n  rotate: ").append(region.rotated() ? "true" : "false").append("\n  xy: ")
                        .append(region.x()).append(',').append(region.y()).append("\n  size: ").append(region.width())
                        .append(',').append(region.height()).append("\n  orig: ").append(region.originalWidth())
                        .append(',').append(region.originalHeight()).append("\n  offset: ").append(region.offsetX())
                        .append(',').append(region.offsetY()).append("\n  index: -1\n\n");
                continue;
            }
            atlas.append(relative).append('\n').append("size: ").append(pixels.getWidth()).append(',')
                    .append(pixels.getHeight()).append('\n')
                    .append("format: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\npma: false\n").append(name)
                    .append('\n')
                    .append("  rotate: false\n  xy: 0,0\n  size: ").append(pixels.getWidth()).append(',')
                    .append(pixels.getHeight())
                    .append("\n  orig: ").append(pixels.getWidth()).append(',').append(pixels.getHeight())
                    .append("\n  offset: 0,0\n  index: -1\n\n");
        }
        byte[] skeleton = plannedSkeleton;
        write(stage.resolve("skeleton.json"), skeleton);
        hashes.put("skeleton.json", ResourcePaths.sha(skeleton));
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
        return published.resolve("skeleton.json");
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
                || !Set.of(SkeletonJsonCodec.VERSION, SkeletonJsonCodec.VERSION_38).contains(m.path("spine").asText()) || !m.path("files").isObject())
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
