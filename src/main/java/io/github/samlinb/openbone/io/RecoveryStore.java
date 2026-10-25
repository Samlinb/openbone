package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * One atomically replaced snapshot per session. OS locks exclude live JVMs and
 * competing recoverers.
 * No writes to the original project. Snapshots contain committed model state,
 * not previews or fields.
 * Locks are intentionally retained while the recovery chooser is open.
 */
public final class RecoveryStore implements AutoCloseable {
    public static final int INTERVAL_MS = 15000;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private final Path directory;
    private final LockedSession session;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectCodec codec = new ProjectCodec();
    private final AtomicProjectFile atomic;

    public RecoveryStore(Path root) throws IOException {
        this(root, new AtomicProjectFile());
    }

    public RecoveryStore(Path root, AtomicProjectFile atomic) throws IOException {
        Files.createDirectories(root);
        directory = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
        var acquired = LockedSession.acquire(FileChannel.open(directory.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE), FileChannel::lock);
        if (acquired == null)
            throw new IOException(m("recoverystore.0001"));
        session = acquired;
        this.atomic = atomic;
    }

    public Path file() {
        return directory.resolve("recovery.json");
    }

    public void snapshot(DocumentSnapshot model, Path original, Path base) throws IOException {
        byte[] project = codec.encode(ResourcePaths.absolute(model, base));
        ObjectNode node = mapper.createObjectNode();
        node.put("recoveryVersion", 1);
        node.put("capturedAt", System.currentTimeMillis());
        node.put("originalPath", original == null ? "" : original.toAbsolutePath().normalize().toString());
        node.put("projectSHA256", hash(project));
        node.set("project", mapper.readTree(project));
        byte[] bytes = mapper.writeValueAsBytes(node);
        if (bytes.length > MAX_BYTES)
            throw new IOException(m("recoverystore.0002"));
        atomic.write(file(), bytes);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file());
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static final class Candidate implements AutoCloseable {
        public final Path file;
        public final String originalPath, error;
        public final long capturedAt;
        public final DocumentSnapshot model;
        private final LockedSession session;

        private Candidate(Path dir, LockedSession session) {
            this.session = session;
            file = dir.resolve("recovery.json");
            String origin = "", failure = null;
            long time = 0;
            DocumentSnapshot decoded = null;
            try {
                if (Files.size(file) > MAX_BYTES)
                    throw new IOException(m("recoverystore.0003"));
                var mapper = new ObjectMapper()
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                JsonNode n = mapper.readTree(Files.readAllBytes(file));
                if (n == null || !n.isObject() || n.size() != 5 || !n.path("recoveryVersion").isIntegralNumber()
                        || !n.path("recoveryVersion").canConvertToInt() || n.path("recoveryVersion").intValue() != 1
                        || !n.path("capturedAt").isIntegralNumber() || !n.path("originalPath").isTextual()
                        || !n.path("projectSHA256").isTextual() || !n.path("project").isObject())
                    throw new IOException(m("recoverystore.0004"));
                origin = n.path("originalPath").textValue();
                time = n.path("capturedAt").longValue();
                var codec = new ProjectCodec();
                decoded = codec.decode(mapper.writeValueAsBytes(n.path("project")));
                if (!hash(codec.encode(decoded)).equals(n.path("projectSHA256").textValue()))
                    throw new IOException(m("recoverystore.0005"));
            } catch (Exception invalid) {
                failure = invalid.getMessage();
                decoded = null;
            }
            originalPath = origin;
            capturedAt = time;
            model = decoded;
            error = failure;
        }

        public void discard() throws IOException {
            Files.deleteIfExists(file);
        }

        @Override
        public String toString() {
            return (model == null ? m("recoverystore.0006") : "")
                    + (originalPath.isBlank() ? m("editorframe.0065") : originalPath) + " · "
                    + (capturedAt == 0 ? file.getParent().getFileName() : new java.util.Date(capturedAt));
        }

        @Override
        public void close() throws IOException {
            session.close();
        }
    }

    /**
     * Session I/O failures are diagnostics, not candidates that may be discarded
     * without a lock.
     */
    public record ScanIssue(Path path, String operation, String detail) {
        @Override
        public String toString() {
            return path + " · " + operation + ": " + detail;
        }
    }

    @FunctionalInterface
    private interface Acquire {
        FileLock lock(FileChannel channel) throws IOException;
    }

    private static final class LockedSession implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockedSession(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static LockedSession acquire(FileChannel channel, Acquire acquire) throws IOException {
            try {
                FileLock held;
                try {
                    held = acquire.lock(channel);
                } catch (OverlappingFileLockException busy) {
                    channel.close();
                    return null;
                }
                if (held == null) {
                    channel.close();
                    return null;
                }
                return new LockedSession(channel, held);
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            // Even a release failure must close the channel; try-with-resources retains
            // suppression.
            try (channel) {
                if (lock.isValid())
                    lock.release();
            }
        }
    }

    /**
     * NIO seam for deterministic permission/list/close failures; production uses
     * these exact defaults.
     */
    interface ScanIo {
        default Stream<Path> sessions(Path root) throws IOException {
            return Files.list(root);
        }

        default BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        default FileChannel openLock(Path file) throws IOException {
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }

        default FileLock tryLock(FileChannel channel) throws IOException {
            return channel.tryLock();
        }

        default DirectoryStream<Path> temps(Path directory) throws IOException {
            return Files.newDirectoryStream(directory, ".openbone-*.tmp");
        }

        default DirectoryStream<Path> contents(Path directory) throws IOException {
            return Files.newDirectoryStream(directory);
        }

        default void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }

    public static List<Candidate> candidates(Path root) throws IOException {
        return candidates(root, issue -> System.err.println("Recovery scan: " + issue));
    }

    public static List<Candidate> candidates(Path root, Consumer<ScanIssue> issues) throws IOException {
        return candidates(root, issues, new ScanIo() {
        });
    }

    static List<Candidate> candidates(Path root, Consumer<ScanIssue> issues, ScanIo io) throws IOException {
        Objects.requireNonNull(issues);
        List<Candidate> result = new ArrayList<>();
        try {
            Stream<Path> opened;
            try {
                opened = io.sessions(root);
            } catch (NoSuchFileException absent) {
                return List.of();
            }
            try (var dirs = opened) {
                for (Path dir : dirs.sorted().toList()) {
                    // Only actual UUID session directories may be cleaned; never follow session
                    // links.
                    try {
                        UUID.fromString(dir.getFileName().toString());
                    } catch (IllegalArgumentException other) {
                        continue;
                    }
                    try {
                        if (io.attributes(dir).isDirectory())
                            scanSession(dir, result, issues, io);
                    } catch (IOException | DirectoryIteratorException failure) {
                        issues.accept(issue(dir, m("recoverystore.0007"), failure));
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            closeFailedScan(result, failure);
            throw failure;
        }
    }

    private static void closeFailedScan(List<Candidate> result, Throwable failure) {
        for (var candidate : result)
            try {
                candidate.close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure)
                    failure.addSuppressed(cleanup);
            }
    }

    private static ScanIssue issue(Path path, String operation, Exception failure) {
        return new ScanIssue(path, operation, failure.toString());
    }

    private static void scanSession(Path dir, List<Candidate> result, Consumer<ScanIssue> issues, ScanIo io)
            throws IOException {
        LockedSession held = LockedSession.acquire(io.openLock(dir.resolve("session.lock")), io::tryLock);
        if (held == null)
            return; // Active JVMs and competing claimants remain excluded by the OS lock.
        Throwable failure = null;
        try {
            cleanTemps(dir, issues, io);
            boolean snapshot;
            try {
                if (!io.attributes(dir.resolve("recovery.json")).isRegularFile())
                    throw new IOException(m("recoverystore.0008"));
                snapshot = true;
            } catch (NoSuchFileException absent) {
                snapshot = false;
            }
            if (snapshot) {
                var candidate = new Candidate(dir, held);
                result.add(candidate);
                held = null; // Transfer ownership only after construction AND result publication succeed.
            } else {
                // Empty-session cleanup is conservative: unknown contents and directories stay
                // intact.
                boolean empty = true;
                try (var contents = io.contents(dir)) {
                    for (Path p : contents)
                        if (!p.getFileName().toString().equals("session.lock")) {
                            empty = false;
                            break;
                        }
                }
                held.close();
                held = null;
                if (empty) {
                    io.delete(dir.resolve("session.lock"));
                    try {
                        io.delete(dir);
                    } catch (DirectoryNotEmptyException keepUnknown) {
                    }
                }
            }
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (held != null)
                try {
                    held.close();
                } catch (IOException cleanup) {
                    if (failure != null)
                        failure.addSuppressed(cleanup);
                    else
                        throw cleanup;
                }
        }
    }

    private static void cleanTemps(Path dir, Consumer<ScanIssue> issues, ScanIo io) {
        try (var junk = io.temps(dir)) {
            for (Path p : junk)
                try {
                    if (!io.attributes(p).isRegularFile())
                        throw new IOException(m("recoverystore.0009"));
                    io.delete(p);
                } catch (IOException failure) {
                    issues.accept(issue(p, m("recoverystore.0010"), failure));
                }
        } catch (IOException | DirectoryIteratorException failure) {
            issues.accept(issue(dir, m("recoverystore.0011"), failure));
        }
    }

    @Override
    public void close() throws IOException {
        session.close();
    }
}
