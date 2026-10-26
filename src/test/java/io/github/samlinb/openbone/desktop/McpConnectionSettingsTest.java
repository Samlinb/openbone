package io.github.samlinb.openbone.desktop;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpConnectionSettingsTest {
    @TempDir Path temp;

    @Test void stableDefaultSavedPortAndExplicitOverrides() throws Exception {
        Path path = temp.resolve("mcp.json");
        assertEquals(39871, McpConnectionSettings.resolve(path, null, null));
        McpConnectionSettings.save(path, 39872);
        assertEquals(39872, McpConnectionSettings.resolve(path, null, null));
        assertEquals(39872, McpConnectionSettings.resolve(path, null, ""));
        assertEquals(39873, McpConnectionSettings.resolve(path, null, "39873"));
        assertEquals(39874, McpConnectionSettings.resolve(path, "39874", "39873"));
        assertEquals(0, McpConnectionSettings.resolve(path, "0", null));
        assertEquals(new McpConnectionSettings.State(39872, false, ""), McpConnectionSettings.read(path));
    }

    @Test void legacySettingsAndAutomaticStartupFlagRoundTrip() throws Exception {
        Path path = temp.resolve("mcp.json");
        Files.writeString(path, "{\"port\":39872}");
        assertFalse(McpConnectionSettings.read(path).autoStart());
        McpConnectionSettings.save(path, new McpConnectionSettings.State(39873, true, ""));
        assertTrue(McpConnectionSettings.read(path).autoStart());
        McpConnectionSettings.save(path, 39874);
        assertTrue(McpConnectionSettings.read(path).autoStart());
    }

    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void savedCredentialIsEncryptedAndReadableByCurrentUser() throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        String encrypted = McpSecretStore.protect(token);
        Path path = temp.resolve("mcp.json");
        McpConnectionSettings.save(path, new McpConnectionSettings.State(39871, true, encrypted));
        assertFalse(Files.readString(path).contains(token));
        assertEquals(token, McpSecretStore.unprotect(McpConnectionSettings.read(path).protectedToken()));
        assertThrows(java.io.IOException.class, () -> McpSecretStore.unprotect("invalid"));
    }

    @Test void invalidConfigurationDoesNotSilentlyChooseAnotherPort() throws Exception {
        Path path = temp.resolve("mcp.json");
        McpConnectionSettings.save(path, 39872);
        for (String value : new String[]{"-1", "65536", "abc", ""})
            assertThrows(IllegalArgumentException.class, () -> McpConnectionSettings.resolve(path, value, null));
        assertThrows(IllegalArgumentException.class, () -> McpConnectionSettings.save(path, 0));
        assertEquals(39872, McpConnectionSettings.resolve(path, null, null));
        Files.writeString(path, "{\"port\":0}");
        assertThrows(IllegalArgumentException.class, () -> McpConnectionSettings.resolve(path, null, null));
    }
}
