package io.github.samlinb.openbone.application;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

public final class PathEdits {
    private PathEdits() {
    }

    public static PathAttachment path(DocumentSnapshot d, EntityId id) {
        return d.attachments().stream().filter(a -> a.id().equals(id) && a instanceof PathAttachment)
                .map(a -> (PathAttachment) a).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(m("pathedits.0001")));
    }

    public static DocumentSnapshot edit(DocumentSnapshot d, EntityId id, UnaryOperator<PathAttachment> f) {
        return MeshEdits.replace(d, id, a -> f.apply(path(d, id)));
    }

    public static DocumentSnapshot vertex(DocumentSnapshot d, EntityId id, int index, Vec2 point) {
        var p = path(d, id);
        var v = new ArrayList<>(p.vertices());
        v.set(index, point);
        return edit(d, id, a -> a.withVertices(v));
    }

    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId id, int index, Vec2 target) {
        var p = path(d, id);
        var slot = d.slots().stream().filter(s -> s.id().equals(p.slotId())).findFirst().orElseThrow();
        var local = d.evaluateWorld().bones().get(slot.boneId()).matrix().inverseOrThrow().apply(target.x(),
                target.y());
        return vertex(d, id, index, local);
    }
}
