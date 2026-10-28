package io.github.samlinb.openbone.headless;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.mcp.EditorMcpTools;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import static io.github.samlinb.openbone.i18n.M.m;

/** A live editing session without any window or GL context. State remains owned by EDT. */
public final class HeadlessSession implements EditorMcpTools.Host {
    private ProjectDocument document;
    private boolean closed;
    private final RuntimePreviewBridge preview;
    private final ProjectScope scope;

    public HeadlessSession(ProjectDocument document) {
        this(document, null);
    }
    HeadlessSession(ProjectDocument document, RuntimePreviewBridge preview) {
        this(document, preview, null);
    }
    HeadlessSession(ProjectDocument document, RuntimePreviewBridge preview, ProjectScope scope) {
        requireEdt();
        this.scope = scope;
        this.document = java.util.Objects.requireNonNull(document);
        this.preview = preview;
    }

    @Override public void validateArguments(String name, com.fasterxml.jackson.databind.JsonNode args) throws Exception {
        if (scope == null) return;
        scope.arguments(name, args);
        var snapshot = new java.util.concurrent.FutureTask<io.github.samlinb.openbone.core.model.DocumentSnapshot>(() -> {
            prepare(); return document.history().state().model();
        });
        SwingUtilities.invokeLater(snapshot);
        try {
            for (var attachment : snapshot.get(10, java.util.concurrent.TimeUnit.SECONDS).attachments())
                if (attachment.source() != null) scope.check(java.nio.file.Path.of(attachment.source()));
        } finally { snapshot.cancel(false); }
    }
    @Override public void loadProject(ProjectDocument target, java.nio.file.Path path, io.github.samlinb.openbone.io.SpineImportPolicy.Approval approval) throws Exception {
        if (scope == null) target.openResolved(path, approval); else scope.load(target, path, approval);
    }
    @Override public String accessDescription() { return scope == null ? "" : m("isolation.description"); }
    @Override public boolean supportsPreview() { return preview != null; }
    @Override public boolean supportsViewControl() { return false; }
    @Override public String previewDescription() { return m("headless.previewDescription"); }
    @Override public String previewSource() { return "OpenBone exported 4.2.22 assets rendered in a separate runtime JVM"; }
    @Override public CommandHistory history() { prepare(); return document.history(); }
    @Override public ProjectDocument project() { prepare(); return document; }
    @Override public void installProject(ProjectDocument next) { prepare(); document = java.util.Objects.requireNonNull(next); }
    @Override public Map<String, Object> context() {
        prepare();
        return Map.of("projectId", scope == null ? "" : scope.id, "workspaceRoot", scope == null ? "" : scope.root.toString(), "hostMode", "headless", "projectPath", document.path().map(Object::toString).orElse(""),
                "previewBackend", preview == null ? "none" : "openbone-runtime");
    }
    @Override public void prepare() {
        requireEdt();
        if (closed) throw new IllegalStateException(m("headless.closed"));
        document.history().requireIdle();
    }
    @Override public void changed() { prepare(); }
    @Override public CompletableFuture<EditorMcpTools.Preview> capture(EntityId animation, double time, String revision) {
        return capture(animation,time,revision,new EditorMcpTools.CaptureOptions(true,false,false));
    }
    @Override public CompletableFuture<EditorMcpTools.Preview> capture(EntityId animation, double time, String revision, EditorMcpTools.CaptureOptions options) {
        prepare();
        if (!document.history().revision().equals(revision)) throw new IllegalStateException("REVISION_CONFLICT");
        if (preview == null) return CompletableFuture.failedFuture(new UnsupportedOperationException(m("headless.unavailable")));
        var model = document.history().state().model();
        io.github.samlinb.openbone.core.animation.PhysicsPlayer.validateTime(model,animation,time);
        return preview.capture(model,document.path().map(java.nio.file.Path::getParent).orElse(java.nio.file.Path.of("").toAbsolutePath()),animation,time,revision,options);
    }
    public void close() { requireEdt(); closed = true; }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(m("headless.edt"));
    }
}
