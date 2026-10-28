package io.github.samlinb.openbone.headless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.io.*;
import java.io.IOException;
import java.nio.file.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Server-owned filesystem boundary. Workspace directories must be provisioned by the operator. */
final class ProjectScope {
    final String id;
    final Path root;

    ProjectScope(String id, Path root) throws IOException {
        this.id = id;
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IOException(m("isolation.config"));
    }

    Path check(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || Files.isSymbolicLink(root) || !root.toRealPath().equals(root))
            throw new IOException(m("isolation.path"));
        // Reject links even when the destination is not yet created, including dangling links.
        Path cursor = root;
        for (Path part : root.relativize(absolute)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor) || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !cursor.toRealPath().startsWith(root)) throw new IOException(m("isolation.path"));
        }
        return absolute;
    }

    void arguments(String name, JsonNode args) throws IOException {
        if (args == null || !args.isObject()) return; // Tool schema reports invalid arguments.
        for (String field : new String[]{"path", "directory"}) {
            if (args.path(field).isTextual()) {
                Path path = check(Path.of(args.get(field).asText()));
                // Export/save helpers create descendants. Reject pre-existing linked descendants too.
                Path tree = field.equals("directory") ? path : path.resolveSibling(path.getFileName() + ".assets");
                check(tree);
                if (Files.isDirectory(tree)) try (var paths = Files.walk(tree)) {
                    for (Path child : paths.toList()) check(child);
                }
            }
        }
        if (args.path("edits").isArray()) for (JsonNode edit : args.get("edits"))
            if (edit.path("pngPath").isTextual()) check(Path.of(edit.get("pngPath").asText()));
    }

    void load(ProjectDocument target, Path path, SpineImportPolicy.Approval approval) throws Exception {
        check(path);
        if (approval != null || SpineProjectSource.isNative(path) || ProjectDocument.isSpineExport(path))
            throw new IOException(m("isolation.nativeOnly"));
        // Decode native JSON only: import adapters may resolve atlas/native resources before publication.
        target.open(path, model -> {
            try {
                for (var attachment : model.attachments()) if (attachment.source() != null)
                    check(ResourcePaths.resolve(path.getParent(), attachment.source()));
                return ResourcePaths.absolute(model, path.getParent());
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }
}
