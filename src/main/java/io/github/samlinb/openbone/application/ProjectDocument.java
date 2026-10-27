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

    /** Publish metadata from a successful worker save, preserving this history and selection. */
    public void acceptSavedCopy(ProjectDocument copy) {
        history.requireIdle();
        if(copy.path==null||copy.history.isDirty()||!copy.history.state().model().equals(history.state().model()))
            throw new IllegalStateException("Saved copy does not match current project");
        path=copy.path; resourceBase=copy.resourceBase; portable=copy.portable; history.markSaved();
    }

    public void open(Path source) throws IOException {
        open(source, UnaryOperator.identity());
    }

    public void openResolved(Path source) throws IOException {
        openResolved(source,null);
    }

    public void openResolved(Path source, SpineImportPolicy.Approval approval) throws IOException {
        if(SpineProjectSource.isNative(source) || isSpineExport(source)) { importSkeleton(source,approval); return; }
        history.requireIdle();
        if(SpineImportPolicy.isJsonExport(ResourcePaths.bytes(source,ProjectCodec.MAX_BYTES))){importSkeleton(source,approval);return;}
        if(approval!=null)throw new ProjectFormatException(m("spineImport.approvalChanged"));
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
        importSkeleton(source,null);
    }

    public void importSkeleton(Path source, SpineImportPolicy.Approval approval) throws IOException {
        history.requireIdle();
        if(SpineProjectSource.isNative(source)&&SpineCliBridge.configured()){
            importNative(source,approval);return;
        }
        SpineProjectSource nativeSource=SpineProjectSource.isNative(source)?SpineProjectSource.resolve(source):null;
        var importer = new SkeletonJsonCodec();
        Path input=nativeSource==null?source:nativeSource.json();
        Path imageBase=nativeSource==null?source.toAbsolutePath().normalize().getParent():nativeSource.imageBase();
        var loaded = importer.read(input,imageBase,approval);
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

    private void importNative(Path source,SpineImportPolicy.Approval approval)throws IOException {
        byte[] sourceBytes=ResourcePaths.bytes(source,64*1024*1024);
        var selection=SpineImportPolicy.select(sourceBytes,SpineImportPolicy.Format.NATIVE,approval);
        Path temporary=java.nio.file.Files.createTempDirectory("openbone-native-");
        try {
            Path copy=temporary.resolve("source.spine");java.nio.file.Files.write(copy,sourceBytes);
            Path json=SpineCliBridge.exportProject(copy,temporary,selection.known()?selection.sourceVersion():selection.parser().version());
            var importer=new SkeletonJsonCodec();
            var loaded=importer.read(json,source.toAbsolutePath().getParent());
            var report=new java.util.ArrayList<>(importer.warnings());report.add(0,m("spineNative.converted",selection.sourceVersion()));
            if(!selection.known())report.add(selection.message());
            SpineImportPolicy.select(ResourcePaths.bytes(source,64*1024*1024),SpineImportPolicy.Format.NATIVE,selection.approval());
            history.reset(loaded);importWarnings=java.util.List.copyOf(report);path=null;portable=true;history.markUnsaved();
        } finally {
            // Only remove files created inside this exact private conversion directory.
            try(var entries=java.nio.file.Files.walk(temporary)) {
                for(Path path:entries.sorted(java.util.Comparator.reverseOrder()).toList())if(path.normalize().startsWith(temporary))java.nio.file.Files.deleteIfExists(path);
            } catch(IOException cleanup) {temporary.toFile().deleteOnExit();}
        }
    }

    public static boolean isSpineExport(Path source) {
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".skel") || name.endsWith(".skel.bytes");
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
