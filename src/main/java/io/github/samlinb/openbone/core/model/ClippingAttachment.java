package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;

/**
 * Static unweighted polygon in slot-bone space. Derived triangles are cached,
 * not serialized.
 */
public final class ClippingAttachment implements Attachment {
    private final EntityId id, slotId, endSlotId;
    private final String name;
    private final List<Vec2> vertices;
    private final List<Integer> triangles;
    public static final Rgba DEFAULT_COLOR = Rgba.parse("ce3a3aff");
    private final Rgba color;

    public ClippingAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, EntityId endSlotId) {
        this(id, name, slotId, vertices, endSlotId, DEFAULT_COLOR);
    }

    public ClippingAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, EntityId endSlotId, Rgba color) {
        this.color = Objects.requireNonNull(color);
        this.id = Objects.requireNonNull(id);
        this.name = Names.require(name);
        this.slotId = Objects.requireNonNull(slotId);
        this.vertices = List.copyOf(vertices);
        this.endSlotId = endSlotId;
        this.triangles = MeshTopology.triangulate(this.vertices, this.vertices.size());
    }

    public EntityId id() {
        return id;
    }

    public Rgba color() { return color; }

    public String name() {
        return name;
    }

    public EntityId slotId() {
        return slotId;
    }

    public EntityId endSlotId() {
        return endSlotId;
    }

    public List<Vec2> vertices() {
        return vertices;
    }

    public List<Integer> triangles() {
        return triangles;
    }

    public ClippingAttachment withVertices(List<Vec2> v) {
        return new ClippingAttachment(id, name, slotId, v, endSlotId, color);
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ClippingAttachment c && id.equals(c.id) && name.equals(c.name)
                && slotId.equals(c.slotId) && vertices.equals(c.vertices) && Objects.equals(endSlotId, c.endSlotId) && color.equals(c.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, slotId, vertices, endSlotId, color);
    }
}
