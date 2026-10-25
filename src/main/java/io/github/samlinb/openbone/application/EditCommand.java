package io.github.samlinb.openbone.application;

/**
 * Must return a new validated state without externally visible side effects.
 */
@FunctionalInterface
public interface EditCommand {
    EditorState apply(EditorState before);
}
