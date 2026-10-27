package io.github.samlinb.openbone.application;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Pure mesh transactions. Setup changes validate complete geometry before
 * publication.
 */
public final class MeshEdits {
    private MeshEdits() {
    }

    public static MeshAttachment mesh(DocumentSnapshot d, EntityId id) {
        return d.attachments().stream().filter(a -> a.id().equals(id) && a instanceof MeshAttachment)
                .map(a -> (MeshAttachment) a).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(m("meshedits.0001")));
    }

    public static DocumentSnapshot replace(DocumentSnapshot d, EntityId id, UnaryOperator<Attachment> edit) {
        if (d.attachments().stream().noneMatch(a -> a.id().equals(id)))
            throw new IllegalArgumentException("Unknown attachment");
        var next = d.attachments().stream().map(a -> a.id().equals(id) ? edit.apply(a) : a).toList();
        var previous = d.attachments().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        var replacement = next.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        if (d.animations().stream().flatMap(a -> a.deforms().stream()).anyMatch(t -> t.attachmentId().equals(id))) {
            if (!(previous instanceof MeshAttachment p) || !(replacement instanceof MeshAttachment n)
                    || !DeformEdits.layout(p).equals(DeformEdits.layout(n)))
                throw new IllegalArgumentException(m("meshedits.0002"));
        }
        if (next.stream().noneMatch(a -> a.id().equals(id)))
            throw new IllegalArgumentException("Attachment identity cannot change");
        var resources = d.resources();
        if (previous instanceof MeshAttachment p && replacement instanceof MeshAttachment n
                && (p.vertices().size() != n.vertices().size() || p.hull() != n.hull() || !p.triangles().equals(n.triangles())))
            resources = resources.withMetadata(resources.metadata().withoutEdges(id));
        return new DocumentSnapshot(d.bones(), d.slots(), next, d.animations(), d.constraints(), resources);
    }

    public static DocumentSnapshot convert(DocumentSnapshot d, EntityId id) {
        return replace(d, id, a -> {
            if (!(a instanceof RegionAttachment r))
                throw new IllegalArgumentException(m("meshedits.0003"));
            double x = r.width() / 2, y = r.height() / 2;
            var t = r.local().matrix();
            var points = List.of(t.apply(-x, -y), t.apply(-x, y), t.apply(x, y), t.apply(x, -y));
            return new MeshAttachment(r.id(), r.name(), r.slotId(), r.source(), points,
                    List.of(new Vec2(0, 1), new Vec2(0, 0), new Vec2(1, 0), new Vec2(1, 1)), 4,
                    MeshTopology.triangulate(points, 4),List.of(),false,r.color());
        });
    }

    public static DocumentSnapshot geometry(DocumentSnapshot d, EntityId id, List<Vec2> vertices, List<Vec2> uvs,
            int hull, List<Integer> triangles) {
        var m = mesh(d, id);
        if(m.runtimeTopology() && (m.vertices().size()!=vertices.size() || m.hull()!=hull || !m.triangles().equals(triangles)))
            throw new IllegalArgumentException(m("meshedits.runtimeTopology"));
        if (m.weighted() && (!m.vertices().equals(vertices) || m.hull() != hull))
            throw new IllegalArgumentException(m("meshedits.0004"));
        return replace(d, id, a -> new MeshAttachment(m.id(), m.name(), m.slotId(), m.source(), vertices, uvs, hull,
                triangles, m.weights(),m.runtimeTopology(),m.color()));
    }

    public static DocumentSnapshot vertex(DocumentSnapshot d, EntityId id, int index, Vec2 position, Vec2 uv) {
        var m = mesh(d, id);
        var v = new ArrayList<>(m.vertices());
        var u = new ArrayList<>(m.uvs());
        v.set(index, position);
        u.set(index, uv);
        return geometry(d, id, v, u, m.hull(), m.triangles());
    }

    public static DocumentSnapshot insert(DocumentSnapshot d, EntityId id, int index, Vec2 position, Vec2 uv,
            boolean boundary) {
        var m = mesh(d, id);
        if (boundary && (index < 0 || index > m.hull())
                || !boundary && (index < m.hull() || index > m.vertices().size()))
            throw new IllegalArgumentException(m("meshedits.0005"));
        var v = new ArrayList<>(m.vertices());
        var u = new ArrayList<>(m.uvs());
        v.add(index, position);
        u.add(index, uv);
        int hull = m.hull() + (boundary ? 1 : 0);
        return geometry(d, id, v, u, hull, MeshTopology.triangulate(v, hull));
    }

    public static DocumentSnapshot remove(DocumentSnapshot d, EntityId id, int index) {
        var m = mesh(d, id);
        var v = new ArrayList<>(m.vertices());
        var u = new ArrayList<>(m.uvs());
        v.remove(index);
        u.remove(index);
        int hull = m.hull() - (index < m.hull() ? 1 : 0);
        return geometry(d, id, v, u, hull, MeshTopology.triangulate(v, hull));
    }

    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId id, int index, Vec2 target) {
        var m = mesh(d, id);
        var slot = d.slots().stream().filter(s -> s.id().equals(m.slotId())).findFirst().orElseThrow();
        if (m.weighted())
            return WeightEdits.moveWorld(d, id, index, target);
        var p = d.evaluateWorld().bones().get(slot.boneId()).matrix().inverseOrThrow().apply(target.x(), target.y());
        return vertex(d, id, index, p, m.uvs().get(index));
    }

    public static Attachment source(Attachment a, String source) {
        if (a instanceof MeshAttachment m)
            return m.withSource(source);
        var r = (RegionAttachment) a;
        return new RegionAttachment(r.id(), r.name(), r.slotId(), source, r.local(), r.width(), r.height(),r.color());
    }
}
