package io.github.samlinb.openbone.desktop;

import java.nio.file.Path;

/** Per-user state stays outside installation and user projects. */
public final class UserState {
    private UserState() {
    }

    public static Path root() {
        String override = System.getProperty("openbone.userState");
        if (override != null)
            return Path.of(override).toAbsolutePath().normalize();
        String local = System.getenv("LOCALAPPDATA");
        return (local == null ? Path.of(System.getProperty("user.home"), ".openbone") : Path.of(local, "Openbone"))
                .toAbsolutePath().normalize();
    }
}
