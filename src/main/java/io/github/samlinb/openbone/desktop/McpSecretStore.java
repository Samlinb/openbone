package io.github.samlinb.openbone.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Windows DPAPI bridge. Secrets travel only over private stdin/stdout pipes, never command arguments or logs. */
final class McpSecretStore {
    private McpSecretStore() {}
    static String protect(String token) throws IOException {
        return transform(Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)), "Protect");
    }
    static String unprotect(String encrypted) throws IOException {
        try { return new String(Base64.getDecoder().decode(transform(encrypted, "Unprotect")), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Cannot read saved MCP credential."); }
    }
    private static String transform(String input, String method) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("Saved MCP credentials currently require Windows; use OPENBONE_MCP_TOKEN on this platform.");
        String command = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                + "$data=[Convert]::FromBase64String([Console]::In.ReadToEnd()); "
                + "[Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::"
                + method + "($data,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))";
        String executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        Process process = new ProcessBuilder(executable, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.US_ASCII)); }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Cannot access saved MCP credential for this Windows user.");
            return new String(process.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII).trim();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("MCP credential operation interrupted.");
        } finally { process.destroyForcibly(); }
    }
}
