package io.github.samlinb.openbone.application;

import java.util.*;
import java.util.function.UnaryOperator;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Shared constraint commands: construction and all validation precede
 * publication.
 */
public final class ConstraintEdits {
    private ConstraintEdits() {
    }

    public static DocumentSnapshot add(DocumentSnapshot d, Constraint value) {
        var next = new ArrayList<>(d.constraints());
        next.add(value);
        return d.withConstraints(next);
    }

    public static DocumentSnapshot editIk(DocumentSnapshot d, EntityId id, UnaryOperator<IkConstraint> operation) {
        if (!(d.constraint(id) instanceof IkConstraint))
            throw new IllegalArgumentException(m("constraintedits.0001"));
        var next = d.constraints().stream().map(c -> c.id().equals(id) ? operation.apply((IkConstraint) c) : c)
                .toList();
        if (next.stream().noneMatch(c -> c.id().equals(id)))
            throw new IllegalArgumentException(m("constraintedits.0002"));
        return d.withConstraints(next);
    }

    public static DocumentSnapshot editTransform(DocumentSnapshot d, EntityId id,
            UnaryOperator<TransformConstraint> operation) {
        if (!(d.constraint(id) instanceof TransformConstraint))
            throw new IllegalArgumentException(m("constraintedits.0003"));
        var next = d.constraints().stream().map(c -> c.id().equals(id) ? operation.apply((TransformConstraint) c) : c)
                .toList();
        if (next.stream().noneMatch(c -> c.id().equals(id)))
            throw new IllegalArgumentException(m("constraintedits.0002"));
        return d.withConstraints(next);
    }

    public static DocumentSnapshot editPath(DocumentSnapshot d, EntityId id, UnaryOperator<PathConstraint> operation) {
        if (!(d.constraint(id) instanceof PathConstraint))
            throw new IllegalArgumentException(m("constraintedits.0004"));
        var next = d.constraints().stream().map(c -> c.id().equals(id) ? operation.apply((PathConstraint) c) : c)
                .toList();
        if (next.stream().noneMatch(c -> c.id().equals(id)))
            throw new IllegalArgumentException(m("constraintedits.0002"));
        return d.withConstraints(next);
    }

    public static DocumentSnapshot editPhysics(DocumentSnapshot d, EntityId id,
            UnaryOperator<PhysicsConstraint> operation) {
        if (!(d.constraint(id) instanceof PhysicsConstraint))
            throw new IllegalArgumentException(m("constraintedits.0005"));
        var next = d.constraints().stream().map(c -> c.id().equals(id) ? operation.apply((PhysicsConstraint) c) : c)
                .toList();
        if (next.stream().noneMatch(c -> c.id().equals(id)))
            throw new IllegalArgumentException(m("constraintedits.0002"));
        return d.withConstraints(next);
    }
}
