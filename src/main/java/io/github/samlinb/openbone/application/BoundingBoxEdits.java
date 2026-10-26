package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

public final class BoundingBoxEdits {
    private BoundingBoxEdits() {}
    public static BoundingBoxAttachment box(DocumentSnapshot d, EntityId id) {
        return (BoundingBoxAttachment)d.attachment(id);
    }
    public static DocumentSnapshot vertices(DocumentSnapshot d, EntityId id, List<Vec2> vertices) {
        return MeshEdits.replace(d, id, a -> {
            var b = (BoundingBoxAttachment)a;
            return new BoundingBoxAttachment(b.id(), b.name(), b.slotId(), vertices, b.color());
        });
    }
    public static DocumentSnapshot vertex(DocumentSnapshot d, EntityId id, int index, Vec2 point) {
        var vertices = new ArrayList<>(box(d,id).vertices()); vertices.set(index,point);
        return vertices(d,id,vertices);
    }
    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId id, int index, Vec2 point) {
        var b = box(d,id);
        var slot = d.slots().stream().filter(s -> s.id().equals(b.slotId())).findFirst().orElseThrow();
        var local = d.evaluateWorld().bones().get(slot.boneId()).matrix().inverseOrThrow().apply(point.x(),point.y());
        return vertex(d,id,index,local);
    }
}
