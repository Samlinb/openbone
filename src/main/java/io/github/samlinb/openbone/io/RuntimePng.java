package io.github.samlinb.openbone.io;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.samlinb.openbone.viewport.ImageAssets;

/**
 * Explicit runtime asset subset. ImageIO grayscale/ICC conversion and GDX Alpha
 * decoding
 * need not agree, so never silently export those inputs as ordinary straight
 * RGBA. Explicit compatibility reads expand indexed PNGs to RGBA while
 * preserving palette colors and transparency; strict reads remain unchanged.
 */
public final class RuntimePng {
    private RuntimePng() {
    }

    private static final byte[] SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
    private static final Set<String> UNMODELED = Set.of("iCCP", "gAMA", "cHRM", "sBIT", "cICP", "mDCv", "cLLi", "acTL",
            "fcTL", "fdAT");

    public static byte[] read(Path path) throws IOException {
        byte[] bytes = ResourcePaths.bytes(path, 64 * 1024 * 1024);
        require(bytes);
        ImageAssets.decodePng(bytes);
        return bytes;
    }

    public static byte[] readCompatible(Path path) throws IOException {
        byte[] bytes = ResourcePaths.bytes(path, 64 * 1024 * 1024);
        require(bytes, true);
        var pixels = ImageAssets.decodePng(bytes);
        if ((bytes[25] & 255) == 3) {
            // Expand palette entries and tRNS alpha without resampling or compositing.
            // The source remains untouched; runtime packages receive plain RGBA bytes.
            var rgba = new java.awt.image.BufferedImage(pixels.getWidth(), pixels.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[pixels.getWidth()];
            for (int y = 0; y < pixels.getHeight(); y++) {
                pixels.getRGB(0, y, pixels.getWidth(), 1, row, 0, pixels.getWidth());
                rgba.setRGB(0, y, pixels.getWidth(), 1, row, 0, pixels.getWidth());
            }
            var out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(rgba, "png", out)) throw new IOException("PNG writer unavailable");
            bytes = out.toByteArray();
            require(bytes);
        }
        return bytes;
    }

    public static void require(byte[] bytes) throws IOException {
        require(bytes, false);
    }

    private static void require(byte[] bytes, boolean allowPalette) throws IOException {
        if (bytes.length < 33 || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8)))
            throw new IOException("Runtime image must be PNG");
        int position = 8;
        boolean header = false, end = false;
        while (position < bytes.length) {
            if (bytes.length - position < 12)
                throw new IOException("Truncated PNG chunk");
            long size = 0;
            for (int i = 0; i < 4; i++)
                size = (size << 8) | (bytes[position + i] & 255L);
            long next = position + 12L + size;
            if (next > bytes.length)
                throw new IOException("Truncated PNG chunk payload");
            String type = new String(bytes, position + 4, 4, StandardCharsets.US_ASCII);
            if (!header) {
                if (!type.equals("IHDR") || size != 13)
                    throw new IOException("PNG requires IHDR first");
                header = true;
                int bits = bytes[position + 16] & 255, color = bytes[position + 17] & 255;
                boolean palette = allowPalette && color == 3 && (bits == 1 || bits == 2 || bits == 4 || bits == 8);
                if (!palette && (bits != 8 || color != 6))
                    throw new IOException("Runtime PNG supports only 8-bit RGBA (IHDR color type 6); found bitDepth="
                            + bits + ", colorType=" + color + ". Convert a copy to RGBA; source is unchanged.");
            } else if (type.equals("IHDR"))
                throw new IOException("Duplicate PNG IHDR");
            if (UNMODELED.contains(type))
                throw new IOException("Unsupported runtime PNG color/animation chunk: " + type
                        + ". Use a plain 8-bit RGBA copy without color-management extensions.");
            position = (int) next;
            if (type.equals("IEND")) {
                if (size != 0 || position != bytes.length)
                    throw new IOException("Invalid PNG IEND/trailing data");
                end = true;
                break;
            }
        }
        if (!end)
            throw new IOException("PNG IEND missing");
    }
}
