package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="openbone.verifyGl", matches="true")
@EnabledOnOs(OS.WINDOWS)
class McpControlDesktopTest {
    @TempDir Path temp;
    EditorFrame frame;
    McpControlDialog dialog;

    @Test void controlWindowSettingsStartStopRestartAndAutomaticStartup() throws Exception {
        Assumptions.assumeFalse(McpConnectionSettings.portOverridden() || McpConnectionSettings.tokenOverridden());
        String old = System.getProperty("openbone.userState");
        System.setProperty("openbone.userState", temp.toString());
        String token = UUID.randomUUID().toString();
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        try {
            openEditor();
            openControl();
            await(() -> component("mcp.start", JButton.class).isEnabled());
            edt(() -> {
                assertFalse(dialog.isModal());
                assertEquals(io.github.samlinb.openbone.i18n.M.m("mcp.stopped"), component("mcp.status", JLabel.class).getText());
                assertEquals(39871, component("mcp.port", JSpinner.class).getValue());
                Path output = Path.of("target/mcp-desktop/control-window.png");
                Files.createDirectories(output.getParent());
                var screenshot = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = screenshot.createGraphics(); dialog.paint(graphics); graphics.dispose();
                ImageIO.write(screenshot, "PNG", output.toFile());
                component("mcp.port", JSpinner.class).setValue(port);
                component("mcp.token", JPasswordField.class).setText(token);
                component("mcp.autoStart", JCheckBox.class).setSelected(true);
                component("mcp.start", JButton.class).doClick();
                return null;
            });
            await(() -> component("mcp.stop", JButton.class).isEnabled());
            String url = edt(() -> component("mcp.endpoint", JTextField.class).getText());
            assertEquals("http://127.0.0.1:" + port + "/mcp", url);
            assertEquals(200, ping(url, token)); assertEquals(401, ping(url, null));
            edt(() -> {
                assertFalse(component("mcp.port", JSpinner.class).isEnabled());
                assertEquals(io.github.samlinb.openbone.i18n.M.m("mcp.running"), component("mcp.status", JLabel.class).getText());
                dialog.dispose(); return null;
            });
            openControl();
            await(() -> component("mcp.stop", JButton.class).isEnabled());
            edt(() -> { component("mcp.stop", JButton.class).doClick(); return null; });
            await(() -> component("mcp.start", JButton.class).isEnabled());
            edt(() -> { component("mcp.start", JButton.class).doClick(); return null; });
            await(() -> component("mcp.stop", JButton.class).isEnabled());
            assertEquals(url, edt(() -> component("mcp.endpoint", JTextField.class).getText()));
            closeEditor(); openEditor(); openControl();
            await(() -> component("mcp.stop", JButton.class).isEnabled());
            assertEquals(200, ping(url, token));
            edt(() -> {
                component("mcp.autoStart", JCheckBox.class).setSelected(false);
                component("mcp.save", JButton.class).doClick(); return null;
            });
            await(() -> component("mcp.save", JButton.class).isEnabled());
            assertFalse(McpConnectionSettings.read(McpConnectionSettings.path()).autoStart());
            assertFalse(Files.readString(McpConnectionSettings.path()).contains(token));
            closeEditor(); openEditor(); openControl();
            await(() -> component("mcp.start", JButton.class).isEnabled());
            assertEquals(io.github.samlinb.openbone.i18n.M.m("mcp.stopped"), edt(() -> component("mcp.status", JLabel.class).getText()));
        } finally {
            closeEditor();
            if (old == null) System.clearProperty("openbone.userState"); else System.setProperty("openbone.userState", old);
        }
    }

    private void openEditor() throws Exception {
        edt(() -> { frame = new EditorFrame(null); frame.setSize(1200, 850); frame.showEditor(); return null; });
    }
    private void openControl() throws Exception {
        edt(() -> {
            for (Component value : frame.getJMenuBar().getComponents())
                if ("mcp.openControl".equals(value.getName())) ((JButton) value).doClick();
            for (Window value : frame.getOwnedWindows())
                if (value instanceof McpControlDialog control && value.isShowing()) dialog = control;
            assertNotNull(dialog); return null;
        });
    }
    private void closeEditor() throws Exception {
        if (frame != null) edt(() -> {
            if (frame.isDisplayable()) {
                frame.history().markSaved();
                var method = EditorFrame.class.getDeclaredMethod("closeRequested"); method.setAccessible(true); method.invoke(frame);
            }
            return null;
        });
    }
    private <T extends Component> T component(String name, Class<T> type) { return type.cast(find(dialog, name)); }
    private Component find(Container root, String name) {
        for (Component value : root.getComponents()) {
            if (name.equals(value.getName())) return value;
            if (value instanceof Container container) { Component found = find(container, name); if (found != null) return found; }
        }
        return null;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(40); }
        fail("MCP control state timed out");
    }
    private int ping(String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<T>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
