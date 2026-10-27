package io.github.samlinb.openbone.application;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Offset-key edits for meshes and paths never overwrite Setup points or bone-local influence
 * points.
 */
public final class DeformEdits {
    private DeformEdits() {
    }

    public static List<Double> offsets(DocumentSnapshot d, EntityId clip, EntityId mesh, double time) {
        return AnimationSampler.deforms(d, clip, time, false).getOrDefault(mesh,
                Collections.nCopies(deformSize(d,mesh), 0.0));
    }

    public static DocumentSnapshot put(DocumentSnapshot d, EntityId clip, EntityId mesh, double time,
            List<Double> offsets) {
        Keyframe.seconds(time);
        if (offsets.size() != deformSize(d,mesh))
            throw new IllegalArgumentException(m("deformedits.0001"));
        return AnimationEdits.clip(d, clip, a -> {
            var tracks = new ArrayList<>(a.deforms());
            var old = tracks.stream().filter(t -> t.attachmentId().equals(mesh)).findFirst().orElse(null);
            var keys = new ArrayList<>(old == null ? List.<Keyframe>of() : old.keys());
            var existing = keys.stream().filter(k -> k.time() == time).findFirst().orElse(null);
            keys.removeIf(k -> k.time() == time);
            keys.add(new Keyframe(existing == null ? EntityId.create() : existing.id(), time, offsets,
                    existing == null ? Collections.nCopies(offsets.size(), Curve.LINEAR) : existing.curves()));
            keys.sort(Comparator.comparingDouble(Keyframe::time));
            var next = new DeformTimeline(mesh, keys);
            if (old == null)
                tracks.add(next);
            else
                tracks.set(tracks.indexOf(old), next);
            return new AnimationClip(a.id(), a.name(), a.timelines(), tracks, a.constraintTracks(), a.discrete());
        });
    }

    public static DocumentSnapshot moveWorld(DocumentSnapshot d, EntityId clip, double time, EntityId id, int vertex,
            Vec2 target) {
        var m = MeshEdits.mesh(d, id);
        var offsets = new ArrayList<>(offsets(d, clip, id, time));
        var pose = d.evaluateWorld(AnimationSampler.pose(d, clip, time, false),
                AnimationSampler.deforms(d, clip, time, false));
        if (!m.weighted()) {
            var slot = d.slots().stream().filter(s -> s.id().equals(m.slotId())).findFirst().orElseThrow();
            var p = pose.bones().get(slot.boneId()).matrix().inverseOrThrow().apply(target.x(), target.y());
            offsets.set(vertex * 2, p.x() - m.vertices().get(vertex).x());
            offsets.set(vertex * 2 + 1, p.y() - m.vertices().get(vertex).y());
        } else {
            var before = pose.meshes().get(id).vertices().get(vertex);
            int index = 0;
            for (int v = 0; v < vertex; v++)
                index += m.weights().get(v).size() * 2;
            for (var i : m.weights().get(vertex)) {
                var inverse = pose.bones().get(i.boneId()).matrix().inverseOrThrow();
                var p = inverse.apply(before.x(), before.y());
                var q = inverse.apply(target.x(), target.y());
                offsets.set(index, Finite.require(offsets.get(index) + q.x() - p.x(), "deform drag x"));
                index++;
                offsets.set(index, Finite.require(offsets.get(index) + q.y() - p.y(), "deform drag y"));
                index++;
            }
        }
        return put(d, clip, id, time, offsets);
    }

    public static String layout(MeshAttachment m) {
        return m.vertices().size() + ":" + m.hull() + ":" + m.triangles() + ":"
                + m.weights().stream().map(row -> row.stream().map(Influence::boneId).toList()).toList();
    }

    private static int deformSize(DocumentSnapshot document,EntityId id) {
        var attachment=document.attachment(id);
        if(attachment instanceof MeshAttachment mesh)return mesh.deformSize();
        if(attachment instanceof PathAttachment path)return path.deformSize();
        if(attachment instanceof ClippingAttachment clipping)return clipping.deformSize();
        throw new IllegalArgumentException(m("documentsnapshot.0026"));
    }
}
