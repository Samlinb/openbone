package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;

/** Single-threaded application service. UI must own it on EDT; IO publishes only after success.
 * Snapshot history restores model AND selection. Saved content, not stack depth, determines dirty. */
public final class CommandHistory {
    private record Entry(String label, EditorState before, EditorState after) {}
    private final List<Entry> entries = new ArrayList<>();
    private int cursor;
    private EditorState state;
    private DocumentSnapshot saved;
    private EditorState previewBase;
    private String previewLabel;
    private boolean applying;

    public CommandHistory(DocumentSnapshot initial) { reset(initial); }
    public EditorState state() { return state; }
    public boolean isDirty() { return !state.model().equals(saved); }
    public boolean isPreviewing() { return previewBase != null; }
    public int undoCount() { return cursor; }
    public int redoCount() { return entries.size()-cursor; }
    public Optional<String> undoLabel() { return cursor == 0 ? Optional.empty() : Optional.of(entries.get(cursor-1).label()); }
    public void select(Selection selection) {
        idle(); state = new EditorState(state.model(), selection,state.keySelection());
    }
    public void selectKeys(Set<io.github.samlinb.openbone.core.model.EntityId> keys){idle();state=new EditorState(state.model(),state.selection(),keys);}
    public void execute(String label, EditCommand command) {
        idle(); requireLabel(label);
        EditorState next = apply(command, state);
        publish(label, state, next);
    }
    private EditorState apply(EditCommand command, EditorState base) {
        applying = true;
        try { return Objects.requireNonNull(command.apply(base), "command result"); }
        finally { applying = false; }
    }
    private void publish(String label, EditorState before, EditorState after) {
        if (!before.model().equals(after.model())) {
            entries.subList(cursor, entries.size()).clear();
            entries.add(new Entry(label, before, after)); cursor++;
        }
        state = after;
    }
    public boolean undo() {
        idle(); if (cursor == 0) return false;
        state = entries.get(--cursor).before(); return true;
    }
    public boolean redo() {
        idle(); if (cursor == entries.size()) return false;
        state = entries.get(cursor++).after(); return true;
    }
    public void beginPreview(String label) {
        idle(); requireLabel(label); previewBase = state; previewLabel = label;
    }
    /** Absolute edit relative to begin, not accumulated deltas. Failed update retains last valid preview. */
    public void updatePreview(EditCommand absoluteEdit) {
        previewRequired(); state = apply(absoluteEdit, previewBase);
    }
    public void commitPreview() {
        previewRequired(); EditorState before = previewBase; String label = previewLabel;
        previewBase = null; previewLabel = null; publish(label, before, state);
    }
    public void cancelPreview() {
        previewRequired(); state = previewBase; previewBase = null; previewLabel = null;
    }
    public void markSaved() { idle(); saved = state.model(); }
    /** An imported document has no saved project yet. Undo cannot make it a saved file. */
    public void markUnsaved() { idle(); saved=null; }
    public void reset(DocumentSnapshot model) {
        idle(); EditorState next = EditorState.of(model);
        entries.clear(); cursor = 0; state = next; saved = model;
    }
    /** IO must refuse saving/opening during preview, before touching files. */
    public void requireIdle() { idle(); }
    private void idle() {
        if (applying) throw new IllegalStateException("Reentrant command/history mutation");
        if (previewBase != null) throw new IllegalStateException("Finish or cancel active preview first");
    }
    private void previewRequired() {
        if (applying) throw new IllegalStateException("Reentrant preview mutation");
        if (previewBase == null) throw new IllegalStateException("No active preview");
    }
    private static void requireLabel(String label) {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Command label required");
    }
}
