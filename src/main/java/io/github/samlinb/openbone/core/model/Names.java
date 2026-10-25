package io.github.samlinb.openbone.core.model;

final class Names {
    private Names() {
    }

    static String require(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 256)
            throw new IllegalArgumentException("Name must contain 1–256 characters without surrounding whitespace");
        return name;
    }
}
