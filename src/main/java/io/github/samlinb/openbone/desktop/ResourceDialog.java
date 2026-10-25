package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.viewport.ImageAssets;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Native P8 editor. Every Apply is one history entry; modal drafts do not
 * mutate models.
 */
final class ResourceDialog extends JDialog {
    private final CommandHistory history;
    private final Supplier<EntityId> active;
    private final Runnable changed;
    private final Consumer<Exception> error;
    private final JTextField keyTime = new JTextField(8);
    private final JComboBox<String> skin = new JComboBox<>(), slot = new JComboBox<>(), attachment = new JComboBox<>();
    private final JTextField skinName = new JTextField(), placeholder = new JTextField(), source = new JTextField();
    private final JComboBox<Slot.Blend> blend = new JComboBox<>(Slot.Blend.values());
    private final DefaultListModel<String> attachmentKeys = new DefaultListModel<>(),
            eventKeys = new DefaultListModel<>();
    private final JList<String> attachmentList = new JList<>(attachmentKeys), eventList = new JList<>(eventKeys);
    private final DefaultListModel<String> order = new DefaultListModel<>();
    private final JList<String> orderList = new JList<>(order);
    private final JComboBox<String> event = new JComboBox<>();
    private final JTextField eventName = new JTextField(), intValue = new JTextField("0"),
            floatValue = new JTextField("0"), stringValue = new JTextField(), audio = new JTextField(),
            volume = new JTextField("1"), balance = new JTextField("0");
    private List<EntityId> attachmentIds = List.of(), eventIds = List.of();
    private boolean refreshing;

    ResourceDialog(Component parent, CommandHistory history, Supplier<EntityId> active, double time, Runnable changed,
            Consumer<Exception> error) {
        super(SwingUtilities.getWindowAncestor(parent), m("resourcedialog.0001"), ModalityType.APPLICATION_MODAL);
        this.history = history;
        this.active = active;
        this.changed = changed;
        this.error = error;
        setName("p8.dialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);
        var transport = new JPanel(new FlowLayout(FlowLayout.LEFT));
        transport.add(new JLabel(m("resourcedialog.0002")));
        keyTime.setName("p8.time");
        keyTime.setText(Double.toString(time));
        transport.add(keyTime);
        button(transport, m("editorframe.0001"), "p8.undo", () -> {
            history.undo();
            changed.run();
            refresh();
        });
        button(transport, m("editorframe.0002"), "p8.redo", () -> {
            history.redo();
            changed.run();
            refresh();
        });
        button(transport, m("editorframe.0042"), "p8.close", this::dispose);
        root.add(transport, BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        tabs.setName("p8.tabs");
        root.add(tabs);
        tabs.addTab(m("resourcedialog.0003"), skinPanel());
        tabs.addTab(m("resourcedialog.0004"), attachmentPanel());
        tabs.addTab(m("resourcedialog.0005"), eventPanel());
        tabs.addTab(m("resourcedialog.0006"), orderPanel());
        refresh();
        setSize(700, 620);
        var bounds = parent.getGraphicsConfiguration().getBounds();
        setSize(Math.min(getWidth(), bounds.width - 80), Math.min(getHeight(), bounds.height - 100));
        setLocationRelativeTo(SwingUtilities.getWindowAncestor(parent));
    }

    private DocumentSnapshot model() {
        return history.state().model();
    }

    private JPanel form() {
        return new JPanel(new GridLayout(0, 2, 6, 6));
    }

    private void field(JPanel panel, String label, JComponent field, String name) {
        panel.add(new JLabel(label));
        field.setName(name);
        panel.add(field);
    }

    private void button(JPanel panel, String text, String name, Runnable action) {
        var b = new JButton(text);
        b.setName(name);
        b.addActionListener(e -> guard(action));
        panel.add(b);
    }

    private void guard(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            error.accept(e);
        }
    }

    private void edit(String name, UnaryOperator<DocumentSnapshot> operation) {
        history.execute(name, Commands.model(operation));
        changed.run();
        refresh();
    }

    private double time() {
        return Keyframe.seconds(Double.parseDouble(keyTime.getText().strip()));
    }

    private EntityId clip() {
        EntityId id = active.get();
        model().animation(id);
        return id;
    }

    private Slot slot() {
        String name = (String) slot.getSelectedItem();
        return model().slots().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(m("resourcedialog.0007")));
    }

    private JPanel skinPanel() {
        var panel = new JPanel(new BorderLayout(6, 6));
        var fields = form();
        field(fields, m("resourcedialog.0008"), skin, "p8.skin");
        field(fields, m("resourcedialog.0009"), skinName, "p8.skinName");
        field(fields, m("resourcedialog.0010"), placeholder, "p8.placeholder");
        field(fields, m("resourcedialog.0011"), source, "p8.source");
        field(fields, m("resourcedialog.0012"), new JLabel(m("resourcedialog.0013")), "p8.slotHint");
        field(fields, m("resourcedialog.0014"), blend, "p8.blend");
        panel.add(fields, BorderLayout.NORTH);
        var actions = new JPanel(new GridLayout(0, 2, 6, 6));
        button(actions, m("resourcedialog.0015"), "p8.switchSkin",
                () -> edit(m("resourcedialog.0015"), d -> ExtendedEdits.skin(d, (String) skin.getSelectedItem())));
        button(actions, m("resourcedialog.0016"), "p8.addSkin",
                () -> edit(m("resourcedialog.0016"), d -> ExtendedEdits.addSkin(d, skinName.getText().strip())));
        button(actions, m("resourcedialog.0017"), "p8.choosePng", () -> {
            var chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                source.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
        });
        button(actions, m("resourcedialog.0018"), "p8.bind", () -> {
            var s = slot();
            Path path = Path.of(source.getText()).toAbsolutePath().normalize();
            try {
                var image = ImageAssets.readPng(path);
                var a = new RegionAttachment(EntityId.create(), placeholder.getText().strip(), s.id(), path.toString(),
                        Transform.IDENTITY, image.getWidth(), image.getHeight());
                edit(m("resourcedialog.0019"), d -> ExtendedEdits.bind(d, d.resources().activeSkin(), a, null));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        });
        button(actions, m("resourcedialog.0020"), "p8.applyBlend", () -> {
            var id = slot().id();
            var value = (Slot.Blend) blend.getSelectedItem();
            edit(m("resourcedialog.0021"), d -> ModelEdits.editSlot(d, id,
                    s -> new Slot(s.id(), s.name(), s.boneId(), s.activeAttachmentId(), value)));
        });
        panel.add(actions, BorderLayout.SOUTH);
        panel.add(new JLabel(m("resourcedialog.0022")));
        return panel;
    }

    private JPanel attachmentPanel() {
        var panel = new JPanel(new BorderLayout(6, 6));
        var fields = form();
        field(fields, "slot", slot, "p8.slot");
        field(fields, m("resourcedialog.0023"), attachment, "p8.attachment");
        slot.addActionListener(e -> {
            if (!refreshing)
                refreshAttachments();
        });
        panel.add(fields, BorderLayout.NORTH);
        attachmentList.setName("p8.attachmentKeys");
        panel.add(new JScrollPane(attachmentList));
        var actions = new JPanel(new GridLayout(0, 2, 6, 6));
        button(actions, m("resourcedialog.0024"), "p8.setupAttachment", () -> {
            var s = slot();
            String name = attachmentName();
            var id = name == null ? null
                    : model().attachments().stream().filter(a -> a.slotId().equals(s.id()) && a.name().equals(name))
                            .findFirst().orElseThrow().id();
            edit(m("resourcedialog.0024"), d -> ModelEdits.editSlot(d, s.id(), v -> v.withActiveAttachment(id)));
        });
        button(actions, m("resourcedialog.0025"), "p8.keyAttachment", () -> {
            var c = clip();
            var s = slot().id();
            double t = time();
            String name = attachmentName();
            edit(m("resourcedialog.0026"), d -> ExtendedEdits.attachment(d, c, s, t, name));
        });
        button(actions, m("resourcedialog.0027"), "p8.removeAttachment", () -> {
            int i = attachmentList.getSelectedIndex();
            if (i < 0)
                throw new IllegalArgumentException(m("resourcedialog.0028"));
            var id = attachmentIds.get(i);
            var c = clip();
            edit(m("resourcedialog.0029"), d -> ExtendedEdits.removeDiscrete(d, c, id));
        });
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private String attachmentName() {
        return attachment.getSelectedIndex() <= 0 ? null : (String) attachment.getSelectedItem();
    }

    private JPanel orderPanel() {
        var panel = new JPanel(new BorderLayout(6, 6));
        orderList.setName("p8.order");
        panel.add(new JScrollPane(orderList));
        var actions = new JPanel(new GridLayout(0, 2, 6, 6));
        button(actions, m("resourcedialog.0030"), "p8.orderUp", () -> move(-1));
        button(actions, m("resourcedialog.0031"), "p8.orderDown", () -> move(1));
        button(actions, m("resourcedialog.0032"), "p8.orderReset", () -> {
            order.clear();
            model().slots().forEach(s -> order.addElement(s.name()));
        });
        button(actions, m("resourcedialog.0033"), "p8.keyOrder", () -> {
            var c = clip();
            double t = time();
            var ids = new ArrayList<EntityId>();
            for (int i = 0; i < order.size(); i++) {
                String name = order.get(i);
                ids.add(model().slots().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().id());
            }
            edit(m("resourcedialog.0034"), d -> ExtendedEdits.drawOrder(d, c, t, ids));
        });
        button(actions, m("resourcedialog.0035"), "p8.removeOrder", () -> {
            var c = clip();
            double t = time();
            var key = model().animation(c).discrete().drawOrder().stream().filter(k -> k.time() == t).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(m("resourcedialog.0036")));
            edit(m("resourcedialog.0037"), d -> ExtendedEdits.removeDiscrete(d, c, key.id()));
        });
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void move(int delta) {
        int i = orderList.getSelectedIndex(), n = i + delta;
        if (i < 0 || n < 0 || n >= order.size())
            return;
        String value = order.remove(i);
        order.add(n, value);
        orderList.setSelectedIndex(n);
    }

    private JPanel eventPanel() {
        var panel = new JPanel(new BorderLayout(6, 6));
        var fields = form();
        field(fields, m("resourcedialog.0038"), event, "p8.event");
        field(fields, m("editorframe.0096"), eventName, "p8.eventName");
        field(fields, m("resourcedialog.0039"), intValue, "p8.eventInt");
        field(fields, m("resourcedialog.0040"), floatValue, "p8.eventFloat");
        field(fields, m("resourcedialog.0041"), stringValue, "p8.eventString");
        field(fields, m("resourcedialog.0042"), audio, "p8.eventAudio");
        field(fields, m("resourcedialog.0043"), volume, "p8.eventVolume");
        field(fields, m("resourcedialog.0044"), balance, "p8.eventBalance");
        event.addActionListener(e -> {
            if (!refreshing)
                loadDefinition();
        });
        panel.add(fields, BorderLayout.NORTH);
        eventList.setName("p8.eventKeys");
        panel.add(new JScrollPane(eventList));
        var actions = new JPanel(new GridLayout(0, 2, 6, 6));
        button(actions, m("resourcedialog.0045"), "p8.defineEvent", () -> {
            String name = eventName.getText().strip();
            var old = model().resources().events().stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
            var value = new EventDefinition(old == null ? EntityId.create() : old.id(), name,
                    Integer.parseInt(intValue.getText().strip()), Double.parseDouble(floatValue.getText().strip()),
                    stringValue.getText(), audio.getText(), Double.parseDouble(volume.getText()),
                    Double.parseDouble(balance.getText()));
            edit(m("resourcedialog.0046"), d -> ExtendedEdits.eventDefinition(d, value));
        });
        button(actions, m("resourcedialog.0047"), "p8.keyEvent", () -> putEvent(false));
        button(actions, m("resourcedialog.0048"), "p8.updateEvent", () -> putEvent(true));
        button(actions, m("resourcedialog.0049"), "p8.loadEvent", () -> {
            var key = selectedEventKey();
            keyTime.setText(Double.toString(key.time()));
            var def = model().resources().events().stream().filter(v -> v.id().equals(key.eventId())).findFirst()
                    .orElseThrow();
            event.setSelectedItem(def.name());
            intValue.setText("" + key.intValue());
            floatValue.setText("" + key.floatValue());
            stringValue.setText(key.stringValue());
            volume.setText("" + key.volume());
            balance.setText("" + key.balance());
        });
        button(actions, m("resourcedialog.0050"), "p8.removeEvent", () -> {
            var key = selectedEventKey();
            var c = clip();
            edit(m("resourcedialog.0051"), d -> ExtendedEdits.removeDiscrete(d, c, key.id()));
        });
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private DiscreteTracks.EventKey selectedEventKey() {
        int i = eventList.getSelectedIndex();
        if (i < 0)
            throw new IllegalArgumentException(m("resourcedialog.0052"));
        var id = eventIds.get(i);
        return model().animation(clip()).discrete().events().stream().filter(k -> k.id().equals(id)).findFirst()
                .orElseThrow();
    }

    private void putEvent(boolean update) {
        var c = clip();
        var def = model().resources().events().stream().filter(v -> v.name().equals(event.getSelectedItem()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(m("resourcedialog.0053")));
        var key = new DiscreteTracks.EventKey(update ? selectedEventKey().id() : EntityId.create(), time(), def.id(),
                Integer.parseInt(intValue.getText().strip()), Double.parseDouble(floatValue.getText().strip()),
                stringValue.getText(), Double.parseDouble(volume.getText()), Double.parseDouble(balance.getText()));
        edit(m("resourcedialog.0054"), d -> ExtendedEdits.event(d, c, key));
    }

    private void loadDefinition() {
        var value = model().resources().events().stream().filter(e -> e.name().equals(event.getSelectedItem()))
                .findFirst().orElse(null);
        if (value == null)
            return;
        eventName.setText(value.name());
        intValue.setText("" + value.intValue());
        floatValue.setText("" + value.floatValue());
        stringValue.setText(value.stringValue());
        audio.setText(value.audio());
        volume.setText("" + value.volume());
        balance.setText("" + value.balance());
    }

    private void refreshAttachments() {
        String name = (String) attachment.getSelectedItem();
        attachment.removeAllItems();
        attachment.addItem(m("resourcedialog.0055"));
        if (slot.getSelectedItem() != null) {
            var s = slot();
            model().attachments().stream().filter(a -> a.slotId().equals(s.id())).map(Attachment::name).distinct()
                    .forEach(attachment::addItem);
            blend.setSelectedItem(s.blend());
        }
        if (name != null)
            attachment.setSelectedItem(name);
    }

    private void refresh() {
        refreshing = true;
        try {
            String selectedSlot = (String) slot.getSelectedItem(), selectedEvent = (String) event.getSelectedItem();
            skin.removeAllItems();
            model().skins().forEach(s -> skin.addItem(s.name()));
            skin.setSelectedItem(model().resources().activeSkin());
            slot.removeAllItems();
            model().slots().forEach(s -> slot.addItem(s.name()));
            if (selectedSlot != null)
                slot.setSelectedItem(selectedSlot);
            refreshAttachments();
            event.removeAllItems();
            model().resources().events().forEach(e -> event.addItem(e.name()));
            if (selectedEvent != null)
                event.setSelectedItem(selectedEvent);
            order.clear();
            model().slots().forEach(s -> order.addElement(s.name()));
            attachmentKeys.clear();
            eventKeys.clear();
            if (!model().animations().isEmpty()) {
                var tracks = model().animation(active.get()).discrete();
                attachmentIds = tracks.attachments().stream().map(DiscreteTracks.AttachmentKey::id).toList();
                eventIds = tracks.events().stream().map(DiscreteTracks.EventKey::id).toList();
                for (var k : tracks.attachments())
                    attachmentKeys
                            .addElement(k.time()
                                    + " · " + model().slots().stream().filter(s -> s.id().equals(k.slotId()))
                                            .findFirst().orElseThrow().name()
                                    + " → " + (k.name() == null ? "null" : k.name()));
                for (var k : tracks.events())
                    eventKeys.addElement(k.time() + " · "
                            + model().resources().events().stream().filter(e -> e.id().equals(k.eventId())).findFirst()
                                    .orElseThrow().name()
                            + " · " + k.intValue() + " / " + k.floatValue() + " / " + k.stringValue());
            }
        } finally {
            refreshing = false;
        }
    }
}
