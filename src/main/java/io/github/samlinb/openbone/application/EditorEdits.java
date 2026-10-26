package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Shared numeric/handle commands; no UI or renderer dependency. Slot transforms
 * edit its bone.
 */
public final class EditorEdits {
    public enum Space {
        LOCAL, WORLD
    }

    public enum Tool {
        SELECT, CREATE, MOVE, ROTATE, SCALE
    }

    private EditorEdits() {
    }

    public static EntityId transformId(DocumentSnapshot d, EntityId id) {
        for (var c : d.constraints())
            if (c.id().equals(id))
                return c.targetBone(d.slots());
        return d.slots().stream().filter(s -> s.id().equals(id)).map(Slot::boneId).findFirst().orElse(id);
    }

    public static Transform local(DocumentSnapshot d, EntityId id) {
        id = transformId(d, id);
        for (var a : d.attachments())
            if (a.id().equals(id))
                return a instanceof RegionAttachment r ? r.local() : Transform.IDENTITY;
        return d.bone(id).local();
    }

    public static Affine2 parent(DocumentSnapshot d, EntityId selected) {
        EntityId id = transformId(d, selected);
        var pose = d.evaluateWorld();
        for (var a : d.attachments())
            if (a.id().equals(id)) {
                var s = d.slots().stream().filter(v -> v.id().equals(a.slotId())).findFirst().orElseThrow();
                return pose.bones().get(s.boneId()).matrix();
            }
        var b = d.bone(id);
        return b.parentId() == null ? Affine2.IDENTITY : pose.bones().get(b.parentId()).matrix();
    }

    public static Affine2 world(DocumentSnapshot d, EntityId id) {
        id = transformId(d, id);
        for (var b : d.bones()) if (b.id().equals(id))
            return BoneInheritance.world(b.inherit(), parent(d, id), b.local(), b.local().matrix());
        return parent(d, id).multiply(local(d, id).matrix());
    }

    private static Transform fromWorld(DocumentSnapshot d, EntityId id, Affine2 matrix) {
        id = transformId(d, id);
        for (var b : d.bones()) if (b.id().equals(id))
            return b.parentId() == null ? matrix.toTransformOrThrow() : BoneInheritance.local(b.inherit(), parent(d, id), matrix);
        return parent(d, id).inverseOrThrow().multiply(matrix).toTransformOrThrow();
    }

    public static DocumentSnapshot transform(DocumentSnapshot d, EntityId selected, Transform value, Space space) {
        EntityId id = transformId(d, selected);
        Transform t = space == Space.LOCAL ? value
                : fromWorld(d, id, value.matrix());
        for (var a : d.attachments())
            if (a.id().equals(id)) {
                if (!(a instanceof RegionAttachment))
                    throw new IllegalArgumentException(m("editoredits.0001"));
                return ModelEdits.editRegion(d, id,
                        r -> new RegionAttachment(r.id(), r.name(), r.slotId(), r.source(), t, r.width(), r.height()));
            }
        return ModelEdits.editBone(d, id, b -> b.withLocal(t));
    }

    public static DocumentSnapshot move(DocumentSnapshot d, EntityId id, Vec2 delta, Space space) {
        Transform t = local(d, id);
        Vec2 p;
        if (space == Space.LOCAL)
            p = new Vec2(t.x() + delta.x(), t.y() + delta.y());
        else {
            var w = world(d, id);
            p = parent(d, id).inverseOrThrow().apply(w.tx() + delta.x(), w.ty() + delta.y());
        }
        return transform(d, id, new Transform(p.x(), p.y(), t.rotation(), t.scaleX(), t.scaleY(), t.shearX(), t.shearY()), Space.LOCAL);
    }

    public static DocumentSnapshot rotate(DocumentSnapshot d, EntityId id, double degrees, Space space) {
        var t = local(d, id);
        if (space == Space.LOCAL)
            return transform(d, id, new Transform(t.x(), t.y(), t.rotation() + degrees, t.scaleX(), t.scaleY(), t.shearX(), t.shearY()), space);
        var w = world(d, id);
        var rotation = new Transform(0, 0, degrees, 1, 1).matrix();
        var linear = new Affine2(w.a(), w.b(), w.c(), w.d(), 0, 0);
        var next = rotation.multiply(linear);
        var local = fromWorld(d, id, new Affine2(next.a(), next.b(), next.c(), next.d(), w.tx(), w.ty()));
        return transform(d, id, local, Space.LOCAL);
    }

    public static DocumentSnapshot scale(DocumentSnapshot d, EntityId id, double sx, double sy, Space space) {
        var t = local(d, id);
        if (space == Space.LOCAL)
            return transform(d, id, new Transform(t.x(), t.y(), t.rotation(), t.scaleX() * sx, t.scaleY() * sy, t.shearX(), t.shearY()), space);
        var w = world(d, id);
        var local = fromWorld(d, id, new Affine2(sx * w.a(), sx * w.b(), sy * w.c(), sy * w.d(), w.tx(), w.ty()));
        return transform(d, id, local, Space.LOCAL);
    }

    public static DocumentSnapshot order(DocumentSnapshot d, EntityId slot, int offset) {
        var slots = new ArrayList<>(d.slots());
        int index = -1;
        for (int i = 0; i < slots.size(); i++)
            if (slots.get(i).id().equals(slot))
                index = i;
        if (index < 0)
            throw new IllegalArgumentException("Unknown slot");
        int next = Math.max(0, Math.min(slots.size() - 1, index + offset));
        Collections.swap(slots, index, next);
        return new DocumentSnapshot(d.bones(), slots, d.attachments(), d.animations(), d.constraints(), d.resources());
    }

    public static Bone create(DocumentSnapshot d, EntityId parent, Vec2 start, Vec2 end, String name) {
        var inv = (parent == null ? Affine2.IDENTITY : d.evaluateWorld().bones().get(parent).matrix()).inverseOrThrow();
        var p = inv.apply(start.x(), start.y());
        var q = inv.apply(end.x(), end.y());
        double dx = q.x() - p.x(), dy = q.y() - p.y();
        return new Bone(EntityId.create(), name, parent,
                new Transform(p.x(), p.y(), Math.toDegrees(Math.atan2(dy, dx)), 1, 1), Math.hypot(dx, dy));
    }
}
