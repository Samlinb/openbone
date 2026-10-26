package io.github.samlinb.openbone.viewport;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import java.awt.Canvas;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.event.*;
import java.util.*;
import javax.swing.SwingUtilities;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.application.EditorEdits.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.Channel;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Real GL surface. AWT is the single input writer; all context/resource work
 * stays on EDT.
 * Uses the official Apache-2.0 libGDX LwjglAWTCanvas backend, not official
 * runtime classes.
 */
public final class EditorViewport extends ApplicationAdapter {
    public interface Host {
        CommandHistory history();

        DocumentSnapshot displayModel();

        WorldPose evaluatedPose();

        boolean prepareTransform(EntityId id);

        EditCommand transformEdit(UnaryOperator<DocumentSnapshot> operation, Set<Channel> channels);

        void changed();

        void message(String message);

        void error(Exception error);

        void createBone(Vec2 start, Vec2 end);

        void shortcut(int key, int modifiers);

        boolean prepareMesh();

        EditCommand meshVertexEdit(EntityId id, int index, Vec2 target);
    }

    private final Host host;
    private final ImageAssets assets;
    private final Camera2D camera = new Camera2D();
    private final LwjglAWTCanvas backend;
    private final Canvas canvas;
    private final KeyEventDispatcher keys;
    private final Map<java.nio.file.Path, GpuImage> textures = new HashMap<>();

    private record GpuImage(ImageAssets.Image image, Texture texture) {
    }

    private PolygonSpriteBatch batch;
    private ShapeRenderer shapes;
    private Tool tool = Tool.SELECT;
    private Space space = Space.LOCAL;
    private DocumentSnapshot dragBase;
    private EntityId dragId;
    private Vec2 press, createEnd;
    private int handle;
    private boolean meshEditing, meshDrag;
    private int meshIndex;

    public boolean meshEditing() {
        return meshEditing;
    }

    public int meshIndex() {
        return meshIndex;
    }

    public void meshIndex(int value) {
        cancel();
        meshIndex = value;
        host.changed();
    }

    public void meshEditing(boolean value) {
        cancel();
        meshEditing = value;
        host.changed();
        canvas.requestFocusInWindow();
    }

    private boolean pan, invalid, stopped;
    private double previousAngle, accumulatedAngle;
    private int previousX, previousY, createdTextures, disposedTextures;
    private int pixelWidth, pixelHeight;
    private final FrameTelemetry telemetry = new FrameTelemetry();
    private long renders, createdAt, firstRenderAt;
    private record Capture(Runnable prepare, java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> result) {}
    private final Deque<Capture> captures = new ArrayDeque<>();

    /** Prepared and read in the same GL frame on EDT, never through a desktop screenshot. */
    public java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> capture(Runnable prepare) {
        requireEdt();
        if (stopped || !canvas.isShowing())
            throw new IllegalStateException("Viewport is not visible. Show the OpenBone editor and retry.");
        if (captures.size() >= 4) throw new IllegalStateException("Preview queue is full. Retry after the current capture.");
        var result = new java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage>();
        captures.addLast(new Capture(prepare, result));
        return result;
    }

    public EditorViewport(Host host, ImageAssets assets) {
        this.host = host;
        this.assets = assets;
        requireEdt();
        LwjglApplicationConfiguration.disableAudio = true;
        var config = new LwjglApplicationConfiguration();
        config.forceExit = false;
        config.foregroundFPS = 120;
        config.backgroundFPS = 20;
        config.vSyncEnabled = false;
        backend = new LwjglAWTCanvas(this, config) {
            @Override
            public boolean executeRunnables() {
                telemetry.begin();
                return super.executeRunnables();
            }

            @Override
            protected int getFrameRate() {
                if (telemetry.enabled()) {
                    if (Gdx.gl != null)
                        Gdx.gl.glFinish();
                    telemetry.end(canvas != null && canvas.isShowing(), metrics());
                }
                return super.getFrameRate();
            }

            @Override
            protected void exception(Throwable t) {
                t.printStackTrace();
                SwingUtilities.invokeLater(() -> host.error(new IllegalStateException(m("editorviewport.0001"), t)));
            }
        };
        canvas = backend.getCanvas();
        canvas.setName("viewport");
        canvas.setFocusable(true);
        var mouse = new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (dragBase != null || createEnd != null || pan)
                    return;
                camera.zoomAt(camera.zoom() * Math.pow(1.15, -e.getPreciseWheelRotation()), e.getX(), e.getY(),
                        canvas.getWidth(), canvas.getHeight());
                host.changed();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                press(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                drag(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                release(e);
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
        keys = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED
                    || KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != canvas)
                return false;
            int k = e.getKeyCode();
            boolean command = k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_DELETE || k == KeyEvent.VK_Q
                    || k == KeyEvent.VK_N || k == KeyEvent.VK_W || k == KeyEvent.VK_E || k == KeyEvent.VK_R
                    || e.isControlDown();
            if (!command)
                return false;
            host.shortcut(k, e.getModifiersEx());
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        canvas.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                cancel();
            }
        });
    }

    public Canvas canvas() {
        return canvas;
    }

    public Camera2D camera() {
        return camera;
    }

    public Tool tool() {
        return tool;
    }

    public Space space() {
        return space;
    }

    public void tool(Tool value) {
        cancel();
        tool = value;
        host.changed();
        canvas.requestFocusInWindow();
    }

    public void space(Space value) {
        cancel();
        space = value;
        host.changed();
    }

    private DocumentSnapshot model() {
        return host.history().state().model();
    }

    private EntityId selected() {
        return host.history().state().selection().primary().orElse(null);
    }

    private Vec2 screen(Vec2 p) {
        return camera.screen(p, canvas.getWidth(), canvas.getHeight());
    }

    private Vec2 world(Vec2 p) {
        return camera.world(p.x(), p.y(), canvas.getWidth(), canvas.getHeight());
    }

    /**
     * Unit logical-screen vectors of displayed axes; translation local axes belong
     * to parent.
     */
    private Vec2 axis(DocumentSnapshot d, EntityId id, boolean xAxis) {
        Affine2 m = space == Space.WORLD ? Affine2.IDENTITY : displayMatrix(d, id, tool == Tool.MOVE);
        double x = xAxis ? m.a() : m.b(), y = xAxis ? m.c() : m.d(), length = Math.hypot(x, y);
        return length < 1e-12 ? (xAxis ? new Vec2(1, 0) : new Vec2(0, -1)) : new Vec2(x / length, -y / length);
    }

    private Affine2 displayMatrix(DocumentSnapshot d, EntityId selected, boolean parent) {
        if (d != model())
            return parent ? EditorEdits.parent(d, selected) : EditorEdits.world(d, selected);
        var pose = host.evaluatedPose();
        EntityId id = EditorEdits.transformId(d, selected);
        for (var a : d.attachments())
            if (a.id().equals(id)) {
                if (!parent && a instanceof RegionAttachment)
                    return pose.regions().get(id).matrix();
                var slot = d.slots().stream().filter(s -> s.id().equals(a.slotId())).findFirst().orElseThrow();
                return pose.bones().get(slot.boneId()).matrix();
            }
        var b = d.bone(id);
        return parent ? (b.parentId() == null ? Affine2.IDENTITY : pose.bones().get(b.parentId()).matrix())
                : pose.bones().get(id).matrix();
    }

    private Vec2 origin(DocumentSnapshot d, EntityId id) {
        var w = displayMatrix(d, id, false);
        return screen(new Vec2(w.tx(), w.ty()));
    }

    private int hitHandle(Vec2 p, DocumentSnapshot d, EntityId id) {
        if (d.attachments().stream().anyMatch(a -> a.id().equals(id) && !(a instanceof RegionAttachment)))
            return 0;
        if (tool != Tool.MOVE && tool != Tool.ROTATE && tool != Tool.SCALE)
            return 0;
        Vec2 o = origin(d, id);
        double distance = Math.hypot(p.x() - o.x(), p.y() - o.y());
        if (tool == Tool.ROTATE)
            return Math.abs(distance - 50) <= 8 ? 3 : 0;
        if (distance <= 10)
            return 3;
        for (int i = 1; i <= 2; i++) {
            Vec2 a = axis(d, id, i == 1);
            Vec2 end = new Vec2(o.x() + a.x() * 64, o.y() + a.y() * 64);
            if (Picker.distance(p, o, end) <= 7)
                return i;
        }
        return 0;
    }

    private void press(MouseEvent e) {
        if (System.getProperty("openbone.diagnostics") != null)
            System.out.println(
                    "AWT press logical=" + e.getX() + "," + e.getY() + " button=" + e.getButton() + " tool=" + tool);
        canvas.requestFocusInWindow();
        previousX = e.getX();
        previousY = e.getY();
        if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
            if (dragBase == null && createEnd == null)
                pan = true;
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e) || dragBase != null)
            return;
        press = new Vec2(e.getX(), e.getY());
        EntityId meshId = selected();
        if (meshEditing && meshId != null && model().attachments().stream()
                .anyMatch(a -> a.id().equals(meshId) && !(a instanceof RegionAttachment))) {
            if (!host.prepareMesh())
                return;
            var pose = host.evaluatedPose();
            var editVertices = pose.paths().containsKey(meshId) ? pose.paths().get(meshId)
                    : pose.meshes().containsKey(meshId) ? pose.meshes().get(meshId).vertices()
                            : pose.bounds().containsKey(meshId) ? pose.bounds().get(meshId).vertices() : pose.clips().get(meshId).vertices();
            int pick = -1;
            double best = 10;
            for (int i = 0; i < editVertices.size(); i++) {
                var p = screen(editVertices.get(i));
                double distance = Math.hypot(p.x() - press.x(), p.y() - press.y());
                if (distance < best) {
                    best = distance;
                    pick = i;
                }
            }
            if (pick >= 0) {
                meshIndex = pick;
                host.changed();
                dragBase = model();
                dragId = meshId;
                meshDrag = true;
                invalid = false;
                host.history().beginPreview(m("editorviewport.0002"));
            }
            return;
        }
        if (tool == Tool.CREATE) {
            createEnd = press;
            return;
        }
        EntityId id = selected();
        handle = id == null ? 0 : hitHandle(press, model(), id);
        if (handle != 0) {
            if (!host.prepareTransform(id))
                return;
            dragBase = host.displayModel();
            dragId = id;
            invalid = false;
            previousAngle = 0;
            accumulatedAngle = 0;
            host.history().beginPreview(m("editorviewport.0003") + toolLabel());
            return;
        }
        try {
            // Frontmost nontransparent region wins; transparent pixels expose the skeleton
            // below.
            var pose = host.evaluatedPose();
            var picked = Picker.region(model(), pose, world(press), assets);
            if (picked.isEmpty())
                picked = Picker.bone(model(), pose, press, camera, canvas.getWidth(), canvas.getHeight());
            host.history().select(picked.map(Selection::of).orElse(Selection.EMPTY));
            host.changed();
        } catch (Exception ex) {
            host.error(ex);
        }
    }

    private void drag(MouseEvent e) {
        if (pan) {
            camera.pan(e.getX() - previousX, e.getY() - previousY);
            previousX = e.getX();
            previousY = e.getY();
            host.changed();
            return;
        }
        if (createEnd != null) {
            createEnd = new Vec2(e.getX(), e.getY());
            return;
        }
        if (dragBase == null)
            return;
        try {
            Vec2 now = new Vec2(e.getX(), e.getY()), p = world(press), q = world(now);
            var base = dragBase;
            var id = dragId;
            EditCommand command;
            if (meshDrag) {
                command = host.meshVertexEdit(id, meshIndex, q);
            } else if (tool == Tool.MOVE) {
                Vec2 delta = new Vec2(q.x() - p.x(), q.y() - p.y());
                if (space == Space.LOCAL) {
                    var inv = EditorEdits.parent(base, id).inverseOrThrow();
                    var a = inv.apply(p.x(), p.y());
                    var b = inv.apply(q.x(), q.y());
                    delta = new Vec2(b.x() - a.x(), b.y() - a.y());
                }
                Vec2 v = new Vec2(handle == 2 ? 0 : delta.x(), handle == 1 ? 0 : delta.y());
                command = host.transformEdit(d -> EditorEdits.move(d, id, v, space), EnumSet.of(Channel.TRANSLATE));
            } else if (tool == Tool.ROTATE) {
                var w = EditorEdits.world(base, id);
                Vec2 o = new Vec2(w.tx(), w.ty());
                if (space == Space.LOCAL) {
                    var inv = EditorEdits.parent(base, id).inverseOrThrow();
                    p = inv.apply(p.x(), p.y());
                    q = inv.apply(q.x(), q.y());
                    var t = EditorEdits.local(base, id);
                    o = new Vec2(t.x(), t.y());
                }
                double degrees = Math
                        .toDegrees(Math.atan2(q.y() - o.y(), q.x() - o.x()) - Math.atan2(p.y() - o.y(), p.x() - o.x()));
                // Continuous unwrapped angle across the ±180 seam, relative to the preview
                // baseline.
                double step = degrees - previousAngle;
                if (step > 180)
                    step -= 360;
                if (step < -180)
                    step += 360;
                accumulatedAngle += step;
                previousAngle = degrees;
                double angle = accumulatedAngle;
                command = host.transformEdit(d -> EditorEdits.rotate(d, id, angle, space), EnumSet.of(Channel.ROTATE));
            } else {
                Vec2 ax = axis(base, id, true), ay = axis(base, id, false);
                double dx = now.x() - press.x(), dy = now.y() - press.y();
                double sx = handle == 2 ? 1 : 1 + (dx * ax.x() + dy * ax.y()) / 64;
                double sy = handle == 1 ? 1 : 1 + (dx * ay.x() + dy * ay.y()) / 64;
                command = host.transformEdit(d -> EditorEdits.scale(d, id, sx, sy, space), EnumSet.of(Channel.SCALE));
            }
            host.history().updatePreview(command);
            invalid = false;
            host.changed();
        } catch (Exception ex) {
            invalid = true;
            System.err.println("Drag rejected: " + ex);
            host.message(m("editorviewport.0004") + ex.getMessage() + m("editorviewport.0005"));
        }
    }

    private void release(MouseEvent e) {
        if (System.getProperty("openbone.diagnostics") != null)
            System.out.println(
                    "AWT release logical=" + e.getX() + "," + e.getY() + " preview=" + host.history().isPreviewing());
        if (pan) {
            pan = false;
            return;
        }
        if (createEnd != null) {
            Vec2 start = world(press), end = world(new Vec2(e.getX(), e.getY()));
            if (Math.hypot(e.getX() - press.x(), e.getY() - press.y()) < 3)
                end = new Vec2(start.x() + 80, start.y());
            createEnd = null;
            host.createBone(start, end);
            return;
        }
        if (dragBase == null)
            return;
        if (invalid)
            host.history().cancelPreview();
        else
            host.history().commitPreview();
        dragBase = null;
        dragId = null;
        meshDrag = false;
        host.changed();
    }

    public void cancel() {
        boolean active = host.history().isPreviewing() || dragBase != null || createEnd != null || pan;
        if (dragBase != null && host.history().isPreviewing())
            host.history().cancelPreview();
        dragBase = null;
        dragId = null;
        meshDrag = false;
        createEnd = null;
        pan = false;
        invalid = false;
        if (active)
            host.changed();
    }

    private String toolLabel() {
        return switch (tool) {
            case MOVE -> m("editorviewport.0006");
            case ROTATE -> m("channel.0002");
            case SCALE -> m("channel.0003");
            default -> m("editorviewport.0007");
        };
    }

    @Override
    public void create() {
        requireEdt();
        createdAt = System.currentTimeMillis();
        batch = new PolygonSpriteBatch();
        shapes = new ShapeRenderer();
        System.out.println("GL create time=" + createdAt + " EDT=" + SwingUtilities.isEventDispatchThread()
                + " renderer=" + Gdx.gl.glGetString(GL20.GL_RENDERER));
    }

    @Override
    public void resize(int width, int height) {
        System.out.println("GL resize time=" + System.currentTimeMillis() + " EDT="
                + SwingUtilities.isEventDispatchThread() + " logical=" + width + "x" + height);
    }

    private Texture texture(ImageAssets.Image image) {
        var entry = textures.get(image.path());
        if (entry != null && entry.image() == image)
            return entry.texture();
        if (entry != null) {
            entry.texture().dispose();
            disposedTextures++;
        }
        var b = image.pixels();
        Pixmap p = new Pixmap(b.getWidth(), b.getHeight(), Pixmap.Format.RGBA8888);
        try {
            p.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < b.getHeight(); y++)
                for (int x = 0; x < b.getWidth(); x++) {
                    int argb = b.getRGB(x, y);
                    p.drawPixel(x, y, (argb << 8) | (argb >>> 24));
                }
            var t = new Texture(p);
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            textures.put(image.path(), new GpuImage(image, t));
            createdTextures++;
            return t;
        } finally {
            p.dispose();
        }
    }

    private static float f(double d) {
        return (float) Math.max(-1e7, Math.min(1e7, d));
    }

    private void line(Vec2 p, Vec2 q) {
        shapes.line(f(p.x()), f(p.y()), f(q.x()), f(q.y()));
    }

    @Override
    public void render() {
        requireEdt();
        if (stopped || batch == null)
            return;
        renders++;
        if (firstRenderAt == 0) {
            firstRenderAt = System.currentTimeMillis();
            System.out.println("GL firstRender time=" + firstRenderAt + " displayable=" + canvas.isDisplayable()
                    + " showing=" + canvas.isShowing());
        }
        Capture capture = captures.pollFirst();
        if (capture != null && capture.result().isDone()) capture = null;
        if (capture != null) {
            try { capture.prepare().run(); }
            catch (Exception failed) { capture.result().completeExceptionally(failed); capture = null; }
        }
        try { renderFrame(capture); }
        catch (RuntimeException failed) {
            if (capture != null) capture.result().completeExceptionally(failed);
            throw failed;
        }
    }

    private void renderFrame(Capture capture) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            if (capture != null) capture.result().completeExceptionally(new IllegalStateException("Viewport has no drawable area."));
            return;
        }
        var device = canvas.getGraphicsConfiguration().getDefaultTransform();
        int pw = (int) Math.round(w * device.getScaleX()), ph = (int) Math.round(h * device.getScaleY());
        if (pw != pixelWidth || ph != pixelHeight) {
            pixelWidth = pw;
            pixelHeight = ph;
            System.out
                    .println("GL viewport logical=" + w + "x" + h + " physical=" + pw + "x" + ph + " device=" + device);
        }
        Gdx.gl.glViewport(0, 0, pw, ph);
        Gdx.gl.glClearColor(32 / 255f, 40 / 255f, 48 / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        var projection = new Matrix4().setToOrtho(0, w, h, 0, -1, 1);
        shapes.setProjectionMatrix(projection);
        batch.setProjectionMatrix(projection);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(.18f, .22f, .26f, 1);
        double step = 50 * camera.zoom();
        while (step < 25)
            step *= 2;
        while (step > 150)
            step /= 2;
        Vec2 zero = screen(new Vec2(0, 0));
        double gx = ((zero.x() % step) + step) % step, gy = ((zero.y() % step) + step) % step;
        for (double x = gx; x < w; x += step)
            line(new Vec2(x, 0), new Vec2(x, h));
        for (double y = gy; y < h; y += step)
            line(new Vec2(0, y), new Vec2(w, y));
        shapes.setColor(.3f, .4f, .5f, 1);
        line(new Vec2(zero.x(), 0), new Vec2(zero.x(), h));
        line(new Vec2(0, zero.y()), new Vec2(w, zero.y()));
        shapes.end();
        var d = model();
        var pose = host.evaluatedPose();
        Set<java.nio.file.Path> used = new HashSet<>();
        TexturedScene.draw(batch, VisibleGeometry.evaluate(d, pose), source -> {
            var image = assets.get(source);
            used.add(image.path());
            return texture(image);
        }, this::screen);
        var iterator = textures.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (!used.contains(e.getKey())) {
                e.getValue().texture().dispose();
                disposedTextures++;
                iterator.remove();
            }
        }
        // Retain all referenced CPU assets including inactive attachments; GPU only
        // retains active images.
        for (var a : d.attachments())
            if (a.source() != null)
                used.add(assets.resolve(a.source()));
        assets.retain(used);
        EntityId selected = selected();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (var b : d.bones()) {
            boolean active = Objects.equals(selected, b.id())
                    || d.slots().stream().anyMatch(s -> Objects.equals(selected, s.id()) && s.boneId().equals(b.id()));
            if (active)
                shapes.setColor(1, .8f, .25f, 1);
            else if (!b.color().equals(Rgba.WHITE))
                shapes.setColor(f(b.color().r()), f(b.color().g()), f(b.color().b()), f(b.color().a()));
            else
                shapes.setColor(.65f, .78f, .88f, 1);
            var bw = pose.bones().get(b.id());
            Vec2 a = screen(bw.origin()), z = screen(bw.tip());
            line(a, z);
            shapes.circle(f(a.x()), f(a.y()), 4, 16);
            shapes.circle(f(z.x()), f(z.y()), 3, 12);
            double dx = z.x() - a.x(), dy = z.y() - a.y(), len = Math.hypot(dx, dy);
            if (len > 0) {
                Vec2 side1 = new Vec2(a.x() + dx * .2 - dy / len * 5, a.y() + dy * .2 + dx / len * 5);
                Vec2 side2 = new Vec2(a.x() + dx * .2 + dy / len * 5, a.y() + dy * .2 - dx / len * 5);
                line(a, side1);
                line(side1, z);
                line(a, side2);
                line(side2, z);
            }
        }
        if (selected != null) {
            EntityId rid = selected;
            for (var s : d.slots())
                if (s.id().equals(selected))
                    rid = s.activeAttachmentId();
            var rw = pose.regions().get(rid);
            if (rw != null) {
                shapes.setColor(1, .8f, .25f, 1);
                for (int i = 0; i < 4; i++)
                    line(screen(rw.vertices().get(i)), screen(rw.vertices().get((i + 1) % 4)));
            }
            var mw = pose.meshes().get(rid);
            if (mw != null) {
                shapes.setColor(1, .8f, .25f, 1);
                for (int i = 0; i < mw.triangles().size(); i += 3)
                    for (int j = 0; j < 3; j++)
                        line(screen(mw.vertices().get(mw.triangles().get(i + j))),
                                screen(mw.vertices().get(mw.triangles().get(i + (j + 1) % 3))));
                if (meshEditing)
                    for (int i = 0; i < mw.vertices().size(); i++) {
                        shapes.setColor(i == meshIndex ? Color.CYAN : Color.YELLOW);
                        var p = screen(mw.vertices().get(i));
                        shapes.circle(f(p.x()), f(p.y()), i == meshIndex ? 6 : 4, 16);
                    }
            }
            var clip = pose.clips().get(rid);
            if (clip != null) {
                var clipColor = ((ClippingAttachment)d.attachment(rid)).color();
                shapes.setColor(f(clipColor.r()), f(clipColor.g()), f(clipColor.b()), f(clipColor.a()));
                for (int k = 0; k < clip.vertices().size(); k++) {
                    var p = screen(clip.vertices().get(k));
                    line(p, screen(clip.vertices().get((k + 1) % clip.vertices().size())));
                    if (meshEditing) {
                        shapes.setColor(k == meshIndex ? Color.CYAN : Color.YELLOW);
                        shapes.circle(f(p.x()), f(p.y()), k == meshIndex ? 6 : 4, 16);
                        shapes.setColor(f(clipColor.r()), f(clipColor.g()), f(clipColor.b()), f(clipColor.a()));
                    }
                }
            }
            var bound = pose.bounds().get(rid);
            if (bound != null) {
                var color = ((BoundingBoxAttachment)d.attachment(rid)).color();
                for (int k = 0; k < bound.vertices().size(); k++) {
                    shapes.setColor(f(color.r()), f(color.g()), f(color.b()), f(color.a()));
                    var point = screen(bound.vertices().get(k));
                    line(point, screen(bound.vertices().get((k+1)%bound.vertices().size())));
                    if (meshEditing) { shapes.setColor(k == meshIndex ? Color.CYAN : Color.YELLOW); shapes.circle(f(point.x()),f(point.y()),4,12); }
                }
            }
            var pathVertices = pose.paths().get(rid);
            if (pathVertices != null) {
                PathAttachment path = null;
                for (var attachment : d.attachments())
                    if (attachment.id().equals(rid))
                        path = (PathAttachment) attachment;
                var chain = path.chain(pathVertices);
                shapes.setColor(.7f, .4f, 1, 1);
                for (int curve = 0; curve < path.curveCount(); curve++) {
                    var prior = chain.get(curve * 3);
                    for (int curveStep = 1; curveStep <= 32; curveStep++) {
                        double t = curveStep / 32., u = 1 - t;
                        int k = curve * 3;
                        var next = new Vec2(
                                chain.get(k).x() * u * u * u + 3 * chain.get(k + 1).x() * u * u * t
                                        + 3 * chain.get(k + 2).x() * u * t * t + chain.get(k + 3).x() * t * t * t,
                                chain.get(k).y() * u * u * u + 3 * chain.get(k + 1).y() * u * u * t
                                        + 3 * chain.get(k + 2).y() * u * t * t + chain.get(k + 3).y() * t * t * t);
                        line(screen(prior), screen(next));
                        prior = next;
                    }
                }
                if (meshEditing)
                    for (int k = 0; k < pathVertices.size(); k++) {
                        var p = screen(pathVertices.get(k));
                        shapes.setColor(k == meshIndex ? Color.CYAN : Color.YELLOW);
                        shapes.circle(f(p.x()), f(p.y()), k == meshIndex ? 6 : 4, 16);
                    }
            }
            Vec2 o = origin(d, selected);
            if (tool == Tool.ROTATE) {
                shapes.setColor(1, .8f, .25f, 1);
                shapes.circle(f(o.x()), f(o.y()), 50, 64);
            } else if (tool == Tool.MOVE || tool == Tool.SCALE) {
                for (int i = 0; i < 2; i++) {
                    shapes.setColor(i == 0 ? 1 : .3f, i == 0 ? .35f : 1, .35f, 1);
                    Vec2 axis = axis(d, selected, i == 0);
                    Vec2 end = new Vec2(o.x() + axis.x() * 64, o.y() + axis.y() * 64);
                    line(o, end);
                    shapes.rect(f(end.x() - 4), f(end.y() - 4), 8, 8);
                }
                shapes.setColor(1, .85f, .3f, 1);
                shapes.rect(f(o.x() - 6), f(o.y() - 6), 12, 12);
            }
        }
        if (createEnd != null) {
            shapes.setColor(1, .8f, .25f, 1);
            line(press, createEnd);
        }
        shapes.end();
        if (capture != null) {
            try {
                if ((long) pw * ph > 16_000_000) throw new IllegalStateException("Viewport too large for a preview.");
                byte[] rgba = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0, 0, pw, ph, true);
                var image = new java.awt.image.BufferedImage(pw, ph, java.awt.image.BufferedImage.TYPE_INT_RGB);
                int[] pixels = new int[pw * ph];
                for (int i = 0; i < pixels.length; i++) {
                    int k = i * 4;
                    pixels[i] = ((rgba[k] & 255) << 16) | ((rgba[k + 1] & 255) << 8) | (rgba[k + 2] & 255);
                }
                image.setRGB(0, 0, pw, ph, pixels, 0, pw);
                capture.result().complete(image);
            } catch (Exception failed) { capture.result().completeExceptionally(failed); }
        }
    }

    public Map<String, Object> metrics() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("logicalWidth", canvas == null ? 0 : canvas.getWidth());
        m.put("logicalHeight", canvas == null ? 0 : canvas.getHeight());
        m.put("physicalWidth", pixelWidth);
        m.put("physicalHeight", pixelHeight);
        m.put("texturesCreated", createdTextures);
        m.put("texturesDisposed", disposedTextures);
        m.put("texturesLive", textures.size());
        m.put("cpuImages", assets.size());
        m.put("renders", renders);
        m.put("createdAt", createdAt);
        m.put("firstRenderAt", firstRenderAt);
        return m;
    }

    public void close() {
        requireEdt();
        if (stopped)
            return;
        captures.forEach(c -> c.result().completeExceptionally(new IllegalStateException("Editor closed.")));
        captures.clear();
        cancel();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        backend.stop();
        stopped = true;
    }

    @Override
    public void dispose() {
        requireEdt();
        for (var t : textures.values()) {
            t.texture().dispose();
            disposedTextures++;
        }
        textures.clear();
        if (batch != null)
            batch.dispose();
        if (shapes != null)
            shapes.dispose();
        telemetry.write(canvas.isShowing(), metrics());
        System.out.println("GL dispose EDT=" + SwingUtilities.isEventDispatchThread() + " texturesCreated="
                + createdTextures + " texturesDisposed=" + disposedTextures);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("GL/UI ownership requires EDT");
    }
}
