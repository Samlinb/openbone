package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.swing.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Native Swing timeline. Only key edits enter history; its clock never touches
 * Setup.
 */
final class AnimationPanel extends JPanel {
    interface Host {
        CommandHistory history();

        Bone selectedBone();

        boolean commitFields();

        void changed();

        void error(Exception e);

        void cancelViewport();

        void poseChanged();

        Map<String, JComponent> controls();
        default java.nio.file.Path imageBase() { return java.nio.file.Path.of("").toAbsolutePath(); }
        default void resourcesChanged() { changed(); }
    }

    private final Host host;
    private final Playback clock = new Playback();

    private record ClipChoice(EntityId id, String name) {
        public String toString() {
            return name;
        }
    }

    private final JComboBox<ClipChoice> clips = new JComboBox<>();
    private final JLabel eventStatus = new JLabel(m("animationpanel.0001"));
    private boolean zeroEmitted;
    private long firedCount;
    private double eventCursor;
    private final java.util.List<EventSampler.Fired> recentEvents = new ArrayList<>();
    private boolean animate, refreshing, committingTime;
    private EntityId active = AnimationClip.DEFAULT_ID;
    private DocumentSnapshot observedModel;
    private int fps = 30;
    private boolean snap;
    private final JPanel body = new JPanel(new BorderLayout());
    private final JTextField time = new JTextField("0", 7);
    private final JLabel frame = new JLabel();
    private final JButton play = new JButton(m("animationpanel.0002"));
    private final JCheckBox loop = new JCheckBox(m("animationpanel.0003")),
            snapping = new JCheckBox(m("animationpanel.0004"));
    private final JComboBox<String> mode = new JComboBox<>(
            new String[] { m("animationpanel.0005"), m("animationpanel.0006") });
    private final TrackView tracks = new TrackView();
    private final JComponent ruler = new JComponent() {
        public Dimension getPreferredSize() {
            return new Dimension(600, 24);
        }

        protected void paintComponent(Graphics raw) {
            var g = (Graphics2D) raw;
            g.setColor(new Color(37, 43, 51));
            g.fillRect(0, 0, getWidth(), 24);
            g.setColor(Color.LIGHT_GRAY);
            for (int i = 0; i <= 10; i++) {
                double t = i * tracks.span() / 10;
                g.drawString(String.format(Locale.ROOT, "%.2f", t), tracks.x(t) - 10, 17);
            }
        }
    };
    private final javax.swing.Timer timer;
    private AnimationEdits.Clipboard clipboard;

    AnimationPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        mode.setName("animation.mode");
        register(mode);
        top.add(mode);
        register(clips, "animation.clips");
        clips.setPreferredSize(new Dimension(160, 26));
        top.add(clips);
        addButton(top, m("animationpanel.0007"), "animation.newClip", () -> organize("new"));
        addButton(top, m("animationpanel.0008"), "animation.duplicateClip", () -> organize("copy"));
        addButton(top, m("animationpanel.0009"), "animation.renameClip", () -> organize("rename"));
        addButton(top, m("animationpanel.0010"), "animation.deleteClip", () -> organize("delete"));
        var header = new JPanel(new GridLayout(0, 1));
        header.add(top);
        var resources = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        addButton(resources, m("animationpanel.0011"), "animation.resources", () -> {
            if (!prepare())
                return;
            clock.seek(clock.time());
            new ResourceDialog(this, host.history(), this::active, clock.time(), host::resourcesChanged, host::error,host::imageBase)
                    .setVisible(true);
        });
        resources.add(eventStatus);
        header.add(resources);
        add(header, BorderLayout.NORTH);
        clips.addActionListener(e -> guard(() -> {
            if (refreshing)
                return;
            if (!prepare()) {
                refresh();
                return;
            }
            var choice = (ClipChoice) clips.getSelectedItem();
            if (choice == null)
                return;
            clock.seek(0);
            active = choice.id();
            host.history().selectKeys(Set.of());
            clearEvents();
            host.changed();
        }));
        var tools = new JPanel(new GridLayout(2, 1));
        var transport = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        addButton(transport, m("animationpanel.0012"), "animation.start", () -> seek(0));
        transport.add(play);
        play.setName("animation.play");
        register(play);
        play.addActionListener(e -> guard(() -> {
            if (!prepare())
                return;
            if (clock.playing()) {
                clock.pause(System.nanoTime(), duration());
                emitEvents(false);
            } else {
                if (clock.time() >= duration())
                    zeroEmitted = false;
                clock.play(System.nanoTime(), duration());
                eventCursor = clock.absoluteTime();
                boolean initial = clock.time() == 0 && !zeroEmitted;
                emitEvents(initial);
                if (clock.time() == 0)
                    zeroEmitted = true;
            }
            host.changed();
        }));
        register(loop, "animation.loop");
        transport.add(loop);
        loop.addActionListener(e -> {
            if (!prepare())
                return;
            clock.loop(loop.isSelected());
        });
        transport.add(new JLabel(m("animationpanel.0013")));
        register(time, "animation.time");
        transport.add(time);
        time.addActionListener(e -> commitTime(true));
        time.addFocusListener(new FocusAdapter() {
            public void focusLost(FocusEvent e) {
                commitTime(false);
            }
        });
        JComboBox<Integer> rate = new JComboBox<>(new Integer[] { 24, 30, 60, 120 });
        rate.setSelectedItem(30);
        register(rate, "animation.fps");
        rate.addActionListener(e -> {
            fps = (Integer) rate.getSelectedItem();
            refresh();
        });
        transport.add(rate);
        transport.add(new JLabel("FPS"));
        register(snapping, "animation.snap");
        transport.add(snapping);
        snapping.addActionListener(e -> snap = snapping.isSelected());
        addButton(transport, m("animationpanel.0014"), "animation.quantize",
                () -> seek(Playback.snap(clock.time(), fps)));
        transport.add(frame);
        tools.add(transport);
        var edit = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        addButton(edit, m("animationpanel.0015"), "animation.keyTRS", () -> record(EnumSet.allOf(Channel.class)));
        for (var c : Channel.values())
            addButton(edit, m("animationpanel.0016") + c, "animation.key" + c.name(), () -> record(EnumSet.of(c)));
        addButton(edit, m("animationpanel.0017"), "animation.curve", this::curveDialog);
        addButton(edit, m("animationpanel.0018"), "animation.copy", () -> {
            if (!prepare())
                return;
            clipboard = AnimationEdits.copy(model(), active, selection());
        });
        addButton(edit, m("animationpanel.0019"), "animation.paste", this::paste);
        addButton(edit, m("animationpanel.0020"), "animation.delete",
                () -> mutate(m("animationpanel.0021"), d -> AnimationEdits.remove(d, active, selection())));
        tools.add(edit);
        body.add(tools, BorderLayout.NORTH);
        var scroll = new JScrollPane(tracks);
        scroll.setPreferredSize(new Dimension(700, 130));
        scroll.setColumnHeaderView(ruler);
        register(scroll.getViewport(), "animation.trackViewport");
        body.add(scroll);
        add(body);
        body.setVisible(false);
        mode.addActionListener(e -> guard(() -> {
            if (refreshing)
                return;
            boolean next = mode.getSelectedIndex() == 1;
            if (!prepare()) {
                refresh();
                return;
            }
            cancel();
            clock.pause(System.nanoTime(), duration());
            animate = next;
            body.setVisible(animate);
            revalidate();
            host.changed();
        }));
        timer = new javax.swing.Timer(16, e -> {
            if (!clock.playing())
                return;
            guard(() -> {
                clock.tick(System.nanoTime(), duration());
                emitEvents(false);
                refresh();
                host.poseChanged();
            });
        });
        timer.start();
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(80, 90, 100)));
        tracks.setFocusable(true);
        register(tracks, "animation.tracks");
        bind("control C", () -> {
            clipboard = AnimationEdits.copy(model(), active, selection());
        });
        bind("control V", this::paste);
        bind("DELETE", () -> mutate(m("animationpanel.0021"), d -> AnimationEdits.remove(d, active, selection())));
        bind("ESCAPE", this::cancel);
    }

    private void bind(String key, Runnable run) {
        tracks.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        tracks.getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                guard(run);
            }
        });
    }

    private void register(JComponent c, String name) {
        c.setName(name);
        register(c);
    }

    private void register(JComponent c) {
        host.controls().put(c.getName(), c);
    }

    private void addButton(JPanel panel, String text, String name, Runnable run) {
        var b = new JButton(text);
        register(b, name);
        b.addActionListener(e -> guard(run));
        panel.add(b);
    }

    private DocumentSnapshot model() {
        return host.history().state().model();
    }

    private Set<EntityId> selection() {
        return host.history().state().keySelection();
    }

    EntityId active() {
        if (model().animations().isEmpty()) {
            if (!active.equals(AnimationClip.DEFAULT_ID) || clock.playing())
                clock.seek(0);
            active = AnimationClip.DEFAULT_ID;
        } else if (model().animations().stream().noneMatch(a -> a.id().equals(active))) {
            clock.seek(0);
            active = model().animations().get(0).id();
            clearEvents();
        }
        return active;
    }

    private List<BoneTimeline> visibleTimelines() {
        return model().animations().isEmpty() ? List.of() : model().animation(active()).timelines();
    }

    private record TrackRow(EntityId target, String label, Channel channel, List<Keyframe> keys,
            ConstraintChannel constraintChannel) {
        TrackRow(EntityId target, String label, Channel channel, List<Keyframe> keys) {
            this(target, label, channel, keys, null);
        }

        String kind() {
            return constraintChannel != null ? constraintChannel.name() : channel == null ? "DEFORM" : channel.name();
        }
    }

    private DocumentSnapshot rowsModel;
    private EntityId rowsClip;
    private List<TrackRow> cachedRows;

    private List<TrackRow> rows() {
        EntityId clip = active();
        if (rowsModel == model() && clip.equals(rowsClip))
            return cachedRows;
        var result = buildRows();
        rowsModel = model();
        rowsClip = clip;
        cachedRows = List.copyOf(result);
        return cachedRows;
    }

    private List<TrackRow> buildRows() {
        List<TrackRow> result = new ArrayList<>();
        for (var b : model().bones())
            for (var c : Channel.values()) {
                var keys = visibleTimelines().stream().filter(t -> t.boneId().equals(b.id()) && t.channel() == c)
                        .flatMap(t -> t.keys().stream()).toList();
                result.add(new TrackRow(b.id(), b.name() + " · " + c, c, keys));
            }
        for (var a : model().attachments())
            if (a instanceof MeshAttachment) {
                var keys = model().animations().isEmpty() ? List.<Keyframe>of()
                        : model().animation(active()).deforms().stream().filter(t -> t.attachmentId().equals(a.id()))
                                .flatMap(t -> t.keys().stream()).toList();
                result.add(new TrackRow(a.id(), a.name() + " · DEFORM", null, keys));
            }
        for (var constraint : model().constraints())
            for (var channel : ConstraintChannel.values())
                if (channel.supports(constraint)) {
                    var keys = model().animations().isEmpty() ? List.<Keyframe>of()
                            : model().animation(active()).constraintTracks().stream()
                                    .filter(t -> t.constraintId().equals(constraint.id()) && t.channel() == channel)
                                    .flatMap(t -> t.keys().stream()).toList();
                    result.add(new TrackRow(constraint.id(), constraint.name() + " · " + channel.name(), null, keys,
                            channel));
                }
        return result;
    }

    double duration() {
        return PhysicsPlayer.duration(model(), active());
    }

    boolean animate() {
        return animate;
    }

    boolean playing() {
        return clock.playing();
    }

    double time() {
        return clock.time();
    }

    /** Exact MCP seek; intentionally independent of the interactive frame snapping setting. */
    void remoteSeek(EntityId clip, double seconds) {
        model().animation(clip);
        PhysicsPlayer.validateTime(model(), clip, seconds);
        active = clip;
        animate = true;
        observedModel = model();
        clock.seek(seconds);
        clearEvents();
        host.changed();
    }

    void remoteControl(String action,EntityId clip,Double seconds,Boolean looping) {
        if(clip!=null)model().animation(clip);
        EntityId target=clip==null?active():clip;
        if(seconds!=null||action.equals("play")||action.equals("seek"))
            remoteSeek(target,seconds==null?clock.time():seconds);
        if(looping!=null) {clock.loop(looping);loop.setSelected(looping);}
        if(action.equals("play"))clock.play(System.nanoTime(),duration());
        if(action.equals("pause"))clock.pause(System.nanoTime(),duration());
        host.changed();
    }

    Map<EntityId, Transform> pose() {
        return animate && !model().animations().isEmpty()
                ? AnimationSampler.pose(model(), active(), clock.time(), false)
                : Map.of();
    }

    DocumentSnapshot display() {
        return animate && !model().animations().isEmpty() ? AnimationEdits.posed(model(), active(), clock.time())
                : model();
    }

    private boolean prepare() {
        if (!host.commitFields())
            return false;
        host.cancelViewport();
        cancel();
        return true;
    }

    boolean editing() {
        if (!host.commitFields())
            return false;
        if (clock.playing()) {
            clock.pause(System.nanoTime(), duration());
            host.changed();
        }
        return true;
    }

    void seek(double value) {
        PhysicsPlayer.validateTime(model(), active(), value);
        if (!prepare())
            return;
        cancel();
        clock.seek(snap ? Playback.snap(value, fps) : value);
        eventCursor = clock.absoluteTime();
        zeroEmitted = false;
        host.changed();
    }

    private void commitTime(boolean explicit) {
        if (refreshing || committingTime || !explicit && time.getText().equals(Double.toString(clock.time())))
            return;
        committingTime = true;
        try {
            seek(parse(time.getText()));
            time.setText(Double.toString(clock.time()));
        } catch (Exception failure) {
            // Restore before opening a modal error: focusLost is delivered inside
            // the nested dialog event loop and must not submit the invalid value twice.
            time.setText(Double.toString(clock.time()));
            host.error(failure);
        } finally {
            committingTime = false;
        }
    }

    void reset() {
        cancel();
        clock.seek(0);
        clearEvents();
        animate = false;
        body.setVisible(false);
        refresh();
    }

    void stop() {
        timer.stop();
        cancel();
    }

    void refresh() {
        refreshing = true;
        try {
            active();
            if (observedModel != null && !observedModel.equals(model())) {
                clock.seek(Math.min(clock.time(), duration()));
                eventCursor = clock.absoluteTime();
            }
            observedModel = model();
            clips.removeAllItems();
            for (var a : model().animations()) {
                var choice = new ClipChoice(a.id(), a.name());
                clips.addItem(choice);
                if (a.id().equals(active))
                    clips.setSelectedItem(choice);
            }
            mode.setSelectedIndex(animate ? 1 : 0);
            body.setVisible(animate);
            if (!time.isFocusOwner())
                time.setText(Double.toString(clock.time()));
            play.setText(clock.playing() ? m("animationpanel.0022") : m("animationpanel.0002"));
            frame.setText(String.format(Locale.ROOT, m("animationpanel.0023"), clock.time() * fps, duration()));
            tracks.setPreferredSize(new Dimension(600, Math.max(90, rows().size() * 24)));
            tracks.revalidate();
            tracks.repaint();
            ruler.repaint();
        } finally {
            refreshing = false;
        }
    }

    private void clearEvents() {
        zeroEmitted = false;
        eventCursor = clock.absoluteTime();
        firedCount = 0;
        recentEvents.clear();
        eventStatus.setText(m("animationpanel.0024"));
    }

    private void emitEvents(boolean includeStart) {
        if (model().animations().isEmpty())
            return;
        var fired = EventSampler.between(model().animation(active()), eventCursor, clock.absoluteTime(), clock.loop(),
                includeStart);
        eventCursor = clock.absoluteTime();
        firedCount += fired.size();
        recentEvents.addAll(fired);
        while (recentEvents.size() > 100)
            recentEvents.remove(0);
        eventStatus.setText(m("animationpanel.0025") + firedCount + (recentEvents.isEmpty() ? ""
                : " · " + recentEvents.get(recentEvents.size() - 1).key().stringValue()));
    }

    private void organize(String operation) {
        if (!prepare())
            return;
        clock.seek(0);
        clearEvents();
        EntityId current = active();
        if (operation.equals("delete")) {
            if (model().animations().isEmpty())
                return;
            if (JOptionPane.showConfirmDialog(this,
                    m("animationpanel.0026") + model().animation(current).name() + m("animationpanel.0027"),
                    m("animationpanel.0010"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
                return;
            host.history().execute(m("animationpanel.0010"),
                    Commands.model(d -> ExtendedEdits.deleteAnimation(d, current)));
            host.changed();
            return;
        }
        String name = JOptionPane.showInputDialog(this, m("animationpanel.0028"),
                operation.equals("new") ? m("animationpanel.0029")
                        : model().animations().isEmpty() ? m("animationpanel.0029")
                                : model().animation(current).name() + m("animationpanel.0030"));
        if (name == null)
            return;
        EntityId next = EntityId.create();
        host.history().execute(m("animationpanel.0031"), state -> {
            var d = state.model();
            var changed = switch (operation) {
                case "new" -> ExtendedEdits.createAnimation(d, next, name);
                case "copy" -> ExtendedEdits.duplicateAnimation(d, current, next, name);
                default -> ExtendedEdits.renameAnimation(d, current, name);
            };
            return new EditorState(changed, state.selection(), Set.of());
        });
        if (!operation.equals("rename"))
            active = next;
        host.changed();
    }

    private void record(Set<Channel> channels) {
        if (!animate)
            throw new IllegalArgumentException(m("animationpanel.0032"));
        if (!prepare())
            return;
        var bone = host.selectedBone();
        if (bone == null)
            throw new IllegalArgumentException(m("animationpanel.0033"));
        clock.pause(System.nanoTime(), duration());
        var t = display().bone(bone.id()).local();
        mutate(m("animationpanel.0034"), d -> AnimationEdits.record(d, active(), bone.id(), clock.time(), t, channels));
    }

    DocumentSnapshot withActiveDefault(DocumentSnapshot d) {
        if (!d.animations().isEmpty())
            return d;
        var next = ExtendedEdits.ensureAnimation(d);
        active = next.animations().get(0).id();
        return next;
    }

    private void mutate(String name, UnaryOperator<DocumentSnapshot> operation) {
        if (!animate)
            throw new IllegalArgumentException(m("animationpanel.0032"));
        if (!prepare())
            return;
        clock.pause(System.nanoTime(), duration());
        host.history().execute(name, Commands.model(d -> operation.apply(withActiveDefault(d))));
        host.changed();
    }

    private void paste() {
        if (!animate)
            throw new IllegalArgumentException(m("animationpanel.0032"));
        if (!prepare())
            return;
        clock.pause(System.nanoTime(), duration());
        host.history().execute(m("animationpanel.0035"), state -> {
            var d = state.model();
            Set<EntityId> old = new HashSet<>();
            d.animations().forEach(a -> a.keys().forEach(k -> old.add(k.id())));
            var next = AnimationEdits.paste(withActiveDefault(d), active(), clipboard, clock.time());
            Set<EntityId> added = new LinkedHashSet<>();
            next.animation(active()).keys().forEach(k -> {
                if (!old.contains(k.id()))
                    added.add(k.id());
            });
            return new EditorState(next, state.selection(), added);
        });
        host.changed();
    }

    private static double parse(String text) {
        try {
            return Keyframe.seconds(Double.parseDouble(text.trim()));
        } catch (Exception e) {
            throw new IllegalArgumentException(m("animationpanel.0036"));
        }
    }

    private void guard(Runnable run) {
        try {
            run.run();
        } catch (Exception e) {
            host.error(e);
        }
    }

    void cancel() {
        tracks.cancel();
    }

    Map<String, Object> diagnostics() {
        var out = new LinkedHashMap<String, Object>();
        out.put("mode", animate ? "ANIMATE" : "SETUP");
        out.put("time", clock.time());
        out.put("playing", clock.playing());
        out.put("loop", clock.loop());
        out.put("fps", fps);
        out.put("snap", snap);
        out.put("duration", duration());
        out.put("clip", active().value());
        out.put("firedEventCount", firedCount);
        out.put("recentEvents", recentEvents);
        out.put("selectedKeys", selection().stream().map(EntityId::value).sorted().toList());
        List<Object> keys = new ArrayList<>();
        if (tracks.isShowing()) {
            var origin = tracks.getLocationOnScreen();
            var rows = rows();
            for (int row = 0; row < rows.size(); row++) {
                var track = rows.get(row);
                for (var k : track.keys())
                    keys.add(Map.of("id", k.id().value(), "bone", track.target().value(), "channel", track.kind(),
                            "time", k.time(), "x", origin.x + tracks.x(k.time()), "y", origin.y + 12 + row * 24));
            }
        }
        out.put("keys", keys);
        return out;
    }

    private void curveDialog() {
        if (!prepare())
            return;
        var keys = model().animation(active()).keys().stream().filter(k -> selection().contains(k.id())).toList();
        if (keys.isEmpty())
            throw new IllegalArgumentException(m("animationpanel.0037"));
        Set<EntityId> deformIds = new HashSet<>();
        model().animation(active()).deforms().forEach(t -> t.keys().forEach(k -> deformIds.add(k.id())));
        boolean deform = keys.stream().allMatch(k -> deformIds.contains(k.id()));
        int size = deform ? 1 : keys.get(0).values().size();
        if (!deform && keys.stream().anyMatch(k -> k.values().size() != size))
            throw new IllegalArgumentException(m("animationpanel.0038"));
        Map<EntityId, ConstraintChannel> kinds = new HashMap<>();
        model().animation(active()).constraintTracks()
                .forEach(t -> t.keys().forEach(k -> kinds.put(k.id(), t.channel())));
        ConstraintChannel constraintChannel = kinds.get(keys.get(0).id());
        if (keys.stream().anyMatch(k -> kinds.get(k.id()) != constraintChannel))
            throw new IllegalArgumentException(m("animationpanel.0039"));
        JPanel panel = new JPanel(new BorderLayout(4, 8));
        JPanel inputs = new JPanel(new GridLayout(0, 5, 5, 5));
        for (String text : new String[] { m("animationpanel.0040"), "X1", "Y1", "X2", "Y2" })
            inputs.add(new JLabel(text));
        List<JComboBox<Curve.Kind>> types = new ArrayList<>();
        List<JTextField[]> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            var c = keys.get(0).curves().get(i);
            var type = new JComboBox<>(Curve.Kind.values());
            type.setName("curve.type." + i);
            type.setSelectedItem(c.kind());
            boolean discrete = constraintChannel != null && constraintChannel.discrete(i);
            type.setEnabled(!discrete);
            final String component = constraintChannel != null ? constraintChannel.labels().get(i)
                    : deform ? "deform %" : size == 1 ? m("channel.0002") : i == 0 ? "X" : "Y";
            type.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                        boolean focus) {
                    var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    String kind = switch ((Curve.Kind) value) {
                        case LINEAR -> m("animationpanel.0041");
                        case STEPPED -> m("animationpanel.0042");
                        case BEZIER -> m("animationpanel.0043");
                    };
                    label.setText(component + " · " + kind);
                    return label;
                }
            });
            types.add(type);
            inputs.add(type);
            var fields = new JTextField[4];
            double[] v = { c.x1(), c.y1(), c.x2(), c.y2() };
            for (int j = 0; j < 4; j++) {
                fields[j] = new JTextField(Double.toString(v[j]), 5);
                fields[j].setName("curve." + i + "." + j);
                fields[j].setEnabled(!discrete);
                inputs.add(fields[j]);
            }
            values.add(fields);
        }
        panel.add(inputs, BorderLayout.NORTH);
        var graph = new JPanel() {
            protected void paintComponent(Graphics raw) {
                super.paintComponent(raw);
                var g = (Graphics2D) raw;
                int w = getWidth() - 30, h = getHeight() - 25;
                g.setColor(Color.GRAY);
                g.drawRect(15, 10, w, h);
                Color[] colors = { new Color(230, 90, 65), new Color(55, 160, 95) };
                for (int c = 0; c < size; c++)
                    try {
                        var curve = readCurve(types.get(c), values.get(c));
                        g.setColor(colors[c % 2]);
                        int px = 15, py = 10 + h;
                        for (int j = 1; j <= 200; j++) {
                            double p = j / 200.0;
                            int x = 15 + (int) (p * w), y = 10 + h - (int) (curve.progress(p) * h);
                            g.drawLine(px, py, x, y);
                            px = x;
                            py = y;
                        }
                    } catch (Exception ignored) {
                    }
            }
        };
        graph.setPreferredSize(new Dimension(420, 120));
        var listener = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                graph.repaint();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                graph.repaint();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                graph.repaint();
            }
        };
        for (var fields : values)
            for (var field : fields)
                field.getDocument().addDocumentListener(listener);
        for (var type : types)
            type.addActionListener(e -> graph.repaint());
        panel.add(graph);
        var preview = new JButton(m("animationpanel.0044"));
        preview.addActionListener(e -> graph.repaint());
        panel.add(preview, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, panel, m("animationpanel.0045"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            var curves = new ArrayList<Curve>();
            for (int i = 0; i < size; i++)
                curves.add(readCurve(types.get(i), values.get(i)));
            mutate(m("animationpanel.0046"), d -> AnimationEdits.curves(d, active(), selection(), curves));
        }
    }

    private static Curve readCurve(JComboBox<Curve.Kind> type, JTextField[] fields) {
        var kind = (Curve.Kind) type.getSelectedItem();
        if (kind == Curve.Kind.LINEAR)
            return Curve.LINEAR;
        if (kind == Curve.Kind.STEPPED)
            return Curve.STEPPED;
        try {
            return new Curve(kind, Double.parseDouble(fields[0].getText()), Double.parseDouble(fields[1].getText()),
                    Double.parseDouble(fields[2].getText()), Double.parseDouble(fields[3].getText()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(m("animationpanel.0047"));
        }
    }

    private final class TrackView extends JComponent implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(600, 130);
        }

        // Default wheel events use 3 units: one notch scrolls one 24px track row.
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 8;
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

        private static final int LEFT = 150;
        private Point pressed;
        private boolean dragging, invalid;
        private double range, dragOrigin;
        private Set<EntityId> dragIds;

        TrackView() {
            var mouse = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    guard(() -> {
                        if (!animate || !editing())
                            return;
                        requestFocusInWindow();
                        range = span();
                        pressed = e.getPoint();
                        int row = e.getY() / 24;
                        EntityId hit = null;
                        var rows = rows();
                        if (row >= 0 && e.getY() >= 0 && row < rows.size())
                            for (var k : rows.get(row).keys())
                                if (Math.abs(x(k.time()) - e.getX()) <= 8 && Math.abs(12 + row * 24 - e.getY()) <= 10)
                                    hit = k.id();
                        var ids = new LinkedHashSet<>(selection());
                        if (hit != null) {
                            if (e.isControlDown()) {
                                if (!ids.remove(hit))
                                    ids.add(hit);
                            } else if (!ids.contains(hit)) {
                                ids.clear();
                                ids.add(hit);
                            }
                            host.history().selectKeys(ids);
                            host.changed();
                        } else {
                            if (!e.isControlDown())
                                host.history().selectKeys(Set.of());
                            seek(seconds(e.getX(), range));
                            pressed = null;
                        }
                    });
                }

                public void mouseDragged(MouseEvent e) {
                    guard(() -> {
                        if (pressed == null || selection().isEmpty())
                            return;
                        if (!dragging && pressed.distance(e.getPoint()) < 3)
                            return;
                        if (!dragging) {
                            dragging = true;
                            invalid = false;
                            dragIds = Set.copyOf(selection());
                            dragOrigin = AnimationEdits.copy(model(), active(), dragIds).origin();
                            host.history().beginPreview(m("animationpanel.0048"));
                        }
                        double delta = (e.getX() - pressed.x) * range / Math.max(1, getWidth() - LEFT - 18);
                        if (snap) {
                            double first = dragOrigin;
                            delta = Playback.snap(Math.max(0, first + delta), fps) - first;
                        }
                        double move = delta;
                        try {
                            host.history().updatePreview(
                                    Commands.model(d -> AnimationEdits.move(d, active(), dragIds, move)));
                            invalid = false;
                        } catch (IllegalArgumentException ex) {
                            invalid = true;
                        }
                        host.changed();
                    });
                }

                public void mouseReleased(MouseEvent e) {
                    guard(() -> {
                        if (dragging) {
                            if (invalid)
                                host.history().cancelPreview();
                            else
                                host.history().commitPreview();
                        }
                        boolean rejected = dragging && invalid;
                        dragging = false;
                        invalid = false;
                        pressed = null;
                        host.changed();
                        if (rejected)
                            throw new IllegalArgumentException(m("animationpanel.0049"));
                    });
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void cancel() {
            if (dragging && host.history().isPreviewing())
                host.history().cancelPreview();
            dragging = false;
            pressed = null;
            repaint();
        }

        double span() {
            return Math.max(2, Math.ceil(duration() + .5));
        }

        int x(double time) {
            return LEFT + (int) Math.round(time / span() * Math.max(1, getWidth() - LEFT - 18));
        }

        double seconds(int x, double range) {
            return Math.max(0, (x - LEFT) * range / Math.max(1, getWidth() - LEFT - 18));
        }

        protected void paintComponent(Graphics raw) {
            var g = (Graphics2D) raw;
            g.setColor(new Color(37, 43, 51));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont());
            var rowData = rows();
            int rows = rowData.size();
            for (int row = 0; row < rows; row++) {
                int y = row * 24;
                g.setColor(row % 3 == 0 ? new Color(55, 62, 71) : new Color(43, 49, 58));
                g.fillRect(0, y, getWidth(), 24);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(rowData.get(row).label(), 8, y + 17);
            }
            double span = span();
            for (int i = 0; i <= 10; i++) {
                double t = i * span / 10;
                int x = x(t);
                g.setColor(new Color(85, 92, 100));
                g.drawLine(x, 0, x, getHeight());
            }
            for (int row = 0; row < rows; row++) {
                for (var k : rowData.get(row).keys()) {
                    int x = x(k.time()), y = 12 + row * 24;
                    g.setColor(selection().contains(k.id()) ? new Color(255, 194, 65) : new Color(120, 190, 225));
                    g.fillPolygon(new int[] { x, x + 6, x, x - 6 }, new int[] { y - 7, y, y + 7, y }, 4);
                }
            }
            g.setColor(invalid ? Color.RED : new Color(255, 105, 90));
            int cursor = x(clock.time());
            g.drawLine(cursor, 0, cursor, getHeight());
        }
    }
}
