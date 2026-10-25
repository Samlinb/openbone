package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;

/**
 * File-system adaptation stays out of the pure model. Never modify or delete
 * source images.
 */
public final class ResourcePaths {
    private ResourcePaths() {
    }

    public static Path resolve(Path base, String source) throws ProjectFormatException {
        try {
            Path p = Path.of(source);
            return (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw ProjectCodec.bad("image source", "Invalid path: " + source);
        }
    }

    /**
     * Runtime imports allow only contained relative paths, including through real
     * symlinks.
     */
    public static Path contained(Path base, String relative) throws IOException {
        if (relative.isBlank() || relative.contains("\\") || relative.contains(":"))
            throw ProjectCodec.bad("image path", "Unsafe relative path: " + relative);
        Path path = Path.of(relative);
        if (path.isAbsolute() || path.normalize().startsWith(".."))
            throw ProjectCodec.bad("image path", "Path escapes resource directory: " + relative);
        Path root = base.toAbsolutePath().normalize(), result = root.resolve(path).normalize();
        if (!result.startsWith(root))
            throw ProjectCodec.bad("image path", "Path escapes resource directory");
        if (Files.exists(result) && !result.toRealPath().startsWith(root.toRealPath()))
            throw ProjectCodec.bad("image path", "Symlink escapes resource directory");
        return result;
    }

    public static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static byte[] png(Path p) throws IOException {
        // Decode the same byte snapshot that will be copied (no check/copy TOCTOU).
        byte[] bytes = bytes(p, 64 * 1024 * 1024);
        ImageAssets.decodePng(bytes);
        return bytes;
    }

    public static byte[] bytes(Path p, int max) throws IOException {
        if (Files.size(p) > max)
            throw new IOException("File exceeds " + max + " bytes: " + p);
        byte[] data;
        try (var in = Files.newInputStream(p)) {
            data = in.readNBytes(max + 1);
        }
        if (data.length > max)
            throw new IOException("File exceeds " + max + " bytes: " + p);
        return data;
    }

    public static DocumentSnapshot absolute(DocumentSnapshot d, Path base) throws ProjectFormatException {
        List<Attachment> images = new ArrayList<>();
        for (var a : d.attachments()) {
            var r = a;
            if (a.source() == null) {
                images.add(a);
                continue;
            }
            images.add(
                    io.github.samlinb.openbone.application.MeshEdits.source(r, resolve(base, r.source()).toString()));
        }
        return new DocumentSnapshot(d.bones(), d.slots(), images, d.animations(), d.constraints(), d.resources());
    }

    /**
     * New content is written before JSON; old content is never overwritten, even
     * after SaveAs.
     */
    public static DocumentSnapshot portable(DocumentSnapshot d, Path base, Path target) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        String folder = target.getFileName() + ".assets";
        Path dir = parent.resolve(folder);
        List<Attachment> images = new ArrayList<>();
        for (var a : d.attachments()) {
            if (a.source() == null) {
                images.add(a);
                continue;
            }
            var r = a;
            byte[] bytes = png(resolve(base, r.source()));
            String name = sha(bytes) + ".png";
            if (Files.isSymbolicLink(dir))
                throw new IOException("Resource directory may not be a symlink: " + dir);
            Files.createDirectories(dir);
            if (!dir.toRealPath().startsWith(parent.toRealPath()))
                throw new IOException("Resource directory escapes project parent: " + dir);
            Path dest = dir.resolve(name);
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(dest) || !Arrays.equals(bytes, Files.readAllBytes(dest)))
                    throw new IOException("Content-addressed resource collision: " + dest);
            } else
                new AtomicProjectFile().write(dest, bytes);
            images.add(io.github.samlinb.openbone.application.MeshEdits.source(r, folder + "/" + name));
        }
        return new DocumentSnapshot(d.bones(), d.slots(), images, d.animations(), d.constraints(), d.resources());
    }
}
