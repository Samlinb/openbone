package io.github.samlinb.openbone.io;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;
import static org.junit.jupiter.api.Assertions.*;

class SpineImageImportTest {
    @TempDir Path temp;
    private static final String JSON = """
        {"skeleton":{"spine":"3.8.55","images":"images/"},"bones":[{"name":"root"}],
        "slots":[{"name":"s","bone":"root","attachment":"tile"}],
        "skins":[{"name":"default","attachments":{"s":{"tile":{}}}}],"animations":{}}
        """;

    @Test void editorImportsColorMetadataAndPortableSavePreservesPixelsAndOriginalBytes() throws Exception {
        for (String chunk : List.of("cHRM", "gAMA", "iCCP")) {
            Path root = temp.resolve(chunk);
            Files.createDirectories(root.resolve("images"));
            Files.createDirectories(root.resolve("export"));
            Path project = root.resolve("example.spine"), image = root.resolve("images/tile.png");
            byte[] nativeBytes = {1, 2, 3, 4};
            Files.write(project, nativeBytes);
            Files.writeString(root.resolve("export/example.json"), JSON);
            byte[] original = withColorChunk(chunk);
            Files.write(image, original);
            var expected = ImageAssets.readPng(image);
            assertEquals(0x80ab4e21, expected.getRGB(0, 0));

            var doc = new ProjectDocument(DocumentSnapshot.EMPTY);
            doc.openResolved(project);
            assertTrue(doc.importWarnings().stream().noneMatch(w -> w.contains("image unavailable")),
                    () -> doc.importWarnings().toString());
            var attachment = (RegionAttachment) doc.history().state().model().attachments().get(0);
            assertEquals(8, attachment.width());
            assertEquals(6, attachment.height());
            var assets = new ImageAssets();
            var preview = assets.get(attachment.source());
            assertFalse(preview.missing(), preview.error());
            assertArrayEquals(pixels(expected), pixels(preview.pixels()));

            Path saved = root.resolve("copy.json");
            doc.savePortable(saved);
            var reopened = new ProjectDocument(DocumentSnapshot.EMPTY);
            reopened.openResolved(saved);
            var copied = reopened.history().state().model().attachments().get(0);
            assertArrayEquals(original, Files.readAllBytes(Path.of(copied.source())));
            assertArrayEquals(pixels(expected), pixels(assets.get(copied.source()).pixels()));
            assertArrayEquals(original, Files.readAllBytes(image));
            assertArrayEquals(nativeBytes, Files.readAllBytes(project));

            // Accepting editor images must not loosen the runtime interchange contract.
            assertThrows(IOException.class, () -> RuntimePng.read(image));
            var strict = new SkeletonJsonCodec();
            byte[] strictJson = JSON.replace("3.8.55", SkeletonJsonCodec.VERSION).getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> strict.decode(strictJson, root));
        }
    }

    @Test void unreadableImagesKeepReferencesAndReportTheDecodeReason() throws Exception {
        Files.createDirectories(temp.resolve("images"));
        Path image = temp.resolve("images/tile.png");
        Files.writeString(image, "not a PNG");
        String decodeReason = assertThrows(IOException.class, () -> ImageAssets.readPng(image)).getMessage();
        var codec = new SkeletonJsonCodec();
        var model = codec.decode(JSON.getBytes(StandardCharsets.UTF_8), temp);
        assertEquals(image.toString(), model.attachments().get(0).source());
        assertTrue(codec.warnings().stream().anyMatch(w -> w.contains("image unavailable")
                && w.contains(image.toString()) && w.contains("; reason: " + decodeReason)),
                () -> codec.warnings().toString());
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static byte[] withColorChunk(String type) throws Exception {
        var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80ab4e21);
        image.setRGB(1, 0, 0xff123456);
        var encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", encoded));
        var payload = new ByteArrayOutputStream();
        var values = new DataOutputStream(payload);
        switch (type) {
            case "cHRM" -> {
                for (int value : new int[]{31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000})
                    values.writeInt(value);
            }
            case "gAMA" -> values.writeInt(45455);
            case "iCCP" -> {
                values.write("sRGB".getBytes(StandardCharsets.US_ASCII));
                values.writeByte(0); // Profile name terminator.
                values.writeByte(0); // Deflate compression method.
                try (var compressed = new DeflaterOutputStream(payload)) {
                    compressed.write(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
                }
            }
            default -> throw new IllegalArgumentException(type);
        }
        byte[] original = encoded.toByteArray(), data = payload.toByteArray();
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var output = new ByteArrayOutputStream();
        output.write(original, 0, 33); // Insert after IHDR, before IDAT.
        var stream = new DataOutputStream(output);
        stream.writeInt(data.length);
        stream.write(name);
        stream.write(data);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
        output.write(original, 33, original.length - 33);
        return output.toByteArray();
    }
}
