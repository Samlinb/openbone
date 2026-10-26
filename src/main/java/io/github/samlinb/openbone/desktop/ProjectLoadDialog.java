package io.github.samlinb.openbone.desktop;

import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import io.github.samlinb.openbone.viewport.ImageAssets;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Only detached documents and CPU images cross the worker/EDT boundary. */
final class ProjectLoadDialog extends JDialog {
    record Loaded(ProjectDocument document, ImageAssets images) {}
    record Progress(String text, int completed, int total) {}
    @FunctionalInterface interface Loader {
        Loaded load(Consumer<Progress> progress) throws Exception;
    }

    private final JLabel stage = new JLabel(m("projectLoad.parsing"));
    private final JProgressBar progress = new JProgressBar();
    private final SwingWorker<Loaded, Progress> worker;
    private Loaded result;
    private Exception failure;

    private ProjectLoadDialog(Window owner, Path source, Loader loader) {
        super(owner, m("projectLoad.title"), ModalityType.APPLICATION_MODAL);
        setName("projectLoad.dialog");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(false);
        var body = new JPanel(new BorderLayout(0, WorkspaceTheme.scale(12)));
        body.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        var file = new JLabel(source.getFileName().toString());
        file.setToolTipText(source.toAbsolutePath().toString());
        file.setName("projectLoad.file");
        body.add(file, BorderLayout.NORTH);
        stage.setName("projectLoad.stage");
        body.add(stage, BorderLayout.CENTER);
        progress.setName("projectLoad.progress");
        progress.setIndeterminate(true);
        body.add(progress, BorderLayout.SOUTH);
        body.setPreferredSize(WorkspaceTheme.size(480, 135));
        setContentPane(body);
        pack();
        setLocationRelativeTo(owner);
        worker = new SwingWorker<>() {
            @Override protected Loaded doInBackground() throws Exception {
                return loader.load(update -> { if (!isCancelled()) publish(update); });
            }
            @Override protected void process(List<Progress> updates) {
                if (!isDisplayable()) return;
                var update = updates.get(updates.size() - 1);
                stage.setText(update.text());
                progress.setIndeterminate(update.total() <= 0);
                if (update.total() > 0) {
                    progress.setMaximum(update.total());
                    progress.setValue(update.completed());
                }
            }
            @Override protected void done() {
                try { result = get(); }
                catch (ExecutionException e) {
                    failure = e.getCause() instanceof Exception cause ? cause : new RuntimeException(e.getCause());
                } catch (Exception e) { failure = e; }
                finally { dispose(); }
            }
        };
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { worker.execute(); }
            @Override public void windowClosed(WindowEvent e) { if (!worker.isDone()) worker.cancel(true); }
        });
    }

    static Loaded load(Window owner, Path source, boolean importing) throws Exception {
        return load(owner, source, updates -> {
            var next = new ProjectDocument(DocumentSnapshot.EMPTY);
            if (importing) next.importSkeleton(source); else next.openResolved(source);
            var images = new ImageAssets();
            images.base(next.path().map(Path::getParent).orElse(Path.of("").toAbsolutePath()));
            var sources = next.history().state().model().attachments().stream()
                    .map(a -> a.source()).filter(java.util.Objects::nonNull).distinct().toList();
            for (int i = 0; i < sources.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                updates.accept(new Progress(m("projectLoad.images", i + 1, sources.size()), i, sources.size()));
                images.get(sources.get(i));
            }
            return new Loaded(next, images);
        });
    }

    // Modal Swing dialogs keep dispatching paints, timers and network requests.
    static Loaded load(Window owner, Path source, Loader loader) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Load dialog requires EDT");
        var dialog = new ProjectLoadDialog(owner, source, loader);
        dialog.setVisible(true);
        if (dialog.failure != null) throw dialog.failure;
        return dialog.result;
    }
}
