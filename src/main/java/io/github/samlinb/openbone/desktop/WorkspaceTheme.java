package io.github.samlinb.openbone.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;

/** Shared desktop palette. GL and custom timeline painting use the same tokens. */
public final class WorkspaceTheme extends FlatDarkLaf {
    public static final Color CHROME = new Color(0x191e25);
    public static final Color PANEL = new Color(0x232b34);
    public static final Color CANVAS = new Color(0x191f27);
    public static final Color INPUT = new Color(0x1c232b);
    public static final Color BORDER = new Color(0x394550);
    public static final Color TEXT = new Color(0xd8e1eb);
    public static final Color MUTED = new Color(0x9baabd);
    public static final Color ACCENT = new Color(0x7dd9bc);
    public static final Color SELECTION = new Color(0x2b4841);
    public static final Color GRID = new Color(0x2c3540);
    public static final Color WARNING = new Color(0xe5b973);
    public static final Color ERROR = new Color(0xef8e89);

    public static void installTheme() {
        if (!(UIManager.getLookAndFeel() instanceof WorkspaceTheme)) {
            if (!setup(new WorkspaceTheme())) throw new IllegalStateException("Cannot install OpenBone theme");
        }
    }

    @Override public String getName() { return "OpenBone Studio"; }

    @Override public UIDefaults getDefaults() {
        var d = super.getDefaults();
        for (String key : new String[]{"Panel.background", "Viewport.background", "Tree.background", "List.background",
                "TabbedPane.background", "ScrollPane.background", "ToolBar.background"}) color(d, key, PANEL);
        for (String key : new String[]{"Label.foreground", "Tree.foreground", "List.foreground", "TextField.foreground",
                "TextArea.foreground", "Menu.foreground", "MenuItem.foreground", "Button.foreground", "ToggleButton.foreground"}) color(d, key, TEXT);
        for (String key : new String[]{"TextField.background", "FormattedTextField.background", "Spinner.background",
                "ComboBox.background", "TextArea.background", "Button.background", "ToggleButton.background"}) color(d, key, INPUT);
        for (String key : new String[]{"Tree.selectionBackground", "Tree.selectionInactiveBackground", "List.selectionBackground",
                "List.selectionInactiveBackground", "ToggleButton.selectedBackground", "Button.selectedBackground", "MenuItem.selectionBackground"}) color(d, key, SELECTION);
        for (String key : new String[]{"Tree.selectionForeground", "Tree.selectionInactiveForeground", "List.selectionForeground",
                "ToggleButton.selectedForeground", "Button.selectedForeground", "TabbedPane.underlineColor", "Component.focusColor",
                "Component.accentColor", "ProgressBar.foreground"}) color(d, key, ACCENT);
        for (String key : new String[]{"Component.borderColor", "Component.disabledBorderColor", "Separator.foreground",
                "SplitPane.background", "ToolBar.borderColor", "ScrollPane.borderColor"}) color(d, key, BORDER);
        color(d,"MenuBar.background",CHROME);
        color(d,"TitlePane.background",CHROME);
        color(d,"TitlePane.foreground",TEXT);
        color(d,"TitlePane.inactiveBackground",CHROME);
        color(d,"TitlePane.inactiveForeground",MUTED);
        color(d,"TitlePane.borderColor",BORDER);
        color(d,"Menu.background",CHROME);
        color(d,"ToolTip.background",INPUT);
        color(d,"ToolTip.foreground",TEXT);
        color(d,"Tree.textBackground",PANEL);
        color(d,"Tree.textForeground",TEXT);
        color(d,"Tree.textInactiveText",MUTED);
        color(d,"TextField.caretForeground",ACCENT);
        d.put("Component.arc", 6); d.put("Button.arc", 6); d.put("TextComponent.arc", 6);
        d.put("Component.focusWidth", 1); d.put("Component.innerFocusWidth", 0);
        d.put("Button.margin", new Insets(5,10,5,10));
        d.put("ToggleButton.margin", new Insets(5,10,5,10));
        d.put("TextField.margin", new Insets(5,7,5,7));
        d.put("TabbedPane.tabHeight", 36); d.put("TabbedPane.showTabSeparators", false);
        d.put("Tree.rowHeight", 29); d.put("Tree.selectionArc", 5); d.put("Tree.wideSelection", true);
        d.put("ScrollBar.width", 11); d.put("SplitPane.dividerSize", 5);
        d.put("SplitPaneDivider.gripDotCount", 0);
        d.put("ToolBar.floatable", false); d.put("TitlePane.menuBarEmbedded", false);
        // Retain FlatLaf's platform fonts and scaling, including the system CJK fallback.
        return d;
    }

    private static void color(UIDefaults defaults, String key, Color value) { defaults.put(key, new ColorUIResource(value)); }

    static Border line(int top, int left, int bottom, int right) {
        return BorderFactory.createMatteBorder(top,left,bottom,right,BORDER);
    }

    static int scale(int value) { return com.formdev.flatlaf.util.UIScale.scale(value); }

    static Dimension size(int width,int height) { return new Dimension(scale(width),scale(height)); }

    static JPanel heading(String text) {
        var panel=new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(line(0,0,1,0),BorderFactory.createEmptyBorder(9,12,9,12)));
        var label=new JLabel(text); label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label); return panel;
    }
}
