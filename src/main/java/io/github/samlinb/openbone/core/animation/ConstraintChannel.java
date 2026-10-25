package io.github.samlinb.openbone.core.animation;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Absolute animated parameters. References, order, modes and offsets remain
 * Setup data.
 */
public enum ConstraintChannel {
    IK(5), TRANSFORM_MIX(6), PATH_POSITION(1), PATH_SPACING(1), PATH_MIX(3), PHYSICS_INERTIA(1), PHYSICS_STRENGTH(1),
    PHYSICS_DAMPING(1), PHYSICS_MASS(1), PHYSICS_WIND(1), PHYSICS_GRAVITY(1), PHYSICS_MIX(1);

    private final int components;

    ConstraintChannel(int components) {
        this.components = components;
    }

    public int components() {
        return components;
    }

    public int physicsParameter() {
        return ordinal() >= PHYSICS_INERTIA.ordinal() ? ordinal() - PHYSICS_INERTIA.ordinal() : -1;
    }

    public List<String> labels() {
        return switch (this) {
            case IK -> List.of("Mix", "Softness", m("constraintchannel.0001"), m("constraintchannel.0002"),
                    m("constraintchannel.0003"));
            case TRANSFORM_MIX -> List.of(m("constraintchannel.0004"), "Mix X", "Mix Y", m("constraintchannel.0005"),
                    m("constraintchannel.0006"), "Mix Shear Y");
            case PATH_POSITION -> List.of(m("constraintchannel.0007"));
            case PATH_SPACING -> List.of(m("constraintchannel.0008"));
            case PATH_MIX -> List.of(m("constraintchannel.0004"), "Mix X", "Mix Y");
            default -> List.of(new String[] { m("constraintchannel.0009"), m("constraintchannel.0010"),
                    m("constraintchannel.0011"), m("constraintchannel.0012"), m("constraintchannel.0013"),
                    m("constraintchannel.0014"), "Mix" }[physicsParameter()]);
        };
    }

    public boolean discrete(int component) {
        return this == IK && component >= 2;
    }

    public boolean supports(Constraint c) {
        return switch (this) {
            case IK -> c instanceof IkConstraint;
            case TRANSFORM_MIX -> c instanceof TransformConstraint;
            case PATH_POSITION, PATH_SPACING, PATH_MIX -> c instanceof PathConstraint;
            default -> c instanceof PhysicsConstraint;
        };
    }

    public List<Double> values(Constraint c) {
        if (!supports(c))
            throw new IllegalArgumentException(m("constraintchannel.0015"));
        return switch (this) {
            case IK -> {
                var p = (IkConstraint) c;
                yield List.of(p.mix(), p.softness(), p.bendPositive() ? 1. : 0., p.compress() ? 1. : 0.,
                        p.stretch() ? 1. : 0.);
            }
            case TRANSFORM_MIX -> ((TransformConstraint) c).mix();
            case PATH_POSITION -> List.of(((PathConstraint) c).position());
            case PATH_SPACING -> List.of(((PathConstraint) c).spacing());
            case PATH_MIX -> {
                var p = (PathConstraint) c;
                yield List.of(p.mixRotate(), p.mixX(), p.mixY());
            }
            default -> List.of(((PhysicsConstraint) c).parameters().get(physicsParameter()));
        };
    }

    public Constraint apply(Constraint c, List<Double> v) {
        if (!supports(c) || v.size() != components)
            throw new IllegalArgumentException(m("constraintchannel.0016"));
        for (int i = 0; i < v.size(); i++)
            if (discrete(i) && v.get(i) != 0 && v.get(i) != 1)
                throw new IllegalArgumentException(m("constraintchannel.0017"));
        return switch (this) {
            case IK -> {
                var p = (IkConstraint) c;
                yield new IkConstraint(p.id(), p.name(), p.order(), p.enabled(), p.boneIds(), p.targetId(), v.get(0),
                        v.get(1), v.get(2) == 1, v.get(3) == 1, v.get(4) == 1, p.uniform());
            }
            case TRANSFORM_MIX -> {
                var p = (TransformConstraint) c;
                yield new TransformConstraint(p.id(), p.name(), p.order(), p.enabled(), p.boneIds(), p.targetId(),
                        p.local(), p.relative(), v, p.offset());
            }
            case PATH_POSITION, PATH_SPACING -> {
                var p = (PathConstraint) c;
                yield p.withValue(this == PATH_POSITION ? 0 : 1, v.get(0));
            }
            case PATH_MIX -> {
                var p = (PathConstraint) c;
                yield p.withValue(3, v.get(0)).withValue(4, v.get(1)).withValue(5, v.get(2));
            }
            default -> ((PhysicsConstraint) c).withParameter(physicsParameter(), v.get(0));
        };
    }
}
