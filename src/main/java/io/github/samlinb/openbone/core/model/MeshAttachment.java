package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Unweighted mesh in slot-bone local space; boundary first, followed by
 * interior vertices.
 * UV is image Y-down, [0,1]. Immutable topology and IDs survive undo and
 * serialization.
 */
public record MeshAttachment(EntityId id, String name, EntityId slotId, String source,
        List<Vec2> vertices, List<Vec2> uvs, int hull, List<Integer> triangles, List<List<Influence>> weights,
        boolean runtimeTopology, Rgba color)
        implements Attachment {
    public MeshAttachment(EntityId id, String name, EntityId slotId, String source,
            List<Vec2> vertices, List<Vec2> uvs, int hull, List<Integer> triangles, List<List<Influence>> weights) {
        this(id,name,slotId,source,vertices,uvs,hull,triangles,weights,false,Rgba.WHITE);
    }
    public MeshAttachment(EntityId id, String name, EntityId slotId, String source, List<Vec2> vertices, List<Vec2> uvs,
            int hull, List<Integer> triangles) {
        this(id, name, slotId, source, vertices, uvs, hull, triangles, List.of());
    }

    public MeshAttachment {
        Objects.requireNonNull(color,"attachment.color");
        Objects.requireNonNull(id);
        Names.require(name);
        Objects.requireNonNull(slotId);
        if (source == null || source.isBlank() || source.length() > 4096)
            throw new IllegalArgumentException("Invalid mesh image source");
        vertices = List.copyOf(vertices);
        uvs = List.copyOf(uvs);
        triangles = List.copyOf(triangles);
        weights = weights.stream().map(List::copyOf).toList();
        if (!weights.isEmpty() && weights.size() != vertices.size())
            throw new IllegalArgumentException(m("meshattachment.0001"));
        weights.forEach(Influence::validate);
        if (uvs.size() != vertices.size())
            throw new IllegalArgumentException(m("meshattachment.0002"));
        for (var uv : uvs)
            if (uv.x() < 0 || uv.x() > 1 || uv.y() < 0 || uv.y() > 1)
                throw new IllegalArgumentException(m("meshattachment.0003"));
        if(runtimeTopology) MeshTopology.validateRuntime(vertices,hull,triangles);
        else MeshTopology.validate(vertices, hull, triangles);
    }

    public boolean weighted() {
        return !weights.isEmpty();
    }

    public int deformSize() {
        return (weighted() ? weights.stream().mapToInt(List::size).sum() : vertices.size()) * 2;
    }

    public MeshAttachment withSource(String value) {
        return new MeshAttachment(id, name, slotId, value, vertices, uvs, hull, triangles, weights,runtimeTopology,color);
    }

    public MeshAttachment withWeights(List<List<Influence>> value) {
        return new MeshAttachment(id, name, slotId, source, vertices, uvs, hull, triangles, value,runtimeTopology,color);
    }
}
