package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.Callable;
import javax.swing.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Non-modal control surface. Credential and disk operations run outside Swing EDT. */
final class McpControlDialog extends JDialog {
    interface Host {
        boolean available();
        boolean running();
        String url();
        String token();
        void start(int port, String token);
        void stop();
    }
    private final Host host;
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(39871, 0, 65535, 1));
    private final JPasswordField token = new JPasswordField(30);
    private final JCheckBox autoStart = new JCheckBox(m("mcp.autoStart"));
    private final JLabel status = new JLabel();
    private final JLabel authentication = new JLabel();
    private final JLabel feedback = new JLabel(" ");
    private final JTextField endpoint = new JTextField(34);
    private final JButton start = new JButton(m("mcp.startService"));
    private final JButton stop = new JButton(m("mcp.stop"));
    private final JButton save = new JButton(m("mcp.saveSettings"));
    private final Timer timer = new Timer(300, e -> refresh());
    private McpConnectionSettings.State stored;
    private String loadedToken = "";
    private boolean busy = true;

    McpControlDialog(JFrame owner, Host host) {
        super(owner, m("mcp.control"), false);
        this.host = host;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setName("mcp.control");
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        port.setName("mcp.port"); token.setName("mcp.token"); autoStart.setName("mcp.autoStart");
        status.setName("mcp.status"); start.setName("mcp.start"); stop.setName("mcp.stop"); save.setName("mcp.save");
        feedback.setName("mcp.feedback"); endpoint.setName("mcp.endpoint");
        endpoint.setEditable(false);
        var reveal = new JCheckBox(m("mcp.showToken"));
        char echo = token.getEchoChar();
        reveal.addActionListener(e -> token.setEchoChar(reveal.isSelected() ? (char) 0 : echo));
        var content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        int row = 0;
        addRow(content, row++, m("mcp.statusLabel"), status);
        addRow(content, row++, m("mcp.endpointLabel"), endpoint);
        addRow(content, row++, m("mcp.portLabel"), port);
        addRow(content, row++, m("mcp.tokenLabel"), token);
        addRow(content, row++, "", reveal);
        addRow(content, row++, "", new JLabel(m("mcp.tokenHint")));
        addRow(content, row++, "", authentication);
        addRow(content, row++, "", autoStart);
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(start); buttons.add(stop); buttons.add(save);
        addRow(content, row++, "", buttons);
        addRow(content, row, "", feedback);
        setContentPane(content);
        start.addActionListener(e -> persist(true));
        save.addActionListener(e -> persist(false));
        stop.addActionListener(e -> { host.stop(); feedback.setText(" "); refresh(); });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { timer.stop(); token.setText(""); }
        });
        refresh(); pack(); setMinimumSize(getSize()); setLocationRelativeTo(owner);
        timer.start();
        work(() -> {
            var state = McpConnectionSettings.read(McpConnectionSettings.path());
            return new Loaded(state, McpConnectionSettings.port(), McpConnectionSettings.token(state));
        }, value -> {
            stored = value.state(); loadedToken = value.token();
            port.setValue(value.port()); token.setText(loadedToken); autoStart.setSelected(stored.autoStart());
            feedback.setText(McpConnectionSettings.portOverridden() || McpConnectionSettings.tokenOverridden()
                    ? m("mcp.startupOverrides") : " ");

        });
    }

    private record Loaded(McpConnectionSettings.State state, int port, String token) {}

    private static void addRow(JPanel panel, int row, String label, Component value) {
        var c = new GridBagConstraints();
        c.gridy = row; c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 0, 5, 12);
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(5, 0, 5, 0);
        panel.add(value, c);
    }

    private void persist(boolean launch) {
        try {
            port.commitEdit();
            int requestedPort = ((Number) port.getValue()).intValue();
            if (!McpConnectionSettings.portOverridden()) McpConnectionSettings.parse(Integer.toString(requestedPort), false);
            String requestedToken = new String(token.getPassword());
            McpConnectionSettings.validateToken(requestedToken);
            boolean automatic = autoStart.isSelected();
            work(() -> {
                String encrypted = stored.protectedToken();
                if (!McpConnectionSettings.tokenOverridden() && !requestedToken.equals(loadedToken))
                    encrypted = requestedToken.isEmpty() ? "" : McpSecretStore.protect(requestedToken);
                var next = new McpConnectionSettings.State(McpConnectionSettings.portOverridden() ? stored.port() : requestedPort,
                        automatic, encrypted);
                McpConnectionSettings.save(McpConnectionSettings.path(), next);
                return next;
            }, next -> {
                stored = next; loadedToken = requestedToken;
                if (launch && host.available()) host.start(requestedPort, requestedToken);
                feedback.setText(m("mcp.settingsSaved"));
            });
        } catch (Exception invalid) { feedback.setText(m("mcp.invalidSettings")); }
    }

    private <T> void work(Callable<T> operation, java.util.function.Consumer<T> completed) {
        busy = true; refresh();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }
            @Override protected void done() {
                busy = false;
                try { completed.accept(get()); }
                catch (Exception failed) {
                    Throwable cause = failed instanceof java.util.concurrent.ExecutionException ? failed.getCause() : failed;
                    feedback.setText(m("mcp.operationFailed") + " " + cause.getMessage());
                }
                refresh();
            }
        }.execute();
    }

    private void refresh() {
        boolean running = host.running();
        status.setText(busy ? m("mcp.working") : running ? m("mcp.running") : m("mcp.stopped"));
        status.setForeground(running ? new Color(25, 130, 65) : UIManager.getColor("Label.foreground"));
        endpoint.setText(running ? host.url() : "http://127.0.0.1:" + port.getValue() + "/mcp");
        authentication.setText(running ? host.token().isEmpty() ? m("mcp.noAuthentication") : m("mcp.authenticationEnabled") : " ");
        port.setEnabled(!busy && !running && !McpConnectionSettings.portOverridden());
        token.setEnabled(!busy && !running && !McpConnectionSettings.tokenOverridden());
        autoStart.setEnabled(!busy && stored != null);
        start.setEnabled(!busy && !running && stored != null && host.available());
        stop.setEnabled(!busy && running);
        save.setEnabled(!busy && stored != null);
    }
}
