package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.application.EditorEdits.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.i18n.M;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Swing document workspace. All model writes are atomic history commands on
 * EDT.
 */
public final class EditorFrame extends JFrame implements EditorViewport.Host {
    private ProjectDocument document = new ProjectDocument(DocumentSnapshot.EMPTY);
    private final ImageAssets assets = new ImageAssets();
    private final EditorViewport viewport;
    private final AnimationPanel animation;
    private final PhysicsPlayer physics = new PhysicsPlayer();
    private final JTree tree = new JTree();
    private final JPanel properties = new PropertyPanel();
    private final JLabel status = new JLabel(" ");
    private final Map<EntityId, TreePath> paths = new HashMap<>();
    private final Map<String, JComponent> named = new LinkedHashMap<>();
    private boolean refreshing, closing;
    private boolean committingField;
    private boolean fieldFailure;
    private boolean errorShowing;
    private RecoveryStore recovery;
    private ProjectDocument recoveryDocument;
    private long recoveredAt;
    private String recoveryError = "";
    private java.util.List<String> recoveryScanIssues = java.util.List.of();
    private final javax.swing.Timer recoveryTimer = new javax.swing.Timer(RecoveryStore.INTERVAL_MS,
            e -> checkpointRecovery());
    private JSplitPane hierarchySplit, propertySplit;
    private Rectangle normalBounds;
    private int propertyRevision;
    private final javax.swing.Timer diagnostics;
    private long lastInputWhen;
    private final Deque<Map<String, Object>> diagnosticKeys = new ArrayDeque<>();
    private final AWTEventListener diagnosticInput = this::observeInput;

    private void observeInput(AWTEvent event) {
        if (event instanceof KeyEvent k && (k.getID() == KeyEvent.KEY_PRESSED || k.getID() == KeyEvent.KEY_RELEASED)) {
            lastInputWhen = Math.max(lastInputWhen, k.getWhen());
            var item = new LinkedHashMap<String, Object>();
            var source = k.getComponent();
            item.put("when", k.getWhen());
            item.put("event", k.getID());
            item.put("key", k.getKeyCode());
            item.put("modifiers", k.getModifiersEx());
            item.put("sourceClass", source.getClass().getSimpleName());
            item.put("sourceIdentity", System.identityHashCode(source));
            item.put("showing", source.isShowing());
            item.put("sourceName", String.valueOf(source.getName()));
            item.put("focused", source.isFocusOwner());
            item.put("enabled", source.isEnabled());
            if (source instanceof JTextField f) {
                item.put("beforeText", f.getText());
                item.put("editable", f.isEditable());
                item.put("selection", List.of(f.getSelectionStart(), f.getSelectionEnd()));
            }
            if (k.getID() == KeyEvent.KEY_PRESSED && k.getKeyCode() == KeyEvent.VK_V)
                item.put("clipboard", clipboardReceipt());
            diagnosticKeys.addLast(item);
            while (diagnosticKeys.size() > 64)
                diagnosticKeys.removeFirst();
            SwingUtilities.invokeLater(() -> {
                item.put("consumed", k.isConsumed());
                if (source instanceof JTextField f) {
                    item.put("afterText", f.getText());
                    item.put("afterSelection", List.of(f.getSelectionStart(), f.getSelectionEnd()));
                }
            });
        } else if (event instanceof InputMethodEvent ime) {
            var item = new LinkedHashMap<String, Object>();
            item.put("event", "IME");
            item.put("when", ime.getWhen());
            item.put("committed", ime.getCommittedCharacterCount());
            var chars = ime.getText();
            StringBuilder text = new StringBuilder();
            if (chars != null)
                for (char c = chars.first(); c != java.text.CharacterIterator.DONE; c = chars.next())
                    text.append(c);
            item.put("text", text.toString());
            item.put("sourceName", String.valueOf(((Component) ime.getSource()).getName()));
            diagnosticKeys.addLast(item);
            while (diagnosticKeys.size() > 64)
                diagnosticKeys.removeFirst();
        } else if (event instanceof MouseEvent m
                && (m.getID() == MouseEvent.MOUSE_RELEASED || m.getID() == MouseEvent.MOUSE_WHEEL))
            lastInputWhen = Math.max(lastInputWhen, m.getWhen());
    }

    private static Map<String, Object> clipboardReceipt() {
        try {
            var c = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!c.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor))
                return Map.of("status", "NO_TEXT");
            String text = (String) c.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return Map.of("status", "READY", "text", text.length() <= 100000 ? text : text.substring(0, 100000),
                    "observedAt", System.currentTimeMillis());
        } catch (Exception e) {
            return Map.of("status", "UNAVAILABLE", "error",
                    e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private final Action undo, redo;

    private record Item(EntityId id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record Choice(EntityId id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class PropertyPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(285, 600);
        }

        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 24;
        }

        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(24, r.height - 24);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    public EditorFrame(Path open) {
        super("Openbone");
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(Math.min(1000, screen.width - 40), Math.min(680, screen.height - 60)));
        setSize(Math.min(1360, screen.width - 40), Math.min(860, screen.height - 60));
        setLocationRelativeTo(null);
        undo = action(m("editorframe.0001"), () -> {
            if (!commitFocusedField())
                return;
            viewportCancel();
            history().undo();
            changed();
        });
        redo = action(m("editorframe.0002"), () -> {
            if (!commitFocusedField())
                return;
            viewportCancel();
            history().redo();
            changed();
        });
        viewport = new EditorViewport(this, assets);
        animation = new AnimationPanel(new AnimationPanel.Host() {
            public CommandHistory history() {
                return EditorFrame.this.history();
            }

            public Bone selectedBone() {
                return EditorFrame.this.selectedBone();
            }

            public boolean commitFields() {
                return commitFocusedField();
            }

            public void changed() {
                EditorFrame.this.changed();
            }

            public void error(Exception e) {
                EditorFrame.this.error(e);
            }

            public void cancelViewport() {
                viewportCancel();
            }

            public void poseChanged() {
                refreshPoseFields();
            }

            public Map<String, JComponent> controls() {
                return named;
            }
        });
        setJMenuBar(menus());
        JPanel main = new JPanel(new BorderLayout());
        main.add(toolbar(), BorderLayout.NORTH);
        tree.setName("hierarchy");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(25);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            if (refreshing)
                return;
            viewportCancel();
            var n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            EntityId id = n != null && n.getUserObject() instanceof Item i ? i.id() : null;
            history().select(id == null ? Selection.EMPTY : Selection.of(id));
            changed();
        });
        properties.setLayout(new BoxLayout(properties, BoxLayout.Y_AXIS));
        properties.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane props = new JScrollPane(properties);
        props.setMinimumSize(new Dimension(285, 100));
        props.setPreferredSize(new Dimension(300, 600));
        JPanel center = new JPanel(new BorderLayout());
        center.add(viewport.canvas());
        JLabel hint = new JLabel(m("editorframe.0003"));
        hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        center.add(hint, BorderLayout.SOUTH);
        JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, props);
        right.setResizeWeight(1);
        right.setDividerLocation(Math.max(300, getWidth() - 220 - 310));
        JScrollPane hierarchy = new JScrollPane(tree);
        hierarchy.setPreferredSize(new Dimension(220, 600));
        hierarchy.setMinimumSize(new Dimension(160, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, hierarchy, right);
        split.setDividerLocation(220);
        split.setResizeWeight(0);
        hierarchySplit = split;
        propertySplit = right;
        try {
            WorkspaceLayout.restore(this, split, right, WorkspaceLayout.read(UserState.root().resolve("layout.json")));
        } catch (Exception absent) {
            System.out.println("Layout defaults: " + absent.getMessage());
        }
        normalBounds = getBounds();
        addComponentListener(new ComponentAdapter() {
            public void componentMoved(ComponentEvent e) {
                rememberBounds();
            }

            public void componentResized(ComponentEvent e) {
                rememberBounds();
            }
        });
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.add(split);
        workspace.add(animation, BorderLayout.SOUTH);
        main.add(workspace, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        main.add(status, BorderLayout.SOUTH);
        setContentPane(main);
        bind("control Z", undo);
        bind("control Y", redo);
        bind("control shift Z", redo);
        bind("control N", action(m("animationpanel.0007"), this::newProject));
        bind("control O", action(m("editorframe.0004"), this::openProject));
        bind("control S", action(m("editorframe.0005"), () -> save(false)));
        bind("control shift S", action(m("editorframe.0006"), () -> save(true)));
        bind("control alt S", action(m("editorframe.0007"), this::savePortable));
        bind("control alt O", action(m("editorframe.0008"), this::importSkeleton));
        bind("control alt E", action(m("editorframe.0009"), this::exportSkeleton));
        bind("ESCAPE", action(m("editorframe.0010"), () -> {
            viewport.cancel();
            animation.cancel();
            changed();
            message(m("editorframe.0011"));
        }));
        bind("DELETE", action(m("editorframe.0012"), this::deleteSelected));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested();
            }
        });
        diagnostics = new javax.swing.Timer(System.getProperty("openbone.performance") == null ? 400 : 5000,
                e -> writeDiagnostics());
        if (System.getProperty("openbone.diagnostics") != null) {
            Toolkit.getDefaultToolkit().addAWTEventListener(diagnosticInput, AWTEvent.KEY_EVENT_MASK
                    | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.INPUT_METHOD_EVENT_MASK);
            diagnostics.start();
        }
        changed();
        if (open != null)
            guard(() -> {
                openResolved(open);
                resetAssets();
                changed();
            });
        try {
            recovery = new RecoveryStore(UserState.root().resolve("recovery"));
            recoveryDocument = document;
            recoveryTimer.start();
        } catch (Exception unavailable) {
            recoveryError = unavailable.toString();
            message(m("editorframe.0013") + unavailable.getMessage());
        }
    }

    public void showEditor() {
        setVisible(true);
        viewport.canvas().requestFocusInWindow();
        SwingUtilities.invokeLater(() -> {
            chooseRecovery(false);
        });
    }

    private void rememberBounds() {
        if ((getExtendedState() & MAXIMIZED_BOTH) == 0 && isShowing())
            normalBounds = getBounds();
    }

    private void checkpointRecovery() {
        if (recovery == null || closing || history().isPreviewing())
            return;
        try {
            if (!history().isDirty()) {
                recovery.clear();
                recoveredAt = 0;
                return;
            }
            recovery.snapshot(model(), document.path().orElse(null),
                    document.path().map(Path::getParent).orElse(Path.of("").toAbsolutePath()));
            recoveredAt = System.currentTimeMillis();
            recoveryError = "";
        } catch (Exception failed) {
            recoveryError = failed.toString();
            message(m("editorframe.0014") + failed.getMessage());
            System.err.println(recoveryError);
        }
    }

    private void chooseRecovery(boolean explicit) {
        java.util.List<RecoveryStore.Candidate> candidates = java.util.List.of();
        try {
            recoveryScanIssues = java.util.List.of();
            var issues = new ArrayList<RecoveryStore.ScanIssue>();
            candidates = RecoveryStore.candidates(UserState.root().resolve("recovery"), issues::add);
            recoveryScanIssues = issues.stream().map(Object::toString).toList();
            for (String issue : recoveryScanIssues)
                System.err.println("Recovery scan: " + issue);
            String warnings = String.join("\n", recoveryScanIssues.stream().limit(20).toList());
            if (recoveryScanIssues.size() > 20)
                warnings += m("editorframe.0015") + (recoveryScanIssues.size() - 20) + m("editorframe.0016");
            if (candidates.isEmpty()) {
                if (explicit || !issues.isEmpty())
                    JOptionPane.showMessageDialog(this,
                            issues.isEmpty() ? m("editorframe.0017")
                                    : new Object[] { m("editorframe.0018"), recoveryWarnings(warnings) },
                            m("editorframe.0019"),
                            issues.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                return;
            }
            var list = new JList<>(candidates.toArray(RecoveryStore.Candidate[]::new));
            list.setSelectedIndex(0);
            list.setVisibleRowCount(Math.min(5, candidates.size()));
            list.setFixedCellWidth(520);
            list.setFixedCellHeight(Math.max(24, list.getFontMetrics(list.getFont()).getHeight() + 6));
            var snapshots = new JScrollPane(list);
            snapshots.setPreferredSize(
                    new Dimension(560, list.getFixedCellHeight() * Math.min(5, candidates.size()) + 24));
            var body = new JPanel(new BorderLayout(6, 8));
            body.add(new JLabel(m("editorframe.0020")), BorderLayout.NORTH);
            body.add(snapshots);
            if (!issues.isEmpty())
                body.add(recoveryWarnings(warnings), BorderLayout.SOUTH);
            int decision = JOptionPane.showOptionDialog(this, body, m("editorframe.0021"), JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null,
                    new String[] { m("editorframe.0022"), m("editorframe.0023"), m("editorframe.0024") },
                    m("editorframe.0022"));
            var chosen = list.getSelectedValue();
            if (chosen == null)
                return;
            if (decision == 0) {
                if (chosen.model == null)
                    throw new IllegalArgumentException(m("editorframe.0025") + chosen.error + m("editorframe.0026"));
                if (!confirmSave())
                    return;
                if (recovery == null)
                    throw new IllegalStateException(m("editorframe.0027"));
                var next = new ProjectDocument(chosen.model);
                next.history().markUnsaved();
                // Transfer durability before releasing the old candidate or publishing model
                // state.
                recovery.snapshot(chosen.model, null, Path.of("").toAbsolutePath());
                recoveredAt = System.currentTimeMillis();
                document = next;
                recoveryDocument = next;
                chosen.discard();
                animation.reset();
                resetAssets();
                viewport.camera().reset();
                changed();
                message(m("editorframe.0028") + chosen.originalPath + m("editorframe.0029"));
            } else if (decision == 1) {
                chosen.discard();
                message(m("editorframe.0030"));
            }
        } catch (Exception failure) {
            error(failure);
        } finally {
            for (var c : candidates)
                try {
                    c.close();
                } catch (Exception failure) {
                    System.err.println(failure);
                }
        }
    }

    private JScrollPane recoveryWarnings(String warnings) {
        var text = new JTextArea(m("editorframe.0031") + warnings, 4, 52);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        return new JScrollPane(text);
    }

    public ProjectDocument document() {
        return document;
    }

    public EditorViewport viewport() {
        return viewport;
    }

    @Override
    public CommandHistory history() {
        return document.history();
    }

    private DocumentSnapshot model() {
        return history().state().model();
    }

    private EntityId selected() {
        return history().state().selection().primary().orElse(null);
    }

    private Bone selectedBone() {
        var id = selected();
        if (id == null)
            return null;
        for (var b : model().bones())
            if (b.id().equals(id))
                return b;
        for (var s : model().slots())
            if (s.id().equals(id))
                return model().bone(s.boneId());
        for (var a : model().attachments())
            if (a.id().equals(id))
                return model().bone(slot(a.slotId()).boneId());
        return null;
    }

    private Slot slot(EntityId id) {
        return model().slots().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private Action action(String name, Runnable run) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                guard(run::run);
            }
        };
    }

    private void bind(String key, Action action) {
        var stroke = KeyStroke.getKeyStroke(key);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, key);
        getRootPane().getActionMap().put(key, action);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private void guard(Checked run) {
        try {
            run.run();
        } catch (Exception e) {
            error(e);
        }
    }

    private void execute(String label, EditCommand edit) {
        history().execute(label, edit);
        changed();
    }

    private void edit(String label, UnaryOperator<DocumentSnapshot> edit) {
        execute(label, Commands.model(edit));
    }

    private void viewportCancel() {
        if (viewport != null)
            viewport.cancel();
        if (animation != null)
            animation.cancel();
    }

    private JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu(m("editorframe.0032")), edit = new JMenu(m("editorframe.0033")),
                view = new JMenu(m("editorframe.0034"));
        menu(file, m("animationpanel.0007"), KeyStroke.getKeyStroke("control N"), this::newProject);
        menu(file, m("editorframe.0035"), KeyStroke.getKeyStroke("control O"), this::openProject);
        menu(file, m("editorframe.0005"), KeyStroke.getKeyStroke("control S"), () -> save(false));
        menu(file, m("editorframe.0036"), KeyStroke.getKeyStroke("control shift S"), () -> save(true));
        menu(file, m("editorframe.0037"), KeyStroke.getKeyStroke("control alt S"), this::savePortable);
        menu(file, m("editorframe.0038"), null, () -> chooseRecovery(true));
        file.addSeparator();
        menu(file, m("editorframe.0039"), KeyStroke.getKeyStroke("control alt O"), this::importSkeleton);
        menu(file, m("editorframe.0040"), KeyStroke.getKeyStroke("control alt E"), this::exportSkeleton);
        file.addSeparator();
        menu(file, m("editorframe.0041"), KeyStroke.getKeyStroke("control I"), this::importImage);
        file.addSeparator();
        menu(file, m("editorframe.0042"), null, this::closeRequested);
        JMenuItem u = new JMenuItem(undo);
        u.setAccelerator(KeyStroke.getKeyStroke("control Z"));
        edit.add(u);
        JMenuItem r = new JMenuItem(redo);
        r.setAccelerator(KeyStroke.getKeyStroke("control Y"));
        edit.add(r);
        menu(edit, m("editorframe.0043"), KeyStroke.getKeyStroke("DELETE"), this::deleteSelected);
        menu(edit, m("editorframe.0044"), null, this::createSlot);
        menu(edit, m("editorframe.0045"), null, this::reparent);
        menu(edit, m("editorframe.0046"), null, this::createClipping);
        menu(edit, m("editorframe.0047"), null, this::createIk);
        menu(edit, m("editorframe.0048"), null, this::createTwoIk);
        menu(edit, m("editorframe.0049"), null, this::createTransformConstraint);
        menu(edit, m("editorframe.0050"), null, this::createPathAttachment);
        menu(edit, m("editorframe.0051"), null, this::createPathConstraint);
        menu(edit, m("editorframe.0052"), null, this::createPhysicsConstraint);
        menu(view, m("editorframe.0053"), null, () -> {
            viewport.camera().reset();
            changed();
        });
        menu(view, m("editorframe.0054"), null, () -> {
            assets.reload();
            changed();
        });
        JMenu language = new JMenu(m("menu.language"));
        ButtonGroup languageGroup = new ButtonGroup();
        for (Locale option : M.supported()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(languageName(option));
            item.setSelected(option.equals(M.locale()));
            item.addActionListener(e -> {
                M.storePreference(option);
                JOptionPane.showMessageDialog(this, m("language.restart"), m("menu.language"),
                        JOptionPane.INFORMATION_MESSAGE);
            });
            languageGroup.add(item);
            language.add(item);
        }
        view.add(language);
        bar.add(file);
        bar.add(edit);
        bar.add(view);
        return bar;

    }

    /** Language menu labels stay in their native form regardless of UI locale. */
    private static String languageName(Locale locale) {
        if (M.ZH_CN.equals(locale))
            return "简体中文";
        if (M.ZH_TW.equals(locale))
            return "繁體中文";
        return "English";
    }

    private void menu(JMenu m, String label, KeyStroke key, Runnable fn) {
        var item = new JMenuItem(action(label, fn));
        if (key != null)
            item.setAccelerator(key);
        m.add(item);
    }

    private JToolBar toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        button(bar, m("animationpanel.0007"), this::newProject);
        button(bar, m("editorframe.0004"), this::openProject);
        button(bar, m("editorframe.0005"), () -> save(false));
        bar.addSeparator();
        ButtonGroup group = new ButtonGroup();
        String[] labels = { m("editorframe.0055"), m("editorframe.0056"), m("editorframe.0057"), m("editorframe.0058"),
                m("editorframe.0059") };
        Tool[] values = Tool.values();
        for (int i = 0; i < values.length; i++) {
            var value = values[i];
            JToggleButton b = new JToggleButton(labels[i]);
            b.setName("tool." + value);
            named.put(b.getName(), b);
            group.add(b);
            b.setSelected(i == 0);
            b.addActionListener(e -> viewport.tool(value));
            bar.add(b);
        }
        JComboBox<String> space = new JComboBox<>(new String[] { m("editorframe.0060"), m("editorframe.0061") });
        space.setMaximumSize(new Dimension(115, 30));
        space.setName("space");
        named.put("space", space);
        space.addActionListener(e -> viewport.space(space.getSelectedIndex() == 0 ? Space.LOCAL : Space.WORLD));
        bar.add(space);
        JComboBox<String> zoom = new JComboBox<>(new String[] { "30%", "100%", "140%", "400%" });
        zoom.setSelectedIndex(1);
        zoom.setMaximumSize(new Dimension(78, 30));
        zoom.setName("zoom");
        named.put("zoom", zoom);
        zoom.addActionListener(e -> {
            double z = new double[] { .3, 1, 1.4, 4 }[zoom.getSelectedIndex()];
            viewport.camera().zoomAt(z, viewport.canvas().getWidth() / 2.0, viewport.canvas().getHeight() / 2.0,
                    viewport.canvas().getWidth(), viewport.canvas().getHeight());
            changed();
        });
        bar.add(zoom);
        bar.addSeparator();
        button(bar, m("editorframe.0062"), this::createSlot);
        button(bar, m("editorframe.0063"), this::importImage);
        button(bar, m("editorframe.0064"), this::reparent);
        bar.addSeparator();
        bar.add(new JButton(undo));
        bar.add(new JButton(redo));
        return bar;
    }

    private void button(JToolBar bar, String label, Runnable fn) {
        var b = new JButton(action(label, fn));
        b.setName(label);
        named.put(label, b);
        bar.add(b);
    }

    @Override
    public void shortcut(int key, int modifiers) {
        boolean ctrl = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0,
                shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        guard(() -> {
            if (key == KeyEvent.VK_ESCAPE) {
                viewport.cancel();
                animation.cancel();
                changed();
                return;
            }
            if (ctrl && (modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
                switch (key) {
                    case KeyEvent.VK_S -> savePortable();
                    case KeyEvent.VK_O -> importSkeleton();
                    case KeyEvent.VK_E -> exportSkeleton();
                    default -> {
                    }
                }
                return;
            }
            if (ctrl) {
                switch (key) {
                    case KeyEvent.VK_Z -> {
                        if (shift)
                            redo.actionPerformed(null);
                        else
                            undo.actionPerformed(null);
                    }
                    case KeyEvent.VK_Y -> redo.actionPerformed(null);
                    case KeyEvent.VK_S -> save(shift);
                    case KeyEvent.VK_N -> newProject();
                    case KeyEvent.VK_O -> openProject();
                    case KeyEvent.VK_I -> importImage();
                    default -> {
                    }
                }
                return;
            }
            if (key == KeyEvent.VK_DELETE) {
                deleteSelected();
                return;
            }
            Tool t = switch (key) {
                case KeyEvent.VK_Q -> Tool.SELECT;
                case KeyEvent.VK_N -> Tool.CREATE;
                case KeyEvent.VK_W -> Tool.MOVE;
                case KeyEvent.VK_E -> Tool.ROTATE;
                case KeyEvent.VK_R -> Tool.SCALE;
                default -> null;
            };
            if (t != null) {
                ((JToggleButton) named.get("tool." + t)).setSelected(true);
                viewport.tool(t);
            }
        });
    }

    @Override
    public void changed() {
        if (refreshing || closing)
            return;
        if (recovery != null && (recoveryDocument != document || !history().isDirty()))
            try {
                recovery.clear();
                recoveryDocument = document;
                recoveredAt = 0;
            } catch (Exception failed) {
                recoveryError = failed.toString();
            }
        physics.invalidate(model());
        // Wheel/camera refresh does not necessarily transfer keyboard focus. Commit an
        // edited
        // field before rebuilding it, just as a normal focus transfer or Save does.
        if (!committingField)
            commitFocusedField();
        refreshing = true;
        try {
            setTitle("Openbone — " + document.path().map(p -> p.getFileName().toString()).orElse(m("editorframe.0065"))
                    + (history().isDirty() ? " *" : ""));
            undo.setEnabled(history().undoCount() > 0);
            redo.setEnabled(history().redoCount() > 0);
            rebuildTree();
            rebuildProperties();
            animation.refresh();
            long missing = model().attachments().stream().filter(a -> a.source() != null)
                    .map(a -> assets.get(a.source())).filter(ImageAssets.Image::missing).count();
            status.setText(m("editorframe.0066") + model().bones().size() + m("editorframe.0067")
                    + model().slots().size() + m("editorframe.0068") + model().attachments().size()
                    + m("editorframe.0069") + String.format(Locale.ROOT, "%.0f%%", viewport.camera().zoom() * 100)
                    + "   " + (viewport.space() == Space.LOCAL ? m("editorframe.0060") : m("editorframe.0061"))
                    + (history().isPreviewing() ? m("editorframe.0070") : "")
                    + (missing > 0 ? m("editorframe.0071") + missing + m("editorframe.0072") : ""));
        } finally {
            refreshing = false;
        }
    }

    @Override
    public DocumentSnapshot displayModel() {
        return animation == null ? model() : animation.display();
    }

    @Override
    public boolean prepareMesh() {
        if (animation.animate() && model().attachments().stream().anyMatch(
                a -> a.id().equals(selected()) && (a instanceof ClippingAttachment || a instanceof PathAttachment))) {
            message(m("editorframe.0073"));
            return false;
        }
        return animation.editing();
    }

    @Override
    public EditCommand meshVertexEdit(EntityId id, int index, Vec2 target) {
        boolean animate = animation.animate();
        double time = animation.time();
        return Commands.model(d -> {
            if (d.attachments().stream().anyMatch(a -> a.id().equals(id) && a instanceof PathAttachment))
                return PathEdits.moveWorld(d, id, index, target);
            if (d.attachments().stream().anyMatch(a -> a.id().equals(id) && a instanceof ClippingAttachment))
                return ClippingEdits.moveWorld(d, id, index, target);
            if (!animate)
                return MeshEdits.moveWorld(d, id, index, target);
            var next = animation.withActiveDefault(d);
            return DeformEdits.moveWorld(next, animation.active(), time, id, index, target);
        });
    }

    @Override
    public WorldPose evaluatedPose() {
        if (animation != null && animation.animate()) {
            if (PhysicsPlayer.active(model()))
                return physics.seek(model(), animation.active(), animation.time(), false);
            if (!model().animations().isEmpty())
                return AnimationSampler.world(model(), animation.active(), animation.time(), false);
        }
        return model().evaluateWorld();
    }

    @Override
    public boolean prepareTransform(EntityId id) {
        if (!animation.editing())
            return false;
        if (animation.animate() && animation.time() > 0 && PhysicsPlayer.active(model())) {
            message(m("editorframe.0074"));
            return false;
        }
        if (animation.animate() && model().attachments().stream().anyMatch(a -> a.id().equals(id))) {
            message(m("editorframe.0075"));
            return false;
        }
        return true;
    }

    @Override
    public EditCommand transformEdit(UnaryOperator<DocumentSnapshot> operation, Set<Channel> channels) {
        EntityId id = selected();
        return Commands.model(d -> animation.animate()
                ? AnimationEdits.transform(animation.withActiveDefault(d), animation.active(), animation.time(), id,
                        operation, channels)
                : operation.apply(d));
    }

    private void refreshPoseFields() {
        if (closing)
            return;
        // Playback never rebuilds a focused input or runs a model command.
        if (animation.playing()) {
            var id = selected();
            if (id != null)
                try {
                    var t = viewport.space() == Space.LOCAL ? EditorEdits.local(displayModel(), id)
                            : EditorEdits.world(displayModel(), id).toTrsOrThrow();
                    String[] keys = { "x", "y", "rotation", "scaleX", "scaleY" };
                    double[] values = { t.x(), t.y(), t.rotation(), t.scaleX(), t.scaleY() };
                    for (int i = 0; i < keys.length; i++)
                        if (named.get("property." + keys[i]) instanceof JTextField f && !f.isFocusOwner())
                            f.setText(number(values[i]));
                } catch (IllegalArgumentException ignored) {
                }
        } else
            changed();
    }

    private boolean setupOnly() {
        if (!commitFocusedField())
            return false;
        if (animation.animate()) {
            message(m("editorframe.0076"));
            return false;
        }
        return true;
    }

    private void rebuildTree() {
        paths.clear();
        var root = new DefaultMutableTreeNode(m("editorframe.0077"));
        Map<EntityId, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
        for (var b : model().bones()) {
            var n = new DefaultMutableTreeNode(new Item(b.id(), m("editorframe.0078") + b.name()));
            nodes.put(b.id(), n);
            (b.parentId() == null ? root : nodes.get(b.parentId())).add(n);
        }
        for (var s : model().slots()) {
            var n = new DefaultMutableTreeNode(
                    new Item(s.id(), m("editorframe.0079") + s.name() + " [" + model().slots().indexOf(s) + "]"));
            nodes.put(s.id(), n);
            nodes.get(s.boneId()).add(n);
        }
        for (var a : model().attachments()) {
            var n = new DefaultMutableTreeNode(new Item(a.id(),
                    (a instanceof PathAttachment ? m("editorframe.0080")
                            : a instanceof ClippingAttachment ? m("editorframe.0081")
                                    : a instanceof MeshAttachment ? m("editorframe.0082") : m("editorframe.0083"))
                            + " · " + a.name()
                            + (Objects.equals(slot(a.slotId()).activeAttachmentId(), a.id()) ? " ●" : "")));
            nodes.put(a.id(), n);
            nodes.get(a.slotId()).add(n);
        }
        for (var c : model().constraints()) {
            var n = new DefaultMutableTreeNode(new Item(c.id(), m("editorframe.0084") + c.name() + " [" + c.order()
                    + "]" + (c.enabled() ? "" : m("editorframe.0085"))));
            nodes.put(c.id(), n);
            root.add(n);
        }
        tree.setModel(new DefaultTreeModel(root));
        for (var e : nodes.entrySet())
            paths.put(e.getKey(), new TreePath(e.getValue().getPath()));
        for (int i = 0; i < tree.getRowCount(); i++)
            tree.expandRow(i);
        if (selected() != null) {
            tree.setSelectionPath(paths.get(selected()));
            tree.scrollPathToVisible(paths.get(selected()));
        }
    }

    private void rebuildProperties() {
        propertyRevision++;
        properties.removeAll();
        named.keySet().removeIf(k -> k.startsWith("property."));
        EntityId id = selected();
        if (id != null && model().constraints().stream().anyMatch(c -> c.id().equals(id))) {
            var c = model().constraint(id);
            if (c instanceof IkConstraint ik)
                constraintProperties(ik);
            else if (c instanceof TransformConstraint t)
                transformConstraintProperties(t);
            else if (c instanceof PathConstraint p)
                pathConstraintProperties(p);
            else if (c instanceof PhysicsConstraint p)
                physicsProperties(p);
            finishProperties();
            return;
        }
        if (id == null) {
            title(m("editorframe.0086"));
            text(m("editorframe.0087"));
            text(m("editorframe.0088"));
            text(m("editorframe.0089"));
            text(m("editorframe.0090"));
            finishProperties();
            return;
        }
        Bone b = model().bones().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        Slot s = model().slots().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        Attachment attachment = model().attachments().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        RegionAttachment r = attachment instanceof RegionAttachment region ? region : null;
        MeshAttachment mesh = attachment instanceof MeshAttachment m ? m : null;
        ClippingAttachment clipping = attachment instanceof ClippingAttachment c ? c : null;
        title(b != null ? m("editorframe.0091") : s != null ? m("editorframe.0062") : m("editorframe.0092"));
        if (animation.animate()) {
            if (b != null || s != null) {
                text(m("editorframe.0093"));
                transformFields(id);
            } else if (mesh != null)
                meshAnimationProperties(mesh);
            else
                text(clipping != null ? m("editorframe.0094") : m("editorframe.0095"));
            finishProperties();
            return;
        }
        if (b != null) {
            field("name", m("editorframe.0096"), b.name(),
                    value -> edit(m("editorframe.0097"), d -> ModelEdits.editBone(d, id, v -> v.withName(value))));
            transformFields(id);
            field("length", m("editorframe.0098"), number(b.length()), value -> {
                double v = parse(value);
                edit(m("editorframe.0099"),
                        d -> ModelEdits.editBone(d, id, a -> new Bone(a.id(), a.name(), a.parentId(), a.local(), v)));
            });
            text(m("editorframe.0100")
                    + (b.parentId() == null ? m("editorframe.0101") : model().bone(b.parentId()).name()));
            propertyButton(m("editorframe.0045"), this::reparent);
            propertyButton(m("editorframe.0044"), this::createSlot);
            propertyButton(m("editorframe.0041"), this::importImage);
            propertyButton(m("editorframe.0047"), this::createIk);
            propertyButton(m("editorframe.0048"), this::createTwoIk);
            propertyButton(m("editorframe.0049"), this::createTransformConstraint);
            propertyButton(m("editorframe.0050"), this::createPathAttachment);
            propertyButton(m("editorframe.0051"), this::createPathConstraint);
            propertyButton(m("editorframe.0052"), this::createPhysicsConstraint);
        } else if (s != null) {
            field("name", m("editorframe.0096"), s.name(), value -> edit(m("editorframe.0102"), d -> ModelEdits
                    .editSlot(d, id, v -> new Slot(v.id(), value, v.boneId(), v.activeAttachmentId(), v.blend()))));
            JComboBox<Choice> bones = boneChoices(false, s.boneId());
            bones.setName("property.binding");
            named.put(bones.getName(), bones);
            row(m("editorframe.0103"), bones);
            bones.addActionListener(e -> {
                if (refreshing)
                    return;
                var target = ((Choice) bones.getSelectedItem()).id();
                guard(() -> edit(m("editorframe.0104"), d -> ModelEdits.editSlot(d, id,
                        v -> new Slot(v.id(), v.name(), target, v.activeAttachmentId(), v.blend()))));
            });
            JComboBox<Choice> active = new JComboBox<>();
            active.addItem(new Choice(null, m("editorframe.0105")));
            for (var a : model().attachments())
                if (a.slotId().equals(id))
                    active.addItem(new Choice(a.id(), a.name()));
            choose(active, s.activeAttachmentId());
            row(m("editorframe.0106"), active);
            active.addActionListener(e -> {
                if (refreshing)
                    return;
                var a = ((Choice) active.getSelectedItem()).id();
                guard(() -> edit(m("editorframe.0107"),
                        d -> ModelEdits.editSlot(d, id, v -> v.withActiveAttachment(a))));
            });
            text(m("editorframe.0108") + model().slots().indexOf(s) + m("editorframe.0109"));
            propertyButton(m("editorframe.0110"), () -> edit(m("editorframe.0111"), d -> EditorEdits.order(d, id, 1)));
            propertyButton(m("editorframe.0112"), () -> edit(m("editorframe.0111"), d -> EditorEdits.order(d, id, -1)));
            propertyButton(m("editorframe.0041"), this::importImage);
            propertyButton(m("editorframe.0046"), this::createClipping);
            text(m("editorframe.0113"));
        } else if (r != null) {
            field("name", m("editorframe.0096"), r.name(),
                    value -> edit(m("editorframe.0114"),
                            d -> ModelEdits.editRegion(d, id, v -> new RegionAttachment(v.id(), value, v.slotId(),
                                    v.source(), v.local(), v.width(), v.height()))));
            transformFields(id);
            field("width", m("editorframe.0115"), number(r.width()), value -> {
                double v = parse(value);
                edit(m("editorframe.0116"), d -> ModelEdits.editRegion(d, id,
                        a -> new RegionAttachment(a.id(), a.name(), a.slotId(), a.source(), a.local(), v, a.height())));
            });
            field("height", m("editorframe.0117"), number(r.height()), value -> {
                double v = parse(value);
                edit(m("editorframe.0118"), d -> ModelEdits.editRegion(d, id,
                        a -> new RegionAttachment(a.id(), a.name(), a.slotId(), a.source(), a.local(), a.width(), v)));
            });
            text(assets.get(r.source()).missing() ? m("editorframe.0119") : m("editorframe.0120"));
            JTextArea source = new JTextArea(r.source());
            source.setLineWrap(true);
            source.setWrapStyleWord(true);
            source.setEditable(false);
            source.setRows(3);
            source.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
            properties.add(source);
            propertyButton(m("editorframe.0121"), () -> relocate(r));
            propertyButton(m("editorframe.0122"), () -> edit(m("editorframe.0123"), d -> MeshEdits.convert(d, id)));
        } else if (mesh != null) {
            meshProperties(mesh);
        } else if (clipping != null) {
            clippingProperties(clipping);
        } else if (attachment instanceof PathAttachment path) {
            pathProperties(path);
        }
        propertyButton(m("editorframe.0043"), this::deleteSelected);
        finishProperties();
    }

    private void createIk() {
        createIk(false);
    }

    private void createTransformConstraint() {
        if (!setupOnly())
            return;
        viewportCancel();
        var bone = selectedBone();
        if (bone == null || model().bones().size() < 2) {
            message(m("editorframe.0124"));
            return;
        }
        var name = new JTextField(
                unique(m("editorframe.0125"), model().constraints().stream().map(Constraint::name).toList()));
        name.setName("constraint.name");
        var target = boneChoices(false, null);
        target.setName("constraint.target");
        var form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel(m("editorframe.0096")));
        form.add(name);
        form.add(new JLabel(m("editorframe.0126")));
        form.add(target);
        if (JOptionPane.showConfirmDialog(this, form, m("editorframe.0127"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            int order = model().constraints().stream().mapToInt(Constraint::order).max().orElse(-1) + 1;
            var c = new TransformConstraint(EntityId.create(), name.getText().strip(), order, true, List.of(bone.id()),
                    ((Choice) target.getSelectedItem()).id(), false, false, Collections.nCopies(6, 1.),
                    Collections.nCopies(6, 0.));
            execute(m("editorframe.0127"),
                    state -> new EditorState(ConstraintEdits.add(state.model(), c), Selection.of(c.id())));
        });
    }

    private void createPhysicsConstraint() {
        if (!setupOnly())
            return;
        viewportCancel();
        var bone = selectedBone();
        if (bone == null) {
            message(m("editorframe.0128"));
            return;
        }
        var name = new JTextField(
                unique(m("editorframe.0129"), model().constraints().stream().map(Constraint::name).toList()));
        name.setName("constraint.name");
        if (JOptionPane.showConfirmDialog(this, name, m("editorframe.0130"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            var p = new PhysicsConstraint(EntityId.create(), name.getText().strip(),
                    model().constraints().stream().mapToInt(Constraint::order).max().orElse(-1) + 1, true, bone.id(),
                    List.of(0., 0., 1., 0., 0.), 5000, List.of(1., 100., .95, 1., 0., 1., 1.));
            execute(m("editorframe.0130"),
                    state -> new EditorState(ConstraintEdits.add(state.model(), p), Selection.of(p.id())));
        });
    }

    private void physicsProperties(PhysicsConstraint c) {
        title(m("editorframe.0131") + c.name());
        text(m("editorframe.0132"));
        if (animation.animate()) {
            constraintAnimationProperties(c);
            return;
        }
        var id = c.id();
        field("name", m("editorframe.0096"), c.name(),
                v -> edit(m("editorframe.0133"), d -> ConstraintEdits.editPhysics(d, id, p -> p.withName(v))));
        field("constraint.order", m("editorframe.0134"), Integer.toString(c.order()), v -> edit(m("editorframe.0135"),
                d -> ConstraintEdits.editPhysics(d, id, p -> p.withOrder(Integer.parseInt(v.strip())))));
        var enabled = new JCheckBox(m("editorframe.0136"), c.enabled());
        enabled.setName("property.physics.enabled");
        named.put(enabled.getName(), enabled);
        row("", enabled);
        enabled.addActionListener(e -> {
            if (!refreshing)
                guard(() -> edit(m("editorframe.0137"),
                        d -> ConstraintEdits.editPhysics(d, id, p -> p.withEnabled(enabled.isSelected()))));
        });
        var bone = boneChoices(false, c.boneId());
        bone.setName("property.physics.bone");
        named.put(bone.getName(), bone);
        row(m("editorframe.0138"), bone);
        EntityId[] attempted = { c.boneId() };
        Runnable applyBone = () -> {
            var value = ((Choice) bone.getSelectedItem()).id();
            if (refreshing || Objects.equals(attempted[0], value))
                return;
            attempted[0] = value;
            guard(() -> edit(m("editorframe.0138"), d -> ConstraintEdits.editPhysics(d, id, p -> p.withBone(value))));
        };
        bone.addActionListener(e -> {
            if (!bone.isPopupVisible())
                applyBone.run();
        });
        bone.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(applyBone);
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
                choose(bone, c.boneId());
            }
        });
        String[] effects = { m("editorframe.0139"), m("editorframe.0140"), m("editorframe.0141"), m("editorframe.0142"),
                m("editorframe.0143") },
                parameters = { m("constraintchannel.0009"), m("constraintchannel.0010"), m("constraintchannel.0011"),
                        m("constraintchannel.0012"), m("constraintchannel.0013"), m("constraintchannel.0014"), "Mix" };
        for (int n = 0; n < 5; n++) {
            int component = n;
            field("physics.influence." + n, effects[n], number(c.influence().get(n)), v -> edit(m("editorframe.0144"),
                    d -> ConstraintEdits.editPhysics(d, id, p -> p.withInfluence(component, parse(v)))));
        }
        field("physics.limit", m("editorframe.0145"), number(c.limit()),
                v -> edit(m("editorframe.0146"), d -> ConstraintEdits.editPhysics(d, id, p -> p.withLimit(parse(v)))));
        for (int n = 0; n < 7; n++) {
            int component = n;
            field("physics.parameter." + n, parameters[n], number(c.parameters().get(n)),
                    v -> edit(m("editorframe.0147"),
                            d -> ConstraintEdits.editPhysics(d, id, p -> p.withParameter(component, parse(v)))));
        }
        propertyButton(m("editorframe.0148"), () -> {
            history().select(Selection.of(c.boneId()));
            changed();
        });
        propertyButton(m("editorframe.0043"), this::deleteSelected);
    }

    private void createPathAttachment() {
        if (!setupOnly())
            return;
        viewportCancel();
        var bone = selectedBone();
        if (bone == null) {
            message(m("editorframe.0149"));
            return;
        }
        var name = new JTextField(m("editorframe.0080"));
        name.setName("path.name");
        var rows = clipRows(List.of(new Vec2(-100, 0), new Vec2(-60, 0), new Vec2(-20, 80), new Vec2(60, 80),
                new Vec2(100, 0), new Vec2(140, 0)));
        rows.setName("path.vertices");
        var form = new JPanel(new BorderLayout(6, 6));
        form.add(name, BorderLayout.NORTH);
        form.add(new JScrollPane(rows));
        form.add(new JLabel(m("editorframe.0150")), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, form, m("editorframe.0151"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            var points = readClipRows(rows.getText());
            var slot = new Slot(EntityId.create(),
                    unique(m("editorframe.0152"), model().slots().stream().map(Slot::name).toList()), bone.id(), null);
            var path = new PathAttachment(EntityId.create(), name.getText().strip(), slot.id(), points, false, true,
                    Collections.nCopies(points.size() / 3, 200.));
            execute(m("editorframe.0151"),
                    state -> new EditorState(
                            ModelEdits.addAttachment(ModelEdits.addSlot(state.model(), slot), path, true),
                            Selection.of(path.id())));
        });
    }

    private void pathProperties(PathAttachment c) {
        title(m("editorframe.0153"));
        var id = c.id();
        field("name", m("editorframe.0096"), c.name(),
                v -> edit(m("editorframe.0154"), d -> PathEdits.edit(d, id, p -> p.withName(v))));
        pathCheck("closed", m("editorframe.0155"), c.closed(),
                v -> edit(m("editorframe.0156"), d -> PathEdits.edit(d, id, p -> p.withOptions(v, p.constantSpeed()))));
        pathCheck("constantSpeed", m("editorframe.0157"), c.constantSpeed(),
                v -> edit(m("editorframe.0158"), d -> PathEdits.edit(d, id, p -> p.withOptions(p.closed(), v))));
        propertyButton(viewport.meshEditing() ? m("editorframe.0159") : m("editorframe.0160"),
                () -> viewport.meshEditing(!viewport.meshEditing()));
        int index = Math.max(0, Math.min(c.vertices().size() - 1, viewport.meshIndex()));
        var p = c.vertices().get(index);
        field("path.index", m("editorframe.0161"), Integer.toString(index), v -> {
            int n = Integer.parseInt(v.strip());
            if (n < 0 || n >= c.vertices().size())
                throw new IllegalArgumentException(m("editorframe.0162"));
            viewport.meshIndex(n);
        });
        field("path.x", m("editorframe.0163"), number(p.x()),
                v -> edit(m("editorframe.0164"), d -> PathEdits.vertex(d, id, index, new Vec2(parse(v), p.y()))));
        field("path.y", m("editorframe.0165"), number(p.y()),
                v -> edit(m("editorframe.0166"), d -> PathEdits.vertex(d, id, index, new Vec2(p.x(), parse(v)))));
        field("path.lengths", m("editorframe.0167"),
                c.lengths().stream().map(EditorFrame::number).collect(java.util.stream.Collectors.joining(" ")),
                v -> edit(m("editorframe.0168"), d -> PathEdits.edit(d, id, a -> a.withLengths(readLengths(v)))));
        propertyButton(m("editorframe.0169"), () -> {
            var rows = clipRows(c.vertices());
            rows.setName("path.vertices");
            var lengths = new JTextField(
                    c.lengths().stream().map(EditorFrame::number).collect(java.util.stream.Collectors.joining(" ")));
            lengths.setName("path.lengths");
            var body = new JPanel(new BorderLayout(6, 6));
            body.add(new JScrollPane(rows));
            body.add(lengths, BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, body, m("editorframe.0170"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                return;
            guard(() -> edit(m("editorframe.0171"),
                    d -> PathEdits.edit(d, id,
                            a -> new PathAttachment(a.id(), a.name(), a.slotId(), readClipRows(rows.getText()),
                                    a.closed(), a.constantSpeed(), readLengths(lengths.getText())))));
        });
        text(m("editorframe.0172"));
    }

    private static List<Double> readLengths(String text) {
        return Arrays.stream(text.strip().split("[\\s,]+")).map(EditorFrame::parse).toList();
    }

    private void createPathConstraint() {
        if (!setupOnly())
            return;
        viewportCancel();
        var bone = selectedBone();
        var target = new JComboBox<Choice>();
        for (var s : model().slots())
            if (model().attachments().stream()
                    .anyMatch(a -> a.id().equals(s.activeAttachmentId()) && a instanceof PathAttachment))
                target.addItem(new Choice(s.id(), s.name()));
        if (bone == null || target.getItemCount() == 0) {
            message(m("editorframe.0173"));
            return;
        }
        var name = new JTextField(
                unique(m("editorframe.0174"), model().constraints().stream().map(Constraint::name).toList()));
        name.setName("constraint.name");
        target.setName("constraint.target");
        var form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel(m("editorframe.0096")));
        form.add(name);
        form.add(new JLabel(m("editorframe.0175")));
        form.add(target);
        if (JOptionPane.showConfirmDialog(this, form, m("editorframe.0176"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            var c = new PathConstraint(EntityId.create(), name.getText().strip(),
                    model().constraints().stream().mapToInt(Constraint::order).max().orElse(-1) + 1, true,
                    List.of(bone.id()), ((Choice) target.getSelectedItem()).id(), PathConstraint.PositionMode.PERCENT,
                    PathConstraint.SpacingMode.FIXED, PathConstraint.RotateMode.TANGENT, 0, 30, 0, 1, 1, 1);
            execute(m("editorframe.0176"),
                    state -> new EditorState(ConstraintEdits.add(state.model(), c), Selection.of(c.id())));
        });
    }

    private void pathConstraintProperties(PathConstraint c) {
        title(m("editorframe.0177") + c.name());
        if (animation.animate()) {
            constraintAnimationProperties(c);
            return;
        }
        var id = c.id();
        field("name", m("editorframe.0096"), c.name(),
                v -> edit(m("editorframe.0178"), d -> ConstraintEdits.editPath(d, id, p -> p.withName(v))));
        field("constraint.order", m("editorframe.0134"), Integer.toString(c.order()), v -> edit(m("editorframe.0179"),
                d -> ConstraintEdits.editPath(d, id, p -> p.withOrder(Integer.parseInt(v.strip())))));
        pathCheck("enabled", m("editorframe.0136"), c.enabled(),
                v -> edit(m("editorframe.0180"), d -> ConstraintEdits.editPath(d, id, p -> p.withEnabled(v))));
        var choices = model().slots().stream().map(s -> new Choice(s.id(), s.name())).toArray(Choice[]::new);
        var current = Arrays.stream(choices).filter(v -> v.id().equals(c.targetId())).findFirst().orElseThrow();
        pathChoice("target", m("editorframe.0175"), current, choices,
                v -> edit(m("editorframe.0181"), d -> ConstraintEdits.editPath(d, id, p -> p.withTarget(v.id()))));
        pathChoice("positionMode", m("editorframe.0182"), c.positionMode(), PathConstraint.PositionMode.values(),
                v -> edit(m("editorframe.0183"),
                        d -> ConstraintEdits.editPath(d, id, p -> p.withModes(v, p.spacingMode(), p.rotateMode()))));
        pathChoice("spacingMode", m("editorframe.0184"), c.spacingMode(), PathConstraint.SpacingMode.values(),
                v -> edit(m("editorframe.0185"),
                        d -> ConstraintEdits.editPath(d, id, p -> p.withModes(p.positionMode(), v, p.rotateMode()))));
        pathChoice("rotateMode", m("editorframe.0186"), c.rotateMode(), PathConstraint.RotateMode.values(),
                v -> edit(m("editorframe.0187"),
                        d -> ConstraintEdits.editPath(d, id, p -> p.withModes(p.positionMode(), p.spacingMode(), v))));
        String[] labels = { m("editorframe.0188"), m("editorframe.0189"), m("editorframe.0190"),
                m("constraintchannel.0004"), "Mix X", "Mix Y" };
        double[] values = { c.position(), c.spacing(), c.rotation(), c.mixRotate(), c.mixX(), c.mixY() };
        for (int n = 0; n < 6; n++) {
            int component = n;
            field("path.value." + n, labels[n], number(values[n]), v -> edit(m("editorframe.0191"),
                    d -> ConstraintEdits.editPath(d, id, p -> p.withValue(component, parse(v)))));
        }
        propertyButton(m("editorframe.0192"), () -> {
            var list = new JList<Choice>(
                    model().bones().stream().map(b -> new Choice(b.id(), b.name())).toArray(Choice[]::new));
            list.setName("constraint.bones");
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            for (int n = 0; n < list.getModel().getSize(); n++)
                if (c.boneIds().contains(list.getModel().getElementAt(n).id()))
                    list.addSelectionInterval(n, n);
            if (JOptionPane.showConfirmDialog(this, new JScrollPane(list), m("editorframe.0193"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
                return;
            guard(() -> edit(m("editorframe.0194"), d -> ConstraintEdits.editPath(d, id,
                    p -> p.withBones(list.getSelectedValuesList().stream().map(Choice::id).toList()))));
        });
        propertyButton(m("editorframe.0195"), () -> {
            history().select(Selection.of(c.targetId()));
            changed();
        });
        propertyButton(m("editorframe.0043"), this::deleteSelected);
    }

    private void pathCheck(String key, String label, boolean value, Consumer<Boolean> update) {
        var box = new JCheckBox(label, value);
        box.setName("property.path." + key);
        named.put(box.getName(), box);
        row("", box);
        box.addActionListener(e -> {
            if (!refreshing)
                guard(() -> update.accept(box.isSelected()));
        });
    }

    private <T> void pathChoice(String key, String label, T value, T[] values, Consumer<T> update) {
        var box = new JComboBox<T>(values);
        box.setSelectedItem(value);
        box.setName("property.path." + key);
        named.put(box.getName(), box);
        row(label, box);
        Object[] attempted = { value };
        Runnable apply = () -> {
            var selected = box.getSelectedItem();
            if (refreshing || Objects.equals(attempted[0], selected))
                return;
            attempted[0] = selected;
            guard(() -> update.accept(box.getItemAt(box.getSelectedIndex())));
        };
        box.addActionListener(e -> {
            if (!box.isPopupVisible())
                apply.run();
        });
        box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(apply);
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                box.setSelectedItem(value);
            }
        });
    }

    private void transformConstraintProperties(TransformConstraint c) {
        title(m("editorframe.0196") + c.name());
        if (animation.animate()) {
            constraintAnimationProperties(c);
            return;
        }
        var id = c.id();
        field("name", m("editorframe.0096"), c.name(),
                v -> edit(m("editorframe.0197"), d -> ConstraintEdits.editTransform(d, id, t -> t.withName(v))));
        field("constraint.order", m("editorframe.0134"), Integer.toString(c.order()), v -> edit(m("editorframe.0198"),
                d -> ConstraintEdits.editTransform(d, id, t -> t.withOrder(Integer.parseInt(v.strip())))));
        transformCheck("enabled", m("editorframe.0136"), c.enabled(), v -> c.withEnabled(v), id);
        transformCheck("local", m("editorframe.0199"), c.local(), v -> c.withMode(v, c.relative()), id);
        transformCheck("relative", m("editorframe.0200"), c.relative(), v -> c.withMode(c.local(), v), id);
        transformBone("target", m("editorframe.0126"), c.targetId(), c::withTarget, id);
        transformBone("bone", m("editorframe.0201"), c.boneIds().get(0), v -> {
            var ids = new ArrayList<>(c.boneIds());
            ids.set(0, v);
            return c.withBones(ids);
        }, id);
        propertyButton(m("editorframe.0202"), () -> transformBones(c));
        String[] labels = { m("channel.0002"), "X", "Y", m("editorframe.0203"), m("editorframe.0204"), "Shear Y" };
        for (int i = 0; i < 6; i++) {
            int n = i;
            field("constraint.mix." + n, "Mix · " + labels[n], number(c.mix().get(n)), v -> edit(m("editorframe.0205"),
                    d -> ConstraintEdits.editTransform(d, id, t -> t.withMix(n, parse(v)))));
        }
        for (int i = 0; i < 6; i++) {
            int n = i;
            field("constraint.offset." + n, "Offset · " + labels[n], number(c.offset().get(n)),
                    v -> edit(m("editorframe.0206"),
                            d -> ConstraintEdits.editTransform(d, id, t -> t.withOffset(n, parse(v)))));
        }
        propertyButton(m("editorframe.0207"), () -> {
            history().select(Selection.of(c.targetId()));
            changed();
        });
        text(m("editorframe.0208"));
        propertyButton(m("editorframe.0043"), this::deleteSelected);
    }

    private void transformCheck(String key, String label, boolean value, Function<Boolean, TransformConstraint> update,
            EntityId id) {
        var box = new JCheckBox(label, value);
        box.setName("property.constraint." + key);
        named.put(box.getName(), box);
        row("", box);
        box.addActionListener(e -> {
            if (!refreshing)
                guard(() -> edit(m("editorframe.0209") + label,
                        d -> ConstraintEdits.editTransform(d, id, t -> update.apply(box.isSelected()))));
        });
    }

    private void transformBone(String key, String label, EntityId value, Function<EntityId, TransformConstraint> update,
            EntityId id) {
        var box = boneChoices(false, value);
        box.setName("property.constraint." + key);
        named.put(box.getName(), box);
        row(label, box);
        EntityId[] attempted = { value };
        Runnable apply = () -> {
            var selected = ((Choice) box.getSelectedItem()).id();
            if (refreshing || Objects.equals(attempted[0], selected))
                return;
            attempted[0] = selected;
            if (Objects.equals(value, selected))
                return;
            guard(() -> edit(m("editorframe.0209") + label,
                    d -> ConstraintEdits.editTransform(d, id, t -> update.apply(selected))));
        };
        box.addActionListener(e -> {
            if (!box.isPopupVisible())
                apply.run();
        });
        box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(apply);
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                choose(box, value);
            }
        });
    }

    private void transformBones(TransformConstraint c) {
        var list = new JList<Choice>(
                model().bones().stream().map(b -> new Choice(b.id(), b.name())).toArray(Choice[]::new));
        list.setName("constraint.bones");
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(10);
        for (int i = 0; i < list.getModel().getSize(); i++)
            if (c.boneIds().contains(list.getModel().getElementAt(i).id()))
                list.addSelectionInterval(i, i);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(list), m("editorframe.0210"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            var ids = list.getSelectedValuesList().stream().map(Choice::id).toList();
            edit(m("editorframe.0211"), d -> ConstraintEdits.editTransform(d, c.id(), t -> t.withBones(ids)));
        });
    }

    private void createTwoIk() {
        createIk(true);
    }

    private void createIk(boolean two) {
        if (!setupOnly())
            return;
        viewportCancel();
        var selected = selectedBone();
        if (selected == null || model().bones().size() < 2) {
            message(m("editorframe.0212"));
            return;
        }
        JTextField name = new JTextField(unique("IK", model().constraints().stream().map(Constraint::name).toList()));
        name.setName("constraint.name");
        var target = boneChoices(false, null);
        target.setName("constraint.target");
        var child = boneChoices(false, model().bones().stream().filter(b -> Objects.equals(b.parentId(), selected.id()))
                .map(Bone::id).findFirst().orElse(null));
        child.setName("constraint.child");
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel(m("editorframe.0096")));
        form.add(name);
        form.add(new JLabel(m("editorframe.0126")));
        form.add(target);
        if (two) {
            form.add(new JLabel(m("editorframe.0213")));
            form.add(child);
        }
        if (JOptionPane.showConfirmDialog(this, form, two ? m("editorframe.0214") : m("editorframe.0215"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            int order = model().constraints().stream().mapToInt(Constraint::order).max().orElse(-1) + 1;
            var c = new IkConstraint(EntityId.create(), name.getText().strip(), order, true,
                    two ? List.of(selected.id(), ((Choice) child.getSelectedItem()).id()) : List.of(selected.id()),
                    ((Choice) target.getSelectedItem()).id(), 1, 0, true, false, false, false);
            execute(m("editorframe.0216"),
                    s -> new EditorState(ConstraintEdits.add(s.model(), c), Selection.of(c.id())));
        });
    }

    private void constraintProperties(IkConstraint c) {
        title((c.boneIds().size() == 2 ? m("editorframe.0217") : m("editorframe.0218")) + c.name());
        if (animation.animate()) {
            constraintAnimationProperties(c);
            return;
        }
        var id = c.id();
        field("name", m("editorframe.0096"), c.name(),
                v -> edit(m("editorframe.0197"), d -> ConstraintEdits.editIk(d, id, a -> a.withName(v))));
        field("constraint.order", m("editorframe.0134"), Integer.toString(c.order()), v -> edit(m("editorframe.0198"),
                d -> ConstraintEdits.editIk(d, id, a -> a.withOrder(Integer.parseInt(v.strip())))));
        constraintCheck("enabled", m("editorframe.0136"), c.enabled(), v -> c.withEnabled(v), id);
        constraintBone("target", m("editorframe.0126"), c.targetId(), v -> c.withTarget(v), id);
        constraintBone("bone", m("editorframe.0219"), c.boneIds().get(0),
                v -> c.withBones(c.boneIds().size() == 1 ? List.of(v) : List.of(v, c.boneIds().get(1))), id);
        if (c.boneIds().size() == 2) {
            constraintBone("child", m("editorframe.0213"), c.boneIds().get(1),
                    v -> c.withBones(List.of(c.boneIds().get(0), v)), id);
            field("constraint.softness", "Softness", number(c.softness()),
                    v -> edit("IK softness", d -> ConstraintEdits.editIk(d, id, a -> a.withSoftness(parse(v)))));
            constraintCheck("bendPositive", m("constraintchannel.0001"), c.bendPositive(),
                    v -> c.withOptions(v, c.compress(), c.stretch(), c.uniform()), id);
        }
        field("constraint.mix", "Mix 0–1", number(c.mix()),
                v -> edit("IK mix", d -> ConstraintEdits.editIk(d, id, a -> a.withMix(parse(v)))));
        if (c.boneIds().size() == 1)
            constraintCheck("compress", m("constraintchannel.0002"), c.compress(),
                    v -> c.withOptions(c.bendPositive(), v, c.stretch(), c.uniform()), id);
        constraintCheck("stretch", m("constraintchannel.0003"), c.stretch(),
                v -> c.withOptions(c.bendPositive(), c.compress(), v, c.uniform()), id);
        constraintCheck("uniform", m("editorframe.0220"), c.uniform(),
                v -> c.withOptions(c.bendPositive(), c.compress(), c.stretch(), v), id);
        propertyButton(m("editorframe.0207"), () -> {
            history().select(Selection.of(c.targetId()));
            changed();
        });
        text(m("editorframe.0221"));
        propertyButton(m("editorframe.0043"), this::deleteSelected);
    }

    private void constraintCheck(String key, String label, boolean value, Function<Boolean, IkConstraint> update,
            EntityId id) {
        var box = new JCheckBox(label, value);
        box.setName("property.constraint." + key);
        named.put(box.getName(), box);
        row("", box);
        box.addActionListener(e -> {
            if (!refreshing)
                guard(() -> edit(m("editorframe.0209") + label,
                        d -> ConstraintEdits.editIk(d, id, c -> update.apply(box.isSelected()))));
        });
    }

    private void constraintBone(String key, String label, EntityId value, Function<EntityId, IkConstraint> update,
            EntityId id) {
        var box = boneChoices(false, value);
        box.setName("property.constraint." + key);
        named.put(box.getName(), box);
        row(label, box);
        EntityId[] attempted = { value };
        Runnable apply = () -> {
            var selected = ((Choice) box.getSelectedItem()).id();
            if (refreshing || Objects.equals(selected, attempted[0]))
                return;
            attempted[0] = selected;
            if (Objects.equals(selected, value))
                return;
            guard(() -> edit(m("editorframe.0209") + label,
                    d -> ConstraintEdits.editIk(d, id, c -> update.apply(selected))));
        };
        box.addActionListener(e -> {
            if (!box.isPopupVisible())
                apply.run();
        });
        box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(apply);
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                choose(box, value);
            }
        });
    }

    private void clippingProperties(ClippingAttachment c) {
        EntityId id = c.id();
        title(m("editorframe.0222"));
        field("name", m("editorframe.0096"), c.name(), value -> edit(m("editorframe.0223"), d -> ClippingEdits.edit(d,
                id, a -> new ClippingAttachment(a.id(), value, a.slotId(), a.vertices(), a.endSlotId()))));
        text(m("editorframe.0224"));
        var ends = new JComboBox<Choice>();
        ends.addItem(new Choice(null, m("editorframe.0225")));
        int start = model().slots().indexOf(slot(c.slotId()));
        for (int i = start + 1; i < model().slots().size(); i++) {
            var s = model().slots().get(i);
            var active = model().attachments().stream().filter(a -> a.id().equals(s.activeAttachmentId())).findFirst()
                    .orElse(null);
            if (!(active instanceof ClippingAttachment))
                ends.addItem(new Choice(s.id(), s.name()));
        }
        choose(ends, c.endSlotId());
        ends.setName("property.clip.end");
        named.put(ends.getName(), ends);
        row(m("editorframe.0226"), ends);
        // Popup keyboard navigation must not rebuild the property panel on every arrow.
        // Commit once after the popup closes; Esc restores its original choice.
        Runnable applyEnd = () -> {
            if (refreshing)
                return;
            var end = ((Choice) ends.getSelectedItem()).id();
            if (Objects.equals(end, c.endSlotId()))
                return;
            boolean focused = ends.isFocusOwner();
            guard(() -> edit(m("editorframe.0227"), d -> ClippingEdits.edit(d, id,
                    a -> new ClippingAttachment(a.id(), a.name(), a.slotId(), a.vertices(), end))));
            if (focused && named.containsKey("property.clip.end"))
                named.get("property.clip.end").requestFocusInWindow();
        };
        ends.addActionListener(e -> {
            if (!ends.isPopupVisible())
                applyEnd.run();
        });
        ends.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(applyEnd);
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                choose(ends, c.endSlotId());
            }
        });
        propertyButton(viewport.meshEditing() ? m("editorframe.0159") : m("editorframe.0160"),
                () -> viewport.meshEditing(!viewport.meshEditing()));
        int index = Math.max(0, Math.min(c.vertices().size() - 1, viewport.meshIndex()));
        var p = c.vertices().get(index);
        field("clip.index", m("editorframe.0228"), Integer.toString(index), value -> {
            int n = Integer.parseInt(value.strip());
            if (n < 0 || n >= c.vertices().size())
                throw new IllegalArgumentException(m("editorframe.0229"));
            viewport.meshIndex(n);
        });
        field("clip.x", m("editorframe.0163"), number(p.x()), value -> edit(m("editorframe.0230"),
                d -> ClippingEdits.vertex(d, id, index, new Vec2(parse(value), p.y()))));
        field("clip.y", m("editorframe.0165"), number(p.y()), value -> edit(m("editorframe.0231"),
                d -> ClippingEdits.vertex(d, id, index, new Vec2(p.x(), parse(value)))));
        propertyButton(m("editorframe.0232"), () -> clipPolygonDialog(c));
        propertyButton(m("editorframe.0233"),
                () -> edit(m("editorframe.0234"), d -> ClippingEdits.remove(d, id, index)));
        text(m("editorframe.0235"));
    }

    private static JTextArea clipRows(List<Vec2> vertices) {
        StringBuilder s = new StringBuilder();
        for (var v : vertices)
            s.append(v.x()).append(' ').append(v.y()).append('\n');
        var rows = new JTextArea(s.toString(), 10, 30);
        rows.setName("clip.vertices");
        return rows;
    }

    private static List<Vec2> readClipRows(String text) {
        List<Vec2> result = new ArrayList<>();
        for (String row : text.strip().split("\\R")) {
            var pair = row.strip().split("[\\s,]+");
            if (pair.length != 2)
                throw new IllegalArgumentException(m("editorframe.0236"));
            result.add(new Vec2(parse(pair[0]), parse(pair[1])));
        }
        return result;
    }

    private void clipPolygonDialog(ClippingAttachment c) {
        var rows = clipRows(c.vertices());
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(rows), m("editorframe.0237"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            var vertices = readClipRows(rows.getText());
            edit(m("editorframe.0238"), d -> ClippingEdits.edit(d, c.id(), a -> a.withVertices(vertices)));
        });
    }

    private void createClipping() {
        if (!setupOnly())
            return;
        viewportCancel();
        var bone = selectedBone();
        if (bone == null) {
            message(m("editorframe.0239"));
            return;
        }
        Slot target = null;
        EntityId selected = selected();
        for (var s : model().slots())
            if (s.id().equals(selected))
                target = s;
        for (var a : model().attachments())
            if (a.id().equals(selected))
                target = slot(a.slotId());
        int index = target == null ? model().slots().size() : model().slots().indexOf(target);
        EntityId end = target == null ? null : target.id();
        JTextField name = new JTextField(m("editorframe.0081"));
        name.setName("clip.name");
        var rows = clipRows(
                List.of(new Vec2(-50, -50), new Vec2(-50, 50), new Vec2(0, 0), new Vec2(50, 50), new Vec2(50, -50)));
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.add(name, BorderLayout.NORTH);
        body.add(new JScrollPane(rows));
        body.add(new JLabel(m("editorframe.0240")), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, body, m("editorframe.0241"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        int insertion = index;
        EntityId finalEnd = end;
        guard(() -> {
            var slot = new Slot(EntityId.create(),
                    unique(m("editorframe.0242"), model().slots().stream().map(Slot::name).toList()), bone.id(), null);
            var clip = new ClippingAttachment(EntityId.create(), name.getText().strip(), slot.id(),
                    readClipRows(rows.getText()), finalEnd);
            execute(m("editorframe.0241"),
                    state -> new EditorState(ClippingEdits.add(state.model(), slot, clip, insertion),
                            Selection.of(clip.id())));
        });
    }

    private void meshProperties(MeshAttachment m) {
        EntityId id = m.id();
        title(m("editorframe.0243"));
        field("name", m("editorframe.0096"), m.name(),
                value -> edit(m("editorframe.0244"), d -> MeshEdits.replace(d, id, a -> new MeshAttachment(m.id(),
                        value, m.slotId(), m.source(), m.vertices(), m.uvs(), m.hull(), m.triangles(), m.weights()))));
        text(m("editorframe.0245") + m.hull() + m("editorframe.0246") + (m.vertices().size() - m.hull())
                + m("editorframe.0247") + m.triangles().size() / 3);
        propertyButton(viewport.meshEditing() ? m("editorframe.0159") : m("editorframe.0160"),
                () -> viewport.meshEditing(!viewport.meshEditing()));
        int index = Math.max(0, Math.min(m.vertices().size() - 1, viewport.meshIndex()));
        field("mesh.index", m("editorframe.0228"), Integer.toString(index), value -> {
            int n = Integer.parseInt(value.trim());
            if (n < 0 || n >= m.vertices().size())
                throw new IllegalArgumentException(m("editorframe.0229"));
            viewport.meshIndex(n);
        });
        var p = m.vertices().get(index);
        var uv = m.uvs().get(index);
        if (!m.weighted()) {
            field("mesh.x", m("editorframe.0163"), number(p.x()), value -> edit(m("editorframe.0248"),
                    d -> MeshEdits.vertex(d, id, index, new Vec2(parse(value), p.y()), uv)));
            field("mesh.y", m("editorframe.0165"), number(p.y()), value -> edit(m("editorframe.0249"),
                    d -> MeshEdits.vertex(d, id, index, new Vec2(p.x(), parse(value)), uv)));
        } else
            text(m("editorframe.0250") + m.weights().get(index).size() + m("editorframe.0251"));
        field("mesh.u", m("editorframe.0252"), number(uv.x()), value -> edit(m("editorframe.0253"),
                d -> MeshEdits.vertex(d, id, index, p, new Vec2(parse(value), uv.y()))));
        field("mesh.v", m("editorframe.0254"), number(uv.y()), value -> edit(m("editorframe.0253"),
                d -> MeshEdits.vertex(d, id, index, p, new Vec2(uv.x(), parse(value)))));
        propertyButton(m("editorframe.0255"), () -> edit(m("editorframe.0256"), d -> MeshEdits.remove(d, id, index)));
        propertyButton(m("editorframe.0257"), () -> meshGeometry(m));
        propertyButton(m("editorframe.0258"), () -> edit(m("editorframe.0259"), d -> MeshEdits.geometry(d, id,
                m.vertices(), m.uvs(), m.hull(), MeshTopology.triangulate(m.vertices(), m.hull()))));
        propertyButton(m("editorframe.0121"), () -> relocateMesh(m));
        propertyButton(m("editorframe.0260"), () -> weightDialog(m, index, true));
        if (m.weighted()) {
            propertyButton(m("editorframe.0261"), () -> weightDialog(m, index, false));
            propertyButton(m("editorframe.0262"), () -> edit(m("editorframe.0263"), d -> WeightEdits.unbind(d, id)));
        }
        text(m("editorframe.0264"));
    }

    private void weightDialog(MeshAttachment m, int vertex, boolean bindAll) {
        JPanel panel = new JPanel(new GridLayout(0, 4, 6, 6));
        for (String label : List.of(m("editorframe.0265"), m("editorframe.0266"), m("editorframe.0163"),
                m("editorframe.0165")))
            panel.add(new JLabel(label));
        List<JComboBox<Choice>> bones = new ArrayList<>();
        List<JTextField[]> values = new ArrayList<>();
        var old = m.weighted() ? m.weights().get(vertex) : List.<Influence>of();
        var owner = slot(m.slotId()).boneId();
        for (int row = 0; row < 4; row++) {
            Influence i = row < old.size() ? old.get(row) : null;
            var choice = boneChoices(true, i != null ? i.boneId() : row == 0 ? owner : null);
            choice.setName("weight.bone." + row);
            bones.add(choice);
            panel.add(choice);
            String[] initial = { i == null ? row == 0 ? "1" : "0" : number(i.weight()),
                    i == null ? "0" : number(i.point().x()), i == null ? "0" : number(i.point().y()) };
            JTextField[] fields = new JTextField[3];
            for (int c = 0; c < 3; c++) {
                var f = new JTextField(initial[c], 9);
                f.setName("weight." + row + "." + c);
                f.setEnabled(c == 0 || !bindAll);
                fields[c] = f;
                panel.add(f);
            }
            values.add(fields);
        }
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.add(new JLabel(bindAll ? m("editorframe.0267") : m("editorframe.0268")), BorderLayout.NORTH);
        body.add(panel);
        if (JOptionPane.showConfirmDialog(this, body, bindAll ? m("editorframe.0269") : m("editorframe.0270"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            List<WeightEdits.Binding> bindings = new ArrayList<>();
            List<Influence> influences = new ArrayList<>();
            for (int row = 0; row < 4; row++) {
                var bone = ((Choice) bones.get(row).getSelectedItem()).id();
                if (bone == null)
                    continue;
                var fields = values.get(row);
                double weight = parse(fields[0].getText());
                bindings.add(new WeightEdits.Binding(bone, weight));
                if (!bindAll)
                    influences.add(new Influence(bone, new Vec2(parse(fields[1].getText()), parse(fields[2].getText())),
                            weight));
            }
            edit(bindAll ? m("editorframe.0269") : m("editorframe.0271"),
                    d -> bindAll ? WeightEdits.bind(d, m.id(), bindings)
                            : WeightEdits.vertex(d, m.id(), vertex, influences));
        });
    }

    private void meshAnimationProperties(MeshAttachment m) {
        title(m("editorframe.0272"));
        text(m.weighted() ? m("editorframe.0273") : m("editorframe.0274"));
        propertyButton(viewport.meshEditing() ? m("editorframe.0159") : m("editorframe.0160"),
                () -> viewport.meshEditing(!viewport.meshEditing()));
        int index = Math.max(0, Math.min(m.vertices().size() - 1, viewport.meshIndex()));
        field("mesh.index", m("editorframe.0228"), Integer.toString(index), value -> {
            int v = Integer.parseInt(value.trim());
            if (v < 0 || v >= m.vertices().size())
                throw new IllegalArgumentException(m("editorframe.0229"));
            viewport.meshIndex(v);
        });
        propertyButton(m("editorframe.0275"), () -> deformDialog(m));
        text(m("editorframe.0276"));
    }

    private void deformDialog(MeshAttachment m) {
        if (!animation.editing())
            return;
        var offsets = model().animations().isEmpty() ? Collections.nCopies(m.deformSize(), 0.0)
                : DeformEdits.offsets(model(), animation.active(), m.id(), animation.time());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < offsets.size(); i += 2)
            text.append(offsets.get(i)).append(' ').append(offsets.get(i + 1)).append('\n');
        JTextArea values = new JTextArea(text.toString(), 12, 36);
        values.setName("deform.offsets");
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.add(new JLabel(m("editorframe.0277") + animation.time() + m("editorframe.0278") + offsets.size() / 2
                + m("editorframe.0279")), BorderLayout.NORTH);
        body.add(new JScrollPane(values));
        if (JOptionPane.showConfirmDialog(this, body, m("editorframe.0280"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            List<Double> parsed = new ArrayList<>();
            for (String row : values.getText().strip().split("\\R")) {
                var pair = row.strip().split("[\\s,]+");
                if (pair.length != 2)
                    throw new IllegalArgumentException(m("editorframe.0281"));
                parsed.add(parse(pair[0]));
                parsed.add(parse(pair[1]));
            }
            double time = animation.time();
            edit(m("editorframe.0280"), d -> {
                var next = animation.withActiveDefault(d);
                return DeformEdits.put(next, animation.active(), m.id(), time, parsed);
            });
        });
    }

    private void meshGeometry(MeshAttachment m) {
        JTextArea rows = new JTextArea(10, 38), triangles = new JTextArea(3, 38);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < m.vertices().size(); i++) {
            var p = m.vertices().get(i);
            var uv = m.uvs().get(i);
            text.append(p.x()).append(' ').append(p.y()).append(' ').append(uv.x()).append(' ').append(uv.y())
                    .append('\n');
        }
        rows.setText(text.toString());
        rows.setName("mesh.geometry.rows");
        triangles.setText(m.triangles().toString().replaceAll("[\\[\\],]", " ").trim());
        triangles.setName("mesh.geometry.triangles");
        JTextField hull = new JTextField(Integer.toString(m.hull()));
        hull.setName("mesh.geometry.hull");
        JCheckBox auto = new JCheckBox(m("editorframe.0282"), true);
        auto.setName("mesh.geometry.auto");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel(m("editorframe.0283")));
        panel.add(new JScrollPane(rows));
        panel.add(new JLabel(m("editorframe.0284")));
        panel.add(hull);
        panel.add(auto);
        panel.add(new JLabel(m("editorframe.0285")));
        panel.add(new JScrollPane(triangles));
        if (JOptionPane.showConfirmDialog(this, panel, m("editorframe.0286"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        guard(() -> {
            List<Vec2> points = new ArrayList<>(), uvs = new ArrayList<>();
            for (String line : rows.getText().strip().split("\\R")) {
                String[] v = line.strip().split("[\\s,]+");
                if (v.length != 4)
                    throw new IllegalArgumentException(m("editorframe.0287"));
                points.add(new Vec2(parse(v[0]), parse(v[1])));
                uvs.add(new Vec2(parse(v[2]), parse(v[3])));
            }
            int count = Integer.parseInt(hull.getText().trim());
            List<Integer> indices = new ArrayList<>();
            if (auto.isSelected())
                indices = MeshTopology.triangulate(points, count);
            else
                for (String v : triangles.getText().strip().split("[\\s,]+"))
                    indices.add(Integer.parseInt(v));
            var topology = List.copyOf(indices);
            edit(m("editorframe.0286"), d -> MeshEdits.geometry(d, m.id(), points, uvs, count, topology));
        });
    }

    private void relocateMesh(MeshAttachment m) {
        Path p = choosePng();
        if (p == null)
            return;
        guard(() -> {
            ImageAssets.readPng(p);
            String source = resourceSource(p);
            edit(m("editorframe.0288"), d -> MeshEdits.replace(d, m.id(), a -> MeshEdits.source(a, source)));
            assets.reload();
            changed();
        });
    }

    private void transformFields(EntityId id) {
        Transform t = EditorEdits.local(displayModel(), id);
        if (viewport.space() == Space.WORLD) {
            try {
                t = EditorEdits.world(displayModel(), id).toTrsOrThrow();
            } catch (IllegalArgumentException e) {
                text(m("editorframe.0289"));
                return;
            }
        }
        text(viewport.space() == Space.LOCAL ? m("editorframe.0290") : m("editorframe.0291"));
        Transform initial = t;
        double[] values = { t.x(), t.y(), t.rotation(), t.scaleX(), t.scaleY() };
        String[] keys = { "x", "y", "rotation", "scaleX", "scaleY" },
                labels = { "X", "Y", m("editorframe.0292"), m("editorframe.0203"), m("editorframe.0204") };
        JTextField[] fields = new JTextField[5];
        int revision = propertyRevision;
        Runnable apply = () -> {
            if (refreshing || propertyRevision != revision)
                return;
            guard(() -> {
                Transform value = new Transform(parse(fields[0].getText()), parse(fields[1].getText()),
                        parse(fields[2].getText()), parse(fields[3].getText()), parse(fields[4].getText()));
                if (value.equals(initial))
                    return;
                Set<Channel> channels = EnumSet.noneOf(Channel.class);
                if (value.x() != initial.x() || value.y() != initial.y())
                    channels.add(Channel.TRANSLATE);
                if (value.rotation() != initial.rotation())
                    channels.add(Channel.ROTATE);
                if (value.scaleX() != initial.scaleX() || value.scaleY() != initial.scaleY())
                    channels.add(Channel.SCALE);
                execute(animation.animate() ? m("editorframe.0293") : m("editorframe.0294"),
                        transformEdit(d -> EditorEdits.transform(d, id, value, viewport.space()), channels));
            });
        };
        for (int i = 0; i < 5; i++) {
            var f = new JTextField(number(values[i]));
            fields[i] = f;
            f.setEnabled(!animation.playing());
            f.setName("property." + keys[i]);
            named.put(f.getName(), f);
            row(labels[i], f);
            onCommit(f, revision, apply);
        }
    }

    private void field(String key, String label, String value, Consumer<String> apply) {
        var f = new JTextField(value);
        f.setName("property." + key);
        named.put(f.getName(), f);
        row(label, f);
        int revision = propertyRevision;
        onCommit(f, revision, () -> {
            if (!value.equals(f.getText()))
                guard(() -> apply.accept(f.getText()));
        });
    }

    private void onCommit(JTextField field, int revision, Runnable apply) {
        Runnable commit = () -> {
            if (committingField || propertyRevision != revision)
                return;
            committingField = true;
            fieldFailure = false;
            try {
                apply.run();
            } finally {
                committingField = false;
            }
        };
        field.addActionListener(e -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!refreshing)
                    commit.run();
            }
        });
    }

    private boolean commitFocusedField() {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextField field && field.getName() != null && field.getName().startsWith("property.")) {
            field.postActionEvent();
            return !fieldFailure;
        }
        return true;
    }

    private static String number(double v) {
        return Double.toString(v);
    }

    private static double parse(String v) {
        double n;
        try {
            n = Double.parseDouble(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(m("editorframe.0295"));
        }
        if (!Double.isFinite(n))
            throw new IllegalArgumentException(m("editorframe.0296"));
        return n;
    }

    private void title(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17));
        label.setAlignmentX(0);
        properties.add(label);
        properties.add(Box.createVerticalStrut(12));
    }

    private void text(String value) {
        JTextArea area = new JTextArea(value);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setAlignmentX(0);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
        properties.add(area);
        properties.add(Box.createVerticalStrut(8));
    }

    private void row(String label, JComponent input) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(input);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setPreferredSize(new Dimension(260, 30));
        row.setAlignmentX(0);
        properties.add(row);
        properties.add(Box.createVerticalStrut(6));
    }

    private void propertyButton(String label, Runnable fn) {
        var b = new JButton(action(label, fn));
        b.setName("property.button." + label);
        named.put(b.getName(), b);
        b.setAlignmentX(0);
        properties.add(b);
        properties.add(Box.createVerticalStrut(8));
    }

    private void constraintAnimationProperties(Constraint setup) {
        text(m("editorframe.0297"));
        Constraint sampled = model().animations().isEmpty() ? setup
                : AnimationSampler.constraints(model(), animation.active(), animation.time(), false).stream()
                        .filter(c -> c.id().equals(setup.id())).findFirst().orElseThrow();
        for (var channel : ConstraintChannel.values())
            if (channel.supports(setup)) {
                var values = channel.values(sampled);
                for (int n = 0; n < channel.components(); n++) {
                    if (setup instanceof IkConstraint ik
                            && (n == 1 && ik.boneIds().size() == 1 || n == 3 && ik.boneIds().size() == 2))
                        continue;
                    int component = n;
                    String key = "constraint.key." + channel.name() + "." + n;
                    if (channel.discrete(n)) {
                        var box = new JCheckBox(channel.labels().get(n) + m("editorframe.0298"), values.get(n) == 1);
                        box.setName("property." + key);
                        named.put(box.getName(), box);
                        row("", box);
                        box.addActionListener(e -> {
                            if (!refreshing)
                                guard(() -> recordConstraint(setup.id(), channel, component,
                                        box.isSelected() ? 1. : 0.));
                        });
                    } else
                        field(key, channel.labels().get(n), number(values.get(n)),
                                v -> recordConstraint(setup.id(), channel, component, parse(v)));
                }
                propertyButton(m("editorframe.0299") + channel.name() + m("editorframe.0300"),
                        () -> recordConstraint(setup.id(), channel, -1, 0));
            }
        text(m("editorframe.0301"));
    }

    private void recordConstraint(EntityId id, ConstraintChannel channel, int component, double value) {
        if (!animation.editing())
            return;
        double time = animation.time();
        edit(m("editorframe.0302"), d -> {
            var next = animation.withActiveDefault(d);
            var sampled = AnimationSampler.constraints(next, animation.active(), time, false).stream()
                    .filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            var values = new ArrayList<>(channel.values(sampled));
            if (component >= 0)
                values.set(component, value);
            return AnimationEdits.putConstraint(next, animation.active(), id, channel, time, values);
        });
    }

    private void finishProperties() {
        properties.add(Box.createVerticalGlue());
        properties.revalidate();
        properties.repaint();
    }

    private JComboBox<Choice> boneChoices(boolean root, EntityId selected) {
        var box = new JComboBox<Choice>();
        if (root)
            box.addItem(new Choice(null, m("editorframe.0303")));
        for (var b : model().bones())
            box.addItem(new Choice(b.id(), b.name()));
        choose(box, selected);
        return box;
    }

    private void choose(JComboBox<Choice> box, EntityId id) {
        for (int i = 0; i < box.getItemCount(); i++)
            if (Objects.equals(box.getItemAt(i).id(), id)) {
                box.setSelectedIndex(i);
                return;
            }
    }

    private static String unique(String stem, Collection<String> names) {
        String candidate = stem;
        for (int i = 2; names.contains(candidate); i++)
            candidate = stem + i;
        return candidate;
    }

    @Override
    public void createBone(Vec2 start, Vec2 end) {
        if (!setupOnly())
            return;
        guard(() -> {
            var parent = selectedBone();
            EntityId pid = parent == null ? null : parent.id();
            Bone b = EditorEdits.create(model(), pid, start, end,
                    unique(m("editorframe.0091"), model().bones().stream().map(Bone::name).toList()));
            execute(m("editorframe.0304"),
                    state -> new EditorState(ModelEdits.addBone(state.model(), b), Selection.of(b.id())));
        });
    }

    private void createSlot() {
        if (!setupOnly())
            return;
        viewportCancel();
        Bone b = selectedBone();
        if (b == null) {
            message(m("editorframe.0305"));
            return;
        }
        String name = JOptionPane.showInputDialog(this, m("editorframe.0102"),
                unique(m("editorframe.0062"), model().slots().stream().map(Slot::name).toList()));
        if (name == null)
            return;
        var slot = new Slot(EntityId.create(), name, b.id(), null);
        execute(m("editorframe.0306"),
                state -> new EditorState(ModelEdits.addSlot(state.model(), slot), Selection.of(slot.id())));
    }

    private JFileChooser chooser(String title) {
        var c = new JFileChooser();
        c.setDialogTitle(title);
        document.path().ifPresent(p -> c.setCurrentDirectory(p.getParent().toFile()));
        return c;
    }

    private Path choosePng() {
        var c = chooser(m("editorframe.0307"));
        c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(m("editorframe.0308"), "png"));
        return c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? c.getSelectedFile().toPath().toAbsolutePath().normalize()
                : null;
    }

    private String resourceSource(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private void importImage() {
        if (!setupOnly())
            return;
        viewportCancel();
        Bone bone = selectedBone();
        if (bone == null) {
            message(m("editorframe.0309"));
            return;
        }
        Path path = choosePng();
        if (path == null)
            return;
        guard(() -> {
            var image = ImageAssets.readPng(path);
            Slot target = null;
            EntityId id = selected();
            for (var s : model().slots())
                if (s.id().equals(id))
                    target = s;
            for (var a : model().attachments())
                if (a.id().equals(id))
                    target = slot(a.slotId());
            boolean add = target == null;
            if (add)
                target = new Slot(EntityId.create(),
                        unique(m("editorframe.0062"), model().slots().stream().map(Slot::name).toList()), bone.id(),
                        null);
            Slot s = target;
            String stem = path.getFileName().toString().replaceFirst("(?i)\\.png$", "");
            String name = unique(stem, model().attachments().stream().filter(a -> a.slotId().equals(s.id()))
                    .map(Attachment::name).toList());
            var r = new RegionAttachment(EntityId.create(), name, s.id(), resourceSource(path), Transform.IDENTITY,
                    image.getWidth(), image.getHeight());
            execute(m("editorframe.0310"), state -> {
                var d = add ? ModelEdits.addSlot(state.model(), s) : state.model();
                return new EditorState(ModelEdits.addAttachment(d, r, true), Selection.of(r.id()));
            });
        });
    }

    private void relocate(RegionAttachment r) {
        Path p = choosePng();
        if (p == null)
            return;
        guard(() -> {
            ImageAssets.readPng(p);
            String source = resourceSource(p);
            edit(m("editorframe.0311"), d -> ModelEdits.editRegion(d, r.id(),
                    a -> new RegionAttachment(a.id(), a.name(), a.slotId(), source, a.local(), a.width(), a.height())));
            assets.reload();
            changed();
        });
    }

    private void reparent() {
        if (!setupOnly())
            return;
        viewportCancel();
        EntityId id = selected();
        if (id == null)
            return;
        Bone bone = model().bones().stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
        if (bone == null) {
            message(m("editorframe.0312"));
            return;
        }
        var parents = boneChoices(true, bone.parentId());
        var policy = new JComboBox<>(new String[] { m("editorframe.0313"), m("editorframe.0314") });
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 8));
        panel.add(new JLabel(m("editorframe.0315")));
        panel.add(parents);
        panel.add(policy);
        panel.add(new JLabel(m("editorframe.0316")));
        if (JOptionPane.showConfirmDialog(this, panel, m("editorframe.0317"),
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            var pid = ((Choice) parents.getSelectedItem()).id();
            var mode = policy.getSelectedIndex() == 0 ? ModelEdits.ReparentPolicy.KEEP_LOCAL
                    : ModelEdits.ReparentPolicy.KEEP_WORLD;
            edit(m("editorframe.0317"), d -> ModelEdits.reparent(d, id, pid, mode));
        }
    }

    private void deleteSelected() {
        if (!setupOnly())
            return;
        viewportCancel();
        EntityId id = selected();
        if (id == null)
            return;
        var after = ModelEdits.delete(model(), id);
        String text = m("editorframe.0318") + (model().bones().size() - after.bones().size()) + m("editorframe.0319")
                + (model().slots().size() - after.slots().size()) + m("editorframe.0320")
                + (model().attachments().size() - after.attachments().size()) + m("editorframe.0321")
                + (model().constraints().size() - after.constraints().size()) + m("editorframe.0322");
        if (JOptionPane.showConfirmDialog(this, text, m("editorframe.0323"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION)
            edit(m("editorframe.0012"), d -> ModelEdits.delete(d, id));
    }

    private boolean confirmSave() {
        if (!commitFocusedField())
            return false;
        viewportCancel();
        if (!history().isDirty())
            return true;
        int answer = JOptionPane.showConfirmDialog(this, m("editorframe.0324"), m("editorframe.0325"),
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION)
            return false;
        return answer != JOptionPane.YES_OPTION || save(false);
    }

    private void newProject() {
        if (!confirmSave())
            return;
        document = new ProjectDocument(DocumentSnapshot.EMPTY);
        animation.reset();
        resetAssets();
        viewport.camera().reset();
        changed();
    }

    private void openProject() {
        if (!commitFocusedField())
            return;
        var c = chooser(m("editorframe.0326"));
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        // Validate a replacement fully BEFORE asking to save/discard the current
        // document.
        guard(() -> {
            var next = new ProjectDocument(DocumentSnapshot.EMPTY);
            next.openResolved(c.getSelectedFile().toPath());
            if (!confirmSave())
                return;
            document = next;
            animation.reset();
            resetAssets();
            viewport.camera().reset();
            changed();
        });
    }

    private boolean save(boolean saveAs) {
        if (!commitFocusedField())
            return false;
        viewportCancel();
        Path target = document.path().orElse(null);
        if (saveAs || target == null) {
            var c = chooser(m("editorframe.0327"));
            c.setSelectedFile(new java.io.File(m("editorframe.0328")));
            if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
                return false;
            target = c.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(this, m("editorframe.0329") + target,
                    m("editorframe.0330"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                return false;
        }
        try {
            document.save(target);
            resetAssets();
            changed();
            message(m("editorframe.0331") + target);
            return true;
        } catch (Exception e) {
            error(e);
            return false;
        }
    }

    private void openResolved(Path source) throws Exception {
        document.openResolved(source);
    }

    private void savePortable() {
        if (!commitFocusedField())
            return;
        viewportCancel();
        var c = chooser(m("editorframe.0332"));
        c.setSelectedFile(new java.io.File(m("editorframe.0328")));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        Path target = c.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, m("editorframe.0329") + target,
                m("editorframe.0330"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            return;
        guard(() -> {
            document.savePortable(target);
            resetAssets();
            changed();
            message(m("editorframe.0333") + target);
        });
    }

    private void importSkeleton() {
        if (!commitFocusedField())
            return;
        var c = chooser(m("editorframe.0334"));
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        guard(() -> {
            Path source = c.getSelectedFile().toPath();
            if (source.getFileName().toString().equals("current.json"))
                source = ExportPackage.resolve(source);
            var next = new ProjectDocument(DocumentSnapshot.EMPTY);
            next.importSkeleton(source);
            if (!confirmSave())
                return;
            document = next;
            animation.reset();
            resetAssets();
            viewport.camera().reset();
            changed();
            message(m("editorframe.0335"));
        });
    }

    private void exportSkeleton() {
        if (!commitFocusedField())
            return;
        viewportCancel();
        var c = chooser(m("editorframe.0336"));
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        Path directory = c.getSelectedFile().toPath().toAbsolutePath().normalize();
        guard(() -> {
            Path exported = new ExportPackage().publish(directory, model(),
                    document.path().map(Path::getParent).orElse(Path.of("").toAbsolutePath()));
            message(m("editorframe.0337") + exported);
            JOptionPane.showMessageDialog(this,
                    m("editorframe.0338") + exported + "\nAtlas：" + exported.resolveSibling("skeleton.atlas")
                            + m("editorframe.0339") + directory.resolve("current.json"),
                    m("editorframe.0340"), JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void resetAssets() {
        assets.base(document.path().map(Path::getParent).orElse(Path.of("").toAbsolutePath()));
    }

    private void closeRequested() {
        if (!confirmSave())
            return;
        try {
            Rectangle b = normalBounds == null ? getBounds() : normalBounds;
            WorkspaceLayout.save(UserState.root().resolve("layout.json"),
                    new WorkspaceLayout.State(1, b.x, b.y, b.width, b.height, hierarchySplit.getDividerLocation(),
                            Math.max(285, propertySplit.getWidth() - propertySplit.getDividerLocation()),
                            (getExtendedState() & MAXIMIZED_BOTH) != 0));
        } catch (Exception failed) {
            System.err.println("Layout save: " + failed);
        }
        recoveryTimer.stop();
        if (recovery != null)
            try {
                recovery.clear();
                recovery.close();
            } catch (Exception failed) {
                System.err.println("Recovery cleanup: " + failed);
            }
        diagnostics.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(diagnosticInput);
        animation.stop();
        viewport.close();
        writeDiagnostics();
        closing = true;
        dispose();
    }

    @Override
    public void message(String message) {
        status.setText(message);
    }

    @Override
    public void error(Exception e) {
        if (committingField)
            fieldFailure = true;
        e.printStackTrace();
        String detail = e.getMessage() == null ? m("editorframe.0341") : e.getMessage();
        if (detail.contains("Singular") || detail.contains("ill-conditioned"))
            detail = m("editorframe.0342");
        if (detail.contains("shear") || detail.contains("represented by TRS"))
            detail = m("editorframe.0343");
        if (detail.contains("Duplicate") && detail.contains("name"))
            detail = m("editorframe.0344");
        if (detail.contains("parent cycle") || detail.contains("parent itself"))
            detail = m("editorframe.0345");
        if (detail.contains("length must be nonnegative"))
            detail = m("editorframe.0346");
        if (detail.contains("dimensions must be positive"))
            detail = m("editorframe.0347");
        if (detail.contains("must be finite"))
            detail = m("editorframe.0348");
        if (errorShowing)
            return;
        errorShowing = true;
        try {
            JOptionPane.showMessageDialog(this, detail, m("editorframe.0349"), JOptionPane.ERROR_MESSAGE);
        } finally {
            errorShowing = false;
        }
        changed();
    }

    /**
     * Optional read-only support snapshot. Native UI tests observe this, never
     * invoke UI/model setters.
     */
    private void writeDiagnostics() {
        String dest = System.getProperty("openbone.diagnostics");
        if (dest == null)
            return;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var out = new LinkedHashMap<String, Object>();
            out.put("animation", animation.diagnostics());
            out.put("recovery",
                    Map.of("intervalMs", RecoveryStore.INTERVAL_MS, "capturedAt", recoveredAt, "error", recoveryError,
                            "file", recovery == null ? "" : recovery.file().toString(), "scanIssues",
                            recoveryScanIssues));
            out.put("screens", DesktopEnvironment.screens());
            out.put("layout", Map.of("hierarchy", hierarchySplit.getDividerLocation(), "propertyDivider",
                    propertySplit.getDividerLocation(), "state", getExtendedState()));
            var poses = new LinkedHashMap<String, Object>();
            animation.pose().forEach((id, t) -> poses.put(id.value(), t));
            out.put("pose", poses);
            var evaluated = evaluatedPose();
            var physicsStates = new LinkedHashMap<String, Object>();
            physics.states().forEach((id, state) -> physicsStates.put(id.value(), state));
            out.put("physics",
                    Map.of("mode", animation.animate() ? "FIXED_TICK_POSE" : "NONE", "frame", physics.frame(),
                            "evaluations", physics.evaluations(), "resets", physics.resets(), "active",
                            PhysicsPlayer.active(model()), "states", physicsStates));
            var worlds = new LinkedHashMap<String, Object>();
            evaluated.bones().forEach((id, t) -> worlds.put(id.value(), t));
            var regions = new LinkedHashMap<String, Object>();
            evaluated.regions().forEach((id, t) -> regions.put(id.value(), t));
            var meshes = new LinkedHashMap<String, Object>();
            evaluated.meshes().forEach((id, t) -> meshes.put(id.value(), t));
            var clips = new LinkedHashMap<String, Object>();
            evaluated.clips().forEach((id, t) -> clips.put(id.value(), t));
            var worldPaths = new LinkedHashMap<String, Object>();
            evaluated.paths().forEach((id, t) -> worldPaths.put(id.value(), t));
            out.put("worldPose",
                    Map.of("bones", worlds, "regions", regions, "meshes", meshes, "clips", clips, "paths", worldPaths));
            out.put("visibleGeometry", VisibleGeometry.evaluate(model(), evaluated));
            out.put("p8Resources", model().resources());
            out.put("evaluatedSlots", evaluated.drawSlots());
            out.put("model", mapper.readTree(new io.github.samlinb.openbone.io.ProjectCodec().encode(model())));
            out.put("selection", selected() == null ? null : selected().value());
            out.put("undo", history().undoCount());
            out.put("redo", history().redoCount());
            out.put("dirty", history().isDirty());
            out.put("preview", history().isPreviewing());
            out.put("meshEditing", viewport.meshEditing());
            out.put("meshIndex", viewport.meshIndex());
            out.put("documentPath", document.path().map(Path::toString).orElse(null));
            out.put("windowActive", isActive());
            out.put("windowFocused", isFocused());
            out.put("lastInputWhen", lastInputWhen);
            out.put("keyboardEvents", new ArrayList<>(diagnosticKeys));
            out.put("clipboard", clipboardReceipt());
            List<Object> propertyControls = new ArrayList<>();
            describeControls(properties, propertyControls);
            out.put("propertyControls", propertyControls);
            List<Object> animationControls = new ArrayList<>();
            describeControls(animation, animationControls);
            out.put("animationControls", animationControls);
            out.put("tool", viewport.tool().toString());
            out.put("space", viewport.space().toString());
            out.put("camera",
                    Map.of("x", viewport.camera().x(), "y", viewport.camera().y(), "zoom", viewport.camera().zoom()));
            out.put("viewport", viewport.metrics());
            out.put("title", getTitle());
            Map<String, Object> bounds = new LinkedHashMap<>();
            if (isShowing()) {
                putBounds(bounds, "frame", this);
                putBounds(bounds, "viewport", viewport.canvas());
                putBounds(bounds, "tree", tree);
                putBounds(bounds, "properties", properties.getParent());
                for (var e : named.entrySet())
                    if (e.getValue().isShowing())
                        putBounds(bounds, e.getKey(), e.getValue());
                Map<String, Object> rows = new LinkedHashMap<>();
                for (var e : paths.entrySet()) {
                    var r = tree.getPathBounds(e.getValue());
                    if (r != null) {
                        var p = tree.getLocationOnScreen();
                        rows.put(e.getKey().value(), List.of(p.x + r.x, p.y + r.y, r.width, r.height));
                    }
                }
                out.put("treeRows", rows);
            }
            out.put("bounds", bounds);
            List<Object> dialogs = new ArrayList<>();
            for (Window window : getOwnedWindows())
                if (window.isShowing()) {
                    List<Object> controls = new ArrayList<>();
                    describeControls(window, controls);
                    List<String> messages = new ArrayList<>();
                    describeMessages(window, messages);
                    dialogs.add(Map.of("title", window instanceof Dialog dialog ? dialog.getTitle() : "", "controls",
                            controls, "messages", messages));
                }
            out.put("dialogs", dialogs);
            Path target = Path.of(dest);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Diagnostics: " + e);
        }
    }

    private static void putBounds(Map<String, Object> bounds, String name, Component component) {
        var p = component.getLocationOnScreen();
        bounds.put(name, List.of(p.x, p.y, component.getWidth(), component.getHeight()));
    }

    /**
     * Read-only error text, separate from actionable controls; enabled only by
     * diagnostics.
     */
    private static void describeMessages(Component component, List<String> messages) {
        if (!component.isShowing())
            return;
        if (component instanceof JLabel label && label.getText() != null)
            messages.add(label.getText());
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                describeMessages(child, messages);
    }

    private static void describeControls(Component c, List<Object> controls) {
        if (!c.isShowing())
            return;
        if (c instanceof AbstractButton || c instanceof javax.swing.text.JTextComponent || c instanceof JComboBox<?>
                || c instanceof JList<?>) {
            Map<String, Object> item = new LinkedHashMap<>();
            putBounds(item, "bounds", c);
            item.put("class", c.getClass().getSimpleName());
            if (c instanceof JComponent component) {
                var visible = component.getVisibleRect();
                var origin = c.getLocationOnScreen();
                item.put("visibleBounds",
                        List.of(origin.x + visible.x, origin.y + visible.y, visible.width, visible.height));
            }
            item.put("kind", c instanceof javax.swing.text.JTextComponent ? "text"
                    : c instanceof JComboBox<?> ? "combo" : c instanceof JList<?> ? "list" : "button");
            item.put("text",
                    c instanceof AbstractButton b ? b.getText()
                            : c instanceof javax.swing.text.JTextComponent t ? t.getText()
                                    : c instanceof JComboBox<?> combo ? String.valueOf(combo.getSelectedItem())
                                            : String.valueOf(((JList<?>) c).getSelectedValuesList()));
            if (c instanceof JList<?> list) {
                item.put("selectedIndices", Arrays.stream(list.getSelectedIndices()).boxed().toList());
                List<Object> cells = new ArrayList<>();
                var origin = list.getLocationOnScreen();
                for (int i = 0; i < list.getModel().getSize(); i++) {
                    var b = list.getCellBounds(i, i);
                    if (b != null)
                        cells.add(Map.of("index", i, "text", String.valueOf(list.getModel().getElementAt(i)), "bounds",
                                List.of(origin.x + b.x, origin.y + b.y, b.width, b.height)));
                }
                item.put("cells", cells);
            }
            item.put("name", c.getName() == null ? "" : c.getName());
            item.put("focused", c.isFocusOwner());
            item.put("identity", System.identityHashCode(c));
            controls.add(item);
        }
        if (c instanceof Container container)
            for (Component child : container.getComponents())
                describeControls(child, controls);
    }
}
