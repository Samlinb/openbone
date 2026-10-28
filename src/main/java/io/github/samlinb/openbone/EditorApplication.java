package io.github.samlinb.openbone;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.github.samlinb.openbone.io.ProjectCodec;

/** Desktop bootstrap and stable command-line project validator. */
public final class EditorApplication {
    private EditorApplication() {
    }

    public static String version() {
        try (var input = EditorApplication.class.getResourceAsStream("/META-INF/maven/io.github.samlinb.openbone/OpenBone/pom.properties")) {
            if (input == null) return "dev";
            var properties = new java.util.Properties(); properties.load(input);
            return properties.getProperty("version", "dev");
        } catch (java.io.IOException unavailable) { return "dev"; }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--headless".equals(args[0])) {
            try { io.github.samlinb.openbone.headless.HeadlessApplication.run(args); }
            catch (Exception failure) {
                System.err.println(java.util.Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
                System.exit(2);
            }
            return;
        }
        // Keep Swing's Windows surfaces on the software/GDI pipeline alongside the
        // heavyweight
        // LWJGL OpenGL canvas. Set before Toolkit initialization; callers can
        // explicitly override.
        if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("sun.java2d.d3d") == null)
            System.setProperty("sun.java2d.d3d", "false");
        if (args.length == 1 && "--environment".equals(args[0])) {
            try {
                System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(io.github.samlinb.openbone.desktop.DesktopEnvironment.describe()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out
                    .println("Openbone " + version());
            return;
        }
        if (args.length == 2 && "--validate-project".equals(args[0])) {
            try {
                ProjectCodec codec = new ProjectCodec();
                var model = codec.read(Path.of(args[1]));
                byte[] canonical = codec.encode(model);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
                int schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(canonical).path("schemaVersion")
                        .asInt();
                System.out.println(
                        "schemaVersion=" + schema + " bones=" + model.bones().size() + " slots=" + model.slots().size()
                                + " attachments=" + model.attachments().size() + " canonicalSHA256=" + hash);
            } catch (Exception invalid) {
                System.err.println(invalid.getMessage());
                System.exit(2);
            }
            return;
        }
        if (args.length != 0 && !(args.length == 2 && "--open".equals(args[0]))) {
            System.err.println(
                    "Usage: --version | --validate-project <schema-project.json> | --open <project.json|project.spine>");
            System.exit(2);
            return;
        }
        Path open = args.length == 2 ? Path.of(args[1]) : null;
        SwingUtilities.invokeLater(() -> new io.github.samlinb.openbone.desktop.EditorFrame(open).showEditor());
    }

}
