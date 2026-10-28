package io.github.samlinb.openbone.headless;

import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import io.github.samlinb.openbone.mcp.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import static io.github.samlinb.openbone.i18n.M.m;

/** Explicit local MCP service; never creates an EditorFrame or initializes OpenGL. */
public final class HeadlessApplication implements AutoCloseable {
    public static final int DEFAULT_PORT = 39871;
    private final HeadlessSession session;
    private final EditorMcpServer server;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimePreviewBridge preview;

    public HeadlessApplication(Path source, int port, String token) throws Exception {
        this(source, port, token, null);
    }
    public HeadlessApplication(Path source, int port, String token, Path runtimeJar) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(m("headless.worker"));
        var document = new ProjectDocument(DocumentSnapshot.EMPTY);
        if (source != null) document.openResolved(source);
        preview = runtimeJar == null ? null : new RuntimePreviewBridge(runtimeJar);
        session = edt(() -> new HeadlessSession(document, preview));
        EditorMcpServer started;
        var tools = new EditorMcpTools(session);
        try { started = new EditorMcpServer(port, token, tools); }
        catch (Exception failure) { tools.close(); if (preview != null) preview.close(); edt(() -> { session.close(); return null; }); throw failure; }
        server = started;
    }

    public String url() { return server.url(); }

    @Override public void close() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(m("headless.worker"));
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); }
        finally {
            if (preview != null) preview.close();
            try { edt(() -> { session.close(); return null; }); }
            catch (Exception failure) { throw new IllegalStateException(m("headless.closeFailed"), failure); }
        }
    }

    public static void run(String[] args) throws Exception {
        Path source = null, projects = null;
        String configuredRuntime = System.getProperty("openbone.preview.jar", System.getenv("OPENBONE_PREVIEW_JAR"));
        Path runtimeJar = configuredRuntime == null || configuredRuntime.isBlank() ? null : Path.of(configuredRuntime);
        String configuredPort = System.getProperty("openbone.mcp.port", System.getenv("OPENBONE_MCP_PORT"));
        int port = configuredPort == null ? DEFAULT_PORT : parsePort(configuredPort);
        var seen = new java.util.HashSet<String>();
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (!seen.add(option) || i + 1 >= args.length) throw new IllegalArgumentException(m("headless.usage"));
            String value = args[++i];
            switch (option) {
                case "--projects" -> projects = Path.of(value).toAbsolutePath().normalize();
                case "--open" -> source = Path.of(value).toAbsolutePath().normalize();
                case "--port" -> port = parsePort(value);
                case "--preview-jar" -> runtimeJar = Path.of(value);
                default -> throw new IllegalArgumentException(m("headless.usage"));
            }
        }
        System.setProperty("java.awt.headless", "true");
        if (projects != null && source != null) throw new IllegalArgumentException(m("headless.usage"));
        AutoCloseable application;
        String url;
        if (projects != null) {
            var isolated = new IsolatedHeadlessApplication(IsolatedHeadlessApplication.readConfig(projects), port, runtimeJar);
            application = isolated;
            url = isolated.url() + "/<projectId>";
        } else {
            var single = new HeadlessApplication(source, port, System.getenv("OPENBONE_MCP_TOKEN"), runtimeJar);
            application = single;
            url = single.url();
        }
        var stopped = new CountDownLatch(1);
        var hook = new Thread(() -> {
            try { application.close(); } catch (Exception failure) { throw new IllegalStateException(m("headless.closeFailed"), failure); } finally { stopped.countDown(); }
        }, "openbone-headless-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        System.out.println(m("headless.started", url));
        try { stopped.await(); }
        finally {
            application.close();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException shuttingDown) { /* Hook owns shutdown. */ }
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException invalid) { /* Localized error below. */ }
        throw new IllegalArgumentException(m("headless.port"));
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<T>(task);
        SwingUtilities.invokeLater(future);
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}
