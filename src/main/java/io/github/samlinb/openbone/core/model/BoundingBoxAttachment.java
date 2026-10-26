package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;

/** Non-textured polygon used for bounds/hit testing, never for clipping the draw list. */
public record BoundingBoxAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, Rgba color)
        implements Attachment {
    public static final Rgba DEFAULT_COLOR = Rgba.parse("60f000ff");
    public BoundingBoxAttachment {
        Objects.requireNonNull(id); Names.require(name); Objects.requireNonNull(slotId); Objects.requireNonNull(color);
        vertices = List.copyOf(vertices);
        MeshTopology.triangulate(vertices, vertices.size());
    }
    public List<Integer> triangles() { return MeshTopology.triangulate(vertices, vertices.size()); }
}
