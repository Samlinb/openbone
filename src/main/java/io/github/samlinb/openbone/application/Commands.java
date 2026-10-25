package io.github.samlinb.openbone.application;

import java.util.List;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.model.*;

public final class Commands {
    private Commands() {}
    public static EditCommand model(UnaryOperator<DocumentSnapshot> edit) { return state -> state.withModel(edit.apply(state.model())); }
    /** Publish only the final result: any failing member leaves history, selection and model untouched. */
    public static EditCommand transaction(List<EditCommand> commands) {
        List<EditCommand> copy = List.copyOf(commands);
        return before -> {
            EditorState next = before;
            for (EditCommand command : copy) next = command.apply(next);
            return next;
        };
    }
}
