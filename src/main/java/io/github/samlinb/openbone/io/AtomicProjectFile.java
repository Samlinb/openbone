package io.github.samlinb.openbone.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;

/**
 * Same-directory temp + force + atomic replace. No unsafe non-atomic fallback.
 */
public final class AtomicProjectFile {
    public enum Stage {
        BEFORE_CREATE, AFTER_CREATE, AFTER_WRITE, AFTER_FORCE, BEFORE_REPLACE
    }

    @FunctionalInterface
    public interface Checkpoint {
        void at(Stage stage, Path temporary) throws IOException;
    }

    @FunctionalInterface
    public interface Replacer {
        void replace(Path source, Path destination) throws IOException;
    }

    private final Checkpoint checkpoint;
    private final Replacer replacer;

    public AtomicProjectFile() {
        this((stage, temp) -> {
        }, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING));
    }

    /**
     * Injection seams exercise OS/write/move failure paths without depending on
     * administrator ACLs.
     */
    public AtomicProjectFile(Checkpoint checkpoint, Replacer replacer) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.replacer = Objects.requireNonNull(replacer);
    }

    public void write(Path target, byte[] bytes) throws IOException {
        Path destination = target.toAbsolutePath().normalize(), parent = destination.getParent();
        Path temporary = null;
        try {
            checkpoint.at(Stage.BEFORE_CREATE, null);
            temporary = Files.createTempFile(parent, ".openbone-", ".tmp");
            checkpoint.at(Stage.AFTER_CREATE, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                checkpoint.at(Stage.AFTER_WRITE, temporary);
                channel.force(true);
                checkpoint.at(Stage.AFTER_FORCE, temporary);
            }
            checkpoint.at(Stage.BEFORE_REPLACE, temporary);
            replacer.replace(temporary, destination);
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            throw failure;
        }
    }
}
