package io.github.samlinb.openbone.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.nio.file.*;
import javax.swing.*;
import io.github.samlinb.openbone.io.AtomicProjectFile;

/**
 * Logical coordinates; clamp on restore when screens or scaling changed. No
 * global display writes.
 */
public final class WorkspaceLayout {
    public record State(int version, int x, int y, int width, int height, int hierarchy, int properties,
            boolean maximized, int timeline, boolean curves) {
        public State(int version,int x,int y,int width,int height,int hierarchy,int properties,boolean maximized) {
            this(version,x,y,width,height,hierarchy,properties,maximized,270,false);
        }
    }

    private WorkspaceLayout() {
    }

    public static Rectangle fit(Rectangle wanted, Rectangle screen) {
        int w = Math.min(Math.max(640, wanted.width), screen.width),
                h = Math.min(Math.max(480, wanted.height), screen.height);
        return new Rectangle(Math.max(screen.x, Math.min(wanted.x, screen.x + screen.width - w)),
                Math.max(screen.y, Math.min(wanted.y, screen.y + screen.height - h)), w, h);
    }

    public static Rectangle usable(GraphicsConfiguration gc) {
        Rectangle r = new Rectangle(gc.getBounds());
        Insets i = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        r.x += i.left;
        r.y += i.top;
        r.width -= i.left + i.right;
        r.height -= i.top + i.bottom;
        return r;
    }

    public static State read(Path path) throws Exception {
        if (Files.size(path) > 4096)
            throw new IllegalArgumentException("Layout too large");
        State s = new ObjectMapper().readValue(Files.readAllBytes(path), State.class);
        if (s.version() != 1 || s.width() < 1 || s.height() < 1)
            throw new IllegalArgumentException("Unknown layout");
        return s;
    }

    public static void save(Path path, State s) throws Exception {
        Files.createDirectories(path.getParent());
        new AtomicProjectFile().write(path, new ObjectMapper().writeValueAsBytes(s));
    }

    public static void restore(JFrame frame, JSplitPane hierarchy, JSplitPane right, State s) {
        Rectangle wanted = new Rectangle(s.x(), s.y(), s.width(), s.height());
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            if (d.getDefaultConfiguration().getBounds().intersects(wanted)) {
                gc = d.getDefaultConfiguration();
                break;
            }
        Rectangle r = fit(wanted, usable(gc));
        frame.setBounds(r);
        hierarchy.setDividerLocation(Math.max(160, Math.min(s.hierarchy(), r.width - 500)));
        right.setDividerLocation(Math.max(150,
                r.width - hierarchy.getDividerLocation() - Math.max(285, Math.min(s.properties(), r.width / 2)) - 25));
        if (s.maximized())
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }
}
