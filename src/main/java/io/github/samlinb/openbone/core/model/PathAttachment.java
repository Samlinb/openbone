package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Unweighted 4.2 path layout, including unused open end handles and lengths.
 */
public record PathAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, boolean closed,
        boolean constantSpeed, List<Double> lengths) implements Attachment {
    public PathAttachment {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(slotId);
        vertices = List.copyOf(vertices);
        lengths = List.copyOf(lengths);
        if (vertices.size() % 3 != 0 || vertices.size() < (closed ? 3 : 6) || vertices.size() > 510)
            throw new IllegalArgumentException(m("pathattachment.0001"));
        if (lengths.size() != vertices.size() / 3)
            throw new IllegalArgumentException(m("pathattachment.0002"));
        double prior = 0;
        int index = 0;
        for (double v : lengths) {
            Finite.require(v, "path cumulative length");
            if (v < 0 || index < vertices.size() / 3 - (closed ? 0 : 1) && v < prior)
                throw new IllegalArgumentException(m("pathattachment.0003"));
            prior = v;
            index++;
        }
    }

    public PathAttachment withVertices(List<Vec2> v) {
        return new PathAttachment(id, name, slotId, v, closed, constantSpeed, lengths);
    }

    public PathAttachment withName(String v) {
        return new PathAttachment(id, v, slotId, vertices, closed, constantSpeed, lengths);
    }

    public PathAttachment withOptions(boolean c, boolean speed) {
        return new PathAttachment(id, name, slotId, vertices, c, speed, lengths);
    }

    public PathAttachment withLengths(List<Double> v) {
        return new PathAttachment(id, name, slotId, vertices, closed, constantSpeed, v);
    }

    /**
     * Canonical cubic chain p0,c1,c2,p1,..., independent of the attachment storage
     * layout.
     */
    public List<Vec2> chain(List<Vec2> points) {
        var result = new ArrayList<>(points.subList(1, closed ? points.size() : points.size() - 1));
        if (closed) {
            result.add(points.get(0));
            result.add(points.get(1));
        }
        return List.copyOf(result);
    }

    public int curveCount() {
        return vertices.size() / 3 - (closed ? 0 : 1);
    }
}
