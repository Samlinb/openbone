package io.github.samlinb.openbone.io;

import java.io.IOException;

public final class ProjectFormatException extends IOException {
    public ProjectFormatException(String message) { super(message); }
    public ProjectFormatException(String message, Throwable cause) { super(message, cause); }
}
