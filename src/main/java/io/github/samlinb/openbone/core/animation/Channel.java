package io.github.samlinb.openbone.core.animation;

import static io.github.samlinb.openbone.i18n.M.m;

public enum Channel {
    TRANSLATE(2, m("channel.0001")), ROTATE(1, m("channel.0002")), SCALE(2, m("channel.0003")), SHEAR(2, m("channel.shear"));

    private final int components;
    private final String label;

    Channel(int components, String label) {
        this.components = components;
        this.label = label;
    }

    public int components() {
        return components;
    }

    @Override
    public String toString() {
        return label;
    }
}
