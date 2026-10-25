package io.github.samlinb.openbone.core.model;

import java.util.List;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Stable setup references. Evaluation state never belongs in the saved
 * constraint.
 */
public sealed interface Constraint permits IkConstraint, TransformConstraint, PathConstraint, PhysicsConstraint {
    EntityId id();

    String name();

    int order();

    boolean enabled();

    List<EntityId> boneIds();

    EntityId targetId();

    default EntityId targetBone(List<Slot> slots) {
        return this instanceof PathConstraint
                ? slots.stream().filter(s -> s.id().equals(targetId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(m("constraint.0001"))).boneId()
                : targetId();
    }
}
