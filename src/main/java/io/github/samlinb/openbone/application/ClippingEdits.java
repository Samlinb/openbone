package io.github.samlinb.openbone.application;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

public final class ClippingEdits {
    private ClippingEdits() {
    }

    public static ClippingAttachment clip(DocumentSnapshot d, EntityId id) {
        return d.attachments().stream().filter(a -> a.id().equals(id) && a instanceof ClippingAttachment)
                .map(a -> (ClippingAttachment) a).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(m("clippingedits.0001")));
    }

    public static DocumentSnapshot edit(DocumentSnapshot d, EntityId id, UnaryOperator<ClippingAttachment> operation) {
        return MeshEdits.replace(d, id, a -> operation.apply(clip(d, id)));
    }

    public static DocumentSnapshot vertex(DocumentSnapshot d, EntityId id, int index, Vec2 value) {
        var c = clip(d, id);
        var v = new ArrayList<>(c.vertices());
        v.set(index, value);
        return edit(d, id, a -> a.withVertices(v));
    }

    public static DocumentSnapshot remove(DocumentSnapshot d, EntityId id, int index) {
        var c = clip(d, id);
        var v = new ArrayList<>(c.vertices());
        v.remove(index);
        return edit(d, id, a -> a.withVertices(v));
    }

    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId id, int index, Vec2 target) {
        var c = clip(d, id);
        var slot = d.slots().stream().filter(s -> s.id().equals(c.slotId())).findFirst().orElseThrow();
        var point = d.evaluateWorld().bones().get(slot.boneId()).matrix().inverseOrThrow().apply(target.x(),
                target.y());
        return vertex(d, id, index, point);
    }

    public static DocumentSnapshot add(DocumentSnapshot d, Slot slot, ClippingAttachment clip, int index) {
        var next = ModelEdits.addSlot(d, slot, index);
        return ModelEdits.addAttachment(next, clip, true);
    }
}
