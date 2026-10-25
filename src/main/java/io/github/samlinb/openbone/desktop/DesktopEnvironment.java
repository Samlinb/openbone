package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.List;

public final class DesktopEnvironment {
    private DesktopEnvironment() {
    }

    public static List<Object> screens() {
        List<Object> out = new ArrayList<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            var c = device.getDefaultConfiguration();
            var b = c.getBounds();
            var t = c.getDefaultTransform();
            var mode = device.getDisplayMode();
            out.add(Map.of("id", device.getIDstring(), "bounds", List.of(b.x, b.y, b.width, b.height), "transform",
                    List.of(t.getScaleX(), t.getShearY(), t.getShearX(), t.getScaleY(), t.getTranslateX(),
                            t.getTranslateY()),
                    "displayMode", List.of(mode.getWidth(), mode.getHeight(), mode.getRefreshRate()), "usable",
                    WorkspaceLayout.usable(c).toString()));
        }
        return out;
    }

    public static Map<String, Object> describe() {
        return Map.of("javaVersion", System.getProperty("java.version"), "javaHome", System.getProperty("java.home"),
                "os", System.getProperty("os.name"), "osVersion", System.getProperty("os.version"), "processors",
                Runtime.getRuntime().availableProcessors(), "jvmArgs",
                ManagementFactory.getRuntimeMXBean().getInputArguments(), "screens", screens(), "userState",
                UserState.root().toString());
    }
}
