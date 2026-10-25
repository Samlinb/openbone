package io.github.samlinb.openbone.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Application IO facade: publish loaded state / save point and path only after
 * complete success.
 */
public final class ProjectDocument {
    private final CommandHistory history;
    private final ProjectCodec codec;
    private final AtomicProjectFile files;
    private Path path;
    /**
     * Origin of relative in-memory links; SaveAs changes the destination, not this
     * origin.
     */
    private Path resourceBase = Path.of("").toAbsolutePath();
    private boolean portable;
    private java.util.List<String> importWarnings = java.util.List.of();

    public java.util.List<String> importWarnings() { return importWarnings; }

    public ProjectDocument(DocumentSnapshot initial) {
        this(initial, new ProjectCodec(), new AtomicProjectFile());
    }

    public ProjectDocument(DocumentSnapshot initial, ProjectCodec codec, AtomicProjectFile files) {
        history = new CommandHistory(initial);
        this.codec = Objects.requireNonNull(codec);
        this.files = Objects.requireNonNull(files);
    }

    public CommandHistory history() {
        return history;
    }

    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    public void open(Path source) throws IOException {
        open(source, UnaryOperator.identity());
    }

    public void openResolved(Path source) throws IOException {
        if(SpineProjectSource.isNative(source)) { importSkeleton(source); return; }
        history.requireIdle();
        var raw = codec.read(source);
        var loaded = ResourcePaths.absolute(raw, source.toAbsolutePath().normalize().getParent());
        boolean packaged = raw.attachments().stream()
                .anyMatch(a -> a.source() != null && a.source().startsWith(source.getFileName() + ".assets/"));
        history.reset(loaded);
        path = source.toAbsolutePath().normalize();
        resourceBase = path.getParent();
        portable = packaged;
    }

    /**
     * Resolve resource references before publication. A failed adapter preserves
     * the entire document.
     */
    public void open(Path source, UnaryOperator<DocumentSnapshot> prepare) throws IOException {
        history.requireIdle();
        DocumentSnapshot raw = codec.read(source), loaded = Objects.requireNonNull(prepare.apply(raw));
        boolean packaged = raw.attachments().stream()
                .anyMatch(a -> a.source() != null && a.source().startsWith(source.getFileName() + ".assets/"));
        history.reset(loaded);
        path = source.toAbsolutePath().normalize();
        resourceBase = path.getParent();
        portable = packaged;
    }

    public void save(Path target) throws IOException {
        if (portable) {
            savePortable(target);
            return;
        }
        history.requireIdle();
        ProjectCodec.requireProjectPath(target);
        byte[] bytes = codec.encode(history.state().model());
        protectImages(target);
        files.write(target, bytes);
        history.markSaved();
        path = target.toAbsolutePath().normalize();
    }

    /**
     * Keep current absolute resource references and history intact; only disk JSON
     * is rebased.
     * The next regular Save stays portable, including after a fresh JVM opens this
     * project.
     */
    public void savePortable(Path target) throws IOException {
        history.requireIdle();
        ProjectCodec.requireProjectPath(target);
        // Refuse excessive models before staging resources.
        codec.encode(history.state().model());
        protectImages(target);
        var disk = ResourcePaths.portable(history.state().model(), resourceBase, target);
        files.write(target, codec.encode(disk));
        history.markSaved();
        path = target.toAbsolutePath().normalize();
        portable = true;
    }

    public void importSkeleton(Path source) throws IOException {
        history.requireIdle();
        SpineProjectSource nativeSource=SpineProjectSource.isNative(source)?SpineProjectSource.resolve(source):null;
        var importer = new SkeletonJsonCodec();
        var loaded = nativeSource==null?importer.read(source):importer.read(nativeSource.json(),nativeSource.imageBase());
        var report=new java.util.ArrayList<>(importer.warnings());
        if(nativeSource!=null) report.add(0,m("spineProject.snapshotWarning",nativeSource.json()));
        history.reset(loaded);
        importWarnings = java.util.List.copyOf(report);
        path = null;
        portable = true;
        // Import creates a new unsaved editable document, even if the source had no
        // animation.
        history.markUnsaved();
    }

    private void protectImages(Path target) throws IOException {
        Path destination = target.toAbsolutePath().normalize();
        if (destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
            throw new ProjectFormatException(m("projectdocument.0001"));
        Path base = resourceBase;
        for (var a : history.state().model().attachments()) {
            if (a.source() == null)
                continue;
            var r = a;
            Path source = ResourcePaths.resolve(base, r.source());
            if (source.equals(destination) || java.nio.file.Files.exists(source)
                    && java.nio.file.Files.exists(destination) && java.nio.file.Files.isSameFile(source, destination))
                throw new ProjectFormatException(m("projectdocument.0002") + source);
        }
    }
}
