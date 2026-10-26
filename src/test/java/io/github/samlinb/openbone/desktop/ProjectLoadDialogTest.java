package io.github.samlinb.openbone.desktop;

import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.application.Commands;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import io.github.samlinb.openbone.viewport.ImageAssets;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectLoadDialogTest {
    @TempDir Path temp;
    JFrame owner;
    static <T> T edt(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        SwingUtilities.invokeLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    @BeforeEach void openOwner() throws Exception {
        edt(() -> { WorkspaceTheme.installTheme(); owner = new JFrame("Project loading test"); owner.setSize(700, 400); owner.setLocationRelativeTo(null); owner.setVisible(true); return null; });
    }
    @AfterEach void closeOwner() throws Exception {
        edt(() -> { owner.dispose(); return null; });
    }
    JDialog dialog() {
        for (Window window : owner.getOwnedWindows())
            if (window instanceof JDialog dialog && dialog.isShowing()) return dialog;
        throw new AssertionError("Loading dialog is not visible");
    }

    @Test void slowParserShowsModalFeedbackWhileEdtProcessesTimersAndResourceProgress() throws Exception {
        var entered = new CountDownLatch(1);
        var loadImages = new CountDownLatch(1);
        var imagesShown = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var expected = new ProjectLoadDialog.Loaded(new ProjectDocument(DocumentSnapshot.EMPTY), new ImageAssets());
        var task = new FutureTask<>(() -> ProjectLoadDialog.load(owner, temp.resolve("大型项目.json"), updates -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            entered.countDown();
            assertTrue(loadImages.await(10, TimeUnit.SECONDS));
            updates.accept(new ProjectLoadDialog.Progress("Loading image resources (2/5)...", 1, 5));
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return expected;
        }));
        SwingUtilities.invokeLater(task);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var timerRan = new CountDownLatch(1);
            edt(() -> {
                var dialog = dialog();
                assertTrue(dialog.isModal());
                assertTrue(((JProgressBar)WorkspaceDesktopTest.find(dialog, "projectLoad.progress")).isIndeterminate());
                var stage = (JLabel)WorkspaceDesktopTest.find(dialog, "projectLoad.stage");
                assertEquals(io.github.samlinb.openbone.i18n.M.m("projectLoad.parsing"), stage.getText());
                stage.addPropertyChangeListener("text", e -> { if (e.getNewValue().equals("Loading image resources (2/5)...")) imagesShown.countDown(); });
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                assertTrue(dialog.isShowing(), "Do not dismiss the feedback while parsing continues");
                var timer = new Timer(20, e -> timerRan.countDown()); timer.setRepeats(false); timer.start();
                Path output = Path.of("target/project-load-desktop/loading.png"); Files.createDirectories(output.getParent());
                var root = dialog.getRootPane();
                var image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
                ImageIO.write(image, "png", output.toFile());
                return null;
            });
            assertTrue(timerRan.await(5, TimeUnit.SECONDS), "EDT must remain responsive during parsing");
            loadImages.countDown();
            assertTrue(imagesShown.await(5, TimeUnit.SECONDS));
            edt(() -> {
                var bar = (JProgressBar)WorkspaceDesktopTest.find(dialog(), "projectLoad.progress");
                assertFalse(bar.isIndeterminate()); assertEquals(5, bar.getMaximum()); assertEquals(1, bar.getValue());
                return null;
            });
        } finally { loadImages.countDown(); finish.countDown(); }
        assertSame(expected, task.get(10, TimeUnit.SECONDS));
        edt(() -> { assertFalse(owner.getOwnedWindows()[0].isDisplayable()); return null; });
    }

    @Test void parsingFailureClosesFeedbackAndPropagatesOriginalError() throws Exception {
        var failure = new IOException("Invalid project fixture");
        var task = new FutureTask<>(() -> ProjectLoadDialog.load(owner, temp.resolve("invalid.json"), updates -> { throw failure; }));
        SwingUtilities.invokeLater(task);
        assertSame(failure, assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS)).getCause());
        edt(() -> { assertFalse(owner.getOwnedWindows()[0].isDisplayable()); return null; });
    }

    @Test void realProjectPreloadsImagesWithoutChangingSourceAndPublishesCacheWithoutRereading() throws Exception {
        Path png = temp.resolve("tile.png"), project = temp.resolve("project.json");
        var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB); pixels.setRGB(0, 0, 0xff22cc44);
        ImageIO.write(pixels, "png", png.toFile());
        var bone = new Bone(EntityId.create(), "root", null, Transform.IDENTITY, 10);
        var slot = new Slot(EntityId.create(), "slot", bone.id(), null);
        var image = new RegionAttachment(EntityId.create(), "tile", slot.id(), "tile.png", Transform.IDENTITY, 2, 2);
        var model = new DocumentSnapshot(List.of(bone), List.of(slot.withActiveAttachment(image.id())), List.of(image));
        byte[] bytes = new ProjectCodec().encode(model); Files.write(project, bytes);
        var loaded = edt(() -> ProjectLoadDialog.load(owner, project, false));
        assertArrayEquals(bytes, Files.readAllBytes(project));
        assertEquals(project, loaded.document().path().orElseThrow());
        assertFalse(loaded.document().history().isDirty());
        assertEquals(1, loaded.images().size());
        Files.delete(png);
        edt(() -> {
            var active = new ImageAssets(); active.replaceWith(loaded.images());
            var cached = active.get(png.toString());
            assertFalse(cached.missing()); assertEquals(0xff22cc44, cached.pixels().getRGB(0, 0));
            return null;
        });
    }

    @Test void disposingOwnerDuringLoadInterruptsWorkerAndDoesNotPublishResult() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var task = new FutureTask<>(() -> ProjectLoadDialog.load(owner, temp.resolve("project.json"), updates -> {
            entered.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            throw new AssertionError("Worker should be interrupted when owner closes");
        }));
        SwingUtilities.invokeLater(task);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        edt(() -> { owner.dispose(); return null; });
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertNull(task.get(10, TimeUnit.SECONDS));
    }

    @Test void failedReplacementAndCancelledSaveKeepActiveDocumentUndoAndRedo() throws Exception {
        String previous = System.getProperty("openbone.userState");
        System.setProperty("openbone.userState", temp.resolve("state").toString());
        EditorFrame frame = edt(() -> { var editor = new EditorFrame(null); editor.showEditor(); return editor; });
        try {
            Path invalid = temp.resolve("invalid.json"), valid = temp.resolve("valid.json");
            Files.writeString(invalid, "{broken"); Files.write(valid, new ProjectCodec().encode(DocumentSnapshot.EMPTY));
            edt(() -> {
                var bone = new Bone(EntityId.create(), "root", null, Transform.IDENTITY, 10);
                var model = new DocumentSnapshot(List.of(bone), List.of(), List.of());
                var history = frame.history();
                history.execute("Create bone", Commands.model(d -> model));
                history.execute("Remove bone", Commands.model(d -> DocumentSnapshot.EMPTY));
                history.undo();
                String revision = history.revision();
                var load = EditorFrame.class.getDeclaredMethod("loadProject", Path.class, boolean.class, boolean.class);
                load.setAccessible(true);
                for (Path source : List.of(invalid, valid)) {
                    var dismissed = new java.util.concurrent.atomic.AtomicBoolean();
                    var timer = new Timer(20, e -> {
                        for (Window window : frame.getOwnedWindows()) {
                            if (window.isShowing() && window instanceof JDialog dialog) {
                                var option = optionPane(dialog);
                                if (option != null) { dismissed.set(true); option.setValue(JOptionPane.CANCEL_OPTION); }
                            }
                        }
                    });
                    timer.start();
                    try { load.invoke(frame, source, false, true); }
                    finally { timer.stop(); }
                    assertTrue(dismissed.get(), "Expected error or unsaved-project prompt");
                    assertSame(history, frame.history()); assertEquals(revision, history.revision());
                    assertEquals(model, history.state().model()); assertTrue(history.isDirty());
                    assertEquals(1, history.undoCount()); assertEquals(1, history.redoCount());
                }
                return null;
            });
        } finally {
            edt(() -> {
                frame.history().markSaved();
                var close = EditorFrame.class.getDeclaredMethod("closeRequested"); close.setAccessible(true); close.invoke(frame);
                return null;
            });
            if (previous == null) System.clearProperty("openbone.userState"); else System.setProperty("openbone.userState", previous);
        }
    }

    private static JOptionPane optionPane(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JOptionPane option) return option;
            if (component instanceof Container container) { var option = optionPane(container); if (option != null) return option; }
        }
        return null;
    }
}
