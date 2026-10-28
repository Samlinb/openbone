package io.github.samlinb.openbone.headless;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import io.github.samlinb.openbone.mcp.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import static io.github.samlinb.openbone.i18n.M.m;

/** One listener with operator-provisioned project endpoints and independent live application services. */
public final class IsolatedHeadlessApplication implements AutoCloseable {
    public record Project(String id, Path root, Path source, String token) {
        @Override public String toString() { return "Project[id=" + id + ", credentials=redacted]"; }
    }
    private final List<HeadlessSession> sessions = new ArrayList<>();
    private final List<RuntimePreviewBridge> previews = new ArrayList<>();
    private final List<EditorMcpTools> tools = new ArrayList<>();
    private EditorMcpServer server;
    private boolean closed;

    public IsolatedHeadlessApplication(List<Project> projects, int port, Path runtimeJar) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(m("headless.worker"));
        try {
            var endpoints = new LinkedHashMap<String, EditorMcpServer.Endpoint>();
            var roots = new ArrayList<Path>();
            var tokens = new HashSet<String>();
            if (projects.isEmpty()) throw new IllegalArgumentException(m("isolation.config"));
            for (Project project : projects) {
                if (project.id() == null || !project.id().matches("[A-Za-z0-9_-]{1,64}")
                        || endpoints.containsKey("/mcp/" + project.id()) || project.token() == null
                        || !project.token().matches("[A-Za-z0-9_-]{32,256}") || !tokens.add(project.token()))
                    throw new IllegalArgumentException(m("isolation.config"));
                var scope = new ProjectScope(project.id(), project.root());
                if (roots.stream().anyMatch(r -> r.startsWith(scope.root) || scope.root.startsWith(r)))
                    throw new IllegalArgumentException(m("isolation.overlap"));
                roots.add(scope.root);
                var document = new ProjectDocument(DocumentSnapshot.EMPTY);
                if (project.source() != null) scope.load(document, scope.check(project.source()), null);
                var preview = runtimeJar == null ? null : new RuntimePreviewBridge(runtimeJar);
                if (preview != null) previews.add(preview);
                var session = edt(() -> new HeadlessSession(document, preview, scope));
                sessions.add(session);
                var projectTools = new EditorMcpTools(session);
                tools.add(projectTools);
                endpoints.put("/mcp/" + project.id(), new EditorMcpServer.Endpoint(project.token(), projectTools));
            }
            server = new EditorMcpServer(port, endpoints);
        } catch (Exception failed) { close(); throw failed; }
    }

    public String url() { return server.url(); }
    public String url(String project) { return url() + "/" + project; }

    /** Configuration contains environment variable names, never bearer credentials. */
    public static List<Project> readConfig(Path file) throws Exception {
        var mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var config = mapper.readTree(io.github.samlinb.openbone.io.ResourcePaths.bytes(file, 1_048_576));
        if (!config.isObject() || config.size() != 1 || !config.path("projects").isArray())
            throw new IllegalArgumentException(m("isolation.config"));
        var projects = new ArrayList<Project>();
        Path base = file.toAbsolutePath().getParent();
        for (var item : config.get("projects")) {
            if (!item.isObject()) throw new IllegalArgumentException(m("isolation.config"));
            var names = item.fieldNames();
            while (names.hasNext()) if (!Set.of("id", "root", "source", "tokenEnv").contains(names.next()))
                throw new IllegalArgumentException(m("isolation.config"));
            for (String required : List.of("id", "root", "tokenEnv"))
                if (!item.path(required).isTextual() || item.path(required).asText().isBlank())
                    throw new IllegalArgumentException(m("isolation.config"));
            Path root = base.resolve(item.get("root").asText()).normalize();
            if (item.has("source") && (!item.get("source").isTextual() || item.get("source").asText().isBlank()))
                throw new IllegalArgumentException(m("isolation.config"));
            projects.add(new Project(item.get("id").asText(), root,
                    item.has("source") ? root.resolve(item.get("source").asText()).normalize() : null,
                    System.getenv(item.get("tokenEnv").asText())));
        }
        return List.copyOf(projects);
    }

    @Override public synchronized void close() {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(m("headless.worker"));
        if (closed) return;
        closed = true;
        if (server != null) server.close();
        else tools.forEach(EditorMcpTools::close);
        previews.forEach(RuntimePreviewBridge::close);
        try { edt(() -> { sessions.forEach(HeadlessSession::close); return null; }); }
        catch (Exception failed) { throw new IllegalStateException(m("headless.closeFailed"), failed); }
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<T>(task);
        SwingUtilities.invokeLater(future);
        return future.get();
    }
}
