package io.github.samlinb.openbone.io;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Resolves an explicitly selected native project to an existing matching export
 * snapshot.
 */
public record SpineProjectSource(Path project, Path json, Path imageBase) {
    public static boolean isNative(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".spine");
    }

    public static SpineProjectSource resolve(Path input) throws IOException {
        Path project = input.toAbsolutePath().normalize();
        if (!isNative(project) || !Files.isRegularFile(project))
            throw new IOException(m("spineProject.missing", project));
        String name = project.getFileName().toString();
        String stem = name.substring(0, name.length() - 6);
        Path root = project.getParent();
        for (Path candidate : List.of(root.resolve(stem + ".json"), root.resolve("export").resolve(stem + ".json"))) {
            if (!Files.isRegularFile(candidate))
                continue;
            byte[] bytes = ResourcePaths.bytes(candidate, ProjectCodec.MAX_BYTES);
            var tree = new ProjectCodec().parseTree(bytes);
            if (!tree.path("skeleton").isObject() || !tree.path("skeleton").path("spine").isTextual())
                throw new ProjectFormatException(m("spineProject.invalidSnapshot", candidate));
            Path base = candidate.getParent();
            String images = tree.path("skeleton").path("images").asText("");
            if (!images.isBlank()) {
                // A copied examples/export directory commonly retains ./images/ while images
                // live beside the .spine.
                Path local = ResourcePaths.contained(base, images);
                if (!Files.isDirectory(local) && Files.isDirectory(ResourcePaths.contained(root, images)))
                    base = root;
            }
            return new SpineProjectSource(project, candidate, base);
        }
        throw new ProjectFormatException(m("spineProject.noSnapshot", project,
                root.resolve(stem + ".json"), root.resolve("export").resolve(stem + ".json")));
    }
}
