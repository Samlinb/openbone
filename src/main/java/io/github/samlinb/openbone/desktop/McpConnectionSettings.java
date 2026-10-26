package io.github.samlinb.openbone.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.io.AtomicProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stable local settings. Saved credentials use Windows current-user DPAPI, never plaintext. */
final class McpConnectionSettings {
    static final int DEFAULT_PORT = 39871;
    record State(int port, boolean autoStart, String protectedToken) {
        State { protectedToken = protectedToken == null ? "" : protectedToken; }
    }

    static State read(Path path) throws IOException {
        if (!Files.exists(path)) return new State(DEFAULT_PORT, false, "");
        if (Files.size(path) > 8192) throw new IOException("MCP settings too large.");
        State state = new ObjectMapper().readValue(Files.readAllBytes(path), State.class);
        parse(Integer.toString(state.port()), false);
        return state;
    }

    static boolean portOverridden() {
        String environment = System.getenv("OPENBONE_MCP_PORT");
        return System.getProperty("openbone.mcp.port") != null || environment != null && !environment.isBlank();
    }

    static boolean tokenOverridden() { return System.getenv("OPENBONE_MCP_TOKEN") != null; }

    static String token(State state) throws IOException {
        String token = System.getenv("OPENBONE_MCP_TOKEN");
        if (token == null) token = state.protectedToken().isEmpty() ? "" : McpSecretStore.unprotect(state.protectedToken());
        return token.isBlank() ? "" : token;
    }

    static void validateToken(String token) {
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("mcp.tokenHint"));
    }

    private McpConnectionSettings() {}

    static Path path() { return UserState.root().resolve("mcp.json"); }

    static int port() throws IOException {
        return resolve(path(), System.getProperty("openbone.mcp.port"), System.getenv("OPENBONE_MCP_PORT"));
    }

    static int resolve(Path path, String property, String environment) throws IOException {
        if (property != null) return parse(property, true);
        if (environment != null && !environment.isBlank()) return parse(environment, true);
        return read(path).port();
    }

    static int parse(String value, boolean allowEphemeral) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= (allowEphemeral ? 0 : 1) && port <= 65535) return port;
        } catch (NumberFormatException invalid) { /* Report a consistent configuration error below. */ }
        throw new IllegalArgumentException("MCP port must be " + (allowEphemeral ? "0" : "1") + " to 65535.");
    }

    static void save(Path path, int port) throws IOException {
        State old = read(path);
        save(path, new State(port, old.autoStart(), old.protectedToken()));
    }

    static void save(Path path, State state) throws IOException {
        parse(Integer.toString(state.port()), false);
        Files.createDirectories(path.toAbsolutePath().getParent());
        new AtomicProjectFile().write(path, new ObjectMapper().writeValueAsBytes(state));
    }
}
