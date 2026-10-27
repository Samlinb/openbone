package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Binding captures bone-local points from the current Setup skin. Singular
 * inverse is refused.
 * Reference polygon is an editor rest-topology certificate; rendering uses
 * influence points.
 */
public final class WeightEdits {
    private WeightEdits() {
    }

    public record Binding(EntityId boneId, double weight) {
    }

    public static DocumentSnapshot bind(DocumentSnapshot d, EntityId id, List<Binding> bindings) {
        var m = MeshEdits.mesh(d, id);
        var pose = d.evaluateWorld();
        var template = new ArrayList<Influence>();
        for (var b : bindings) {
            d.bone(b.boneId());
            template.add(new Influence(b.boneId(), new Vec2(0, 0), b.weight()));
        }
        var normalized = Influence.normalize(template);
        List<List<Influence>> weights = new ArrayList<>();
        for (var point : pose.meshes().get(id).vertices()) {
            var influences = new ArrayList<Influence>();
            for (var b : normalized) {
                var local = pose.bones().get(b.boneId()).matrix().inverseOrThrow().apply(point.x(), point.y());
                influences.add(new Influence(b.boneId(), local, b.weight()));
            }
            weights.add(influences);
        }
        return MeshEdits.replace(d, id, a -> m.withWeights(weights));
    }

    public static DocumentSnapshot vertex(DocumentSnapshot d, EntityId id, int vertex, List<Influence> influences) {
        var m = MeshEdits.mesh(d, id);
        if (!m.weighted())
            throw new IllegalArgumentException(m("weightedits.0001"));
        var normalized = Influence.normalize(influences);
        var all = new ArrayList<>(m.weights());
        all.set(vertex, normalized);
        return MeshEdits.replace(d, id, a -> m.withWeights(all));
    }

    public static DocumentSnapshot unbind(DocumentSnapshot d, EntityId id) {
        var m = MeshEdits.mesh(d, id);
        if (!m.weighted())
            return d;
        var pose = d.evaluateWorld();
        var slot = d.slots().stream().filter(s -> s.id().equals(m.slotId())).findFirst().orElseThrow();
        var inverse = pose.bones().get(slot.boneId()).matrix().inverseOrThrow();
        var vertices = pose.meshes().get(id).vertices().stream().map(p -> inverse.apply(p.x(), p.y())).toList();
        return MeshEdits.replace(d, id, a -> new MeshAttachment(m.id(), m.name(), m.slotId(), m.source(), vertices,
                m.uvs(), m.hull(), m.triangles(),List.of(),m.runtimeTopology(),m.color()));
    }

    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId id, int index, Vec2 target) {
        var m = MeshEdits.mesh(d, id);
        var pose = d.evaluateWorld();
        var before = pose.meshes().get(id).vertices().get(index);
        var influences = new ArrayList<Influence>();
        for (var i : m.weights().get(index)) {
            var inverse = pose.bones().get(i.boneId()).matrix().inverseOrThrow();
            var p = inverse.apply(before.x(), before.y());
            var q = inverse.apply(target.x(), target.y());
            influences.add(new Influence(i.boneId(),
                    new Vec2(i.point().x() + q.x() - p.x(), i.point().y() + q.y() - p.y()), i.weight()));
        }
        // Already normalized: dragging must not drift persisted weights through
        // repeated normalization.
        var all = new ArrayList<>(m.weights());
        all.set(index, List.copyOf(influences));
        return MeshEdits.replace(d, id, a -> m.withWeights(all));
    }
}
