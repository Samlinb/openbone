package io.github.samlinb.openbone.viewport;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * CPU images retain straight alpha for identical drawing/picking. No GPU
 * ownership here.
 */
public final class ImageAssets {
    public record Image(Path path, BufferedImage pixels, String error) {
        public boolean missing() {
            return error != null;
        }
    }

    private final Map<Path, Image> cache = new HashMap<>();
    private Path base = Path.of("").toAbsolutePath();

    public void base(Path directory) {
        base = directory.toAbsolutePath().normalize();
        cache.clear();
    }

    public Path resolve(String source) {
        try {
            return resolveValid(source);
        } catch (InvalidPathException e) {
            return invalidKey(source);
        }
    }

    private Path resolveValid(String source) {
        Path p = Path.of(source);
        return (p.isAbsolute() ? p : base.resolve(p)).normalize();
    }

    private Path invalidKey(String source) {
        return base.resolve(".openbone-invalid-reference-" + Integer.toUnsignedString(source.hashCode()));
    }

    public Image get(String source) {
        Path path;
        try {
            path = resolveValid(source);
        } catch (InvalidPathException e) {
            return cache.computeIfAbsent(invalidKey(source), p -> new Image(p, placeholder(), m("imageassets.0001")));
        }
        return cache.computeIfAbsent(path, p -> {
            try {
                return new Image(p, readPng(p), null);
            } catch (Exception e) {
                System.err.println("Image unavailable: " + p + ": " + e);
                return new Image(p, placeholder(), e.getMessage());
            }
        });
    }

    public void reload() {
        cache.clear();
    }

    public void retain(Set<Path> paths) {
        cache.keySet().retainAll(paths);
    }

    public int size() {
        return cache.size();
    }

    public static BufferedImage readPng(Path path) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            return readPng(in);
        }
    }

    public static BufferedImage decodePng(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            return readPng(in);
        }
    }

    private static BufferedImage readPng(ImageInputStream in) throws IOException {
        if (in == null)
            throw new IOException(m("imageassets.0002"));
        var readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext())
            throw new IOException(m("imageassets.0003"));
        var r = readers.next();
        try {
            r.setInput(in);
            if (!r.getFormatName().equalsIgnoreCase("png"))
                throw new IOException(m("imageassets.0003"));
            int w = r.getWidth(0), h = r.getHeight(0);
            if (w > 8192 || h > 8192 || (long) w * h > 16_777_216)
                throw new IOException(m("imageassets.0004"));
            return r.read(0);
        } finally {
            r.dispose();
        }
    }

    private static BufferedImage placeholder() {
        var b = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
                b.setRGB(x, y, ((x / 8 + y / 8) % 2 == 0) ? 0xffff40d0 : 0xff502048);
        return b;
    }
}
