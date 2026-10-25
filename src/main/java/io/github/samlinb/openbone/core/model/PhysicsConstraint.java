package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Setup parameters only. Influence order X/Y/rotate/scaleX/shearX; parameter
 * order
 * inertia/strength/damping/mass/wind/gravity/mix. Nominal integration is fixed
 * at 60 Hz.
 */
public record PhysicsConstraint(EntityId id, String name, int order, boolean enabled, EntityId boneId,
        List<Double> influence, double limit, List<Double> parameters) implements Constraint {
    public static final double STEP = 1.0 / 60, REFERENCE_SCALE = 100;

    public PhysicsConstraint {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(boneId);
        influence = List.copyOf(influence);
        parameters = List.copyOf(parameters);
        if (order < 0 || influence.size() != 5 || parameters.size() != 7)
            throw new IllegalArgumentException(m("physicsconstraint.0001"));
        for (double v : influence) {
            Finite.require(v, "physics influence");
            if (v < 0 || v > 1)
                throw new IllegalArgumentException(m("physicsconstraint.0002"));
        }
        Finite.require(limit, "physics limit");
        if (limit < 0 || limit > 1e6)
            throw new IllegalArgumentException(m("physicsconstraint.0003"));
        for (double v : parameters)
            Finite.require(v, "physics parameter");
        if (parameters.get(0) < 0 || parameters.get(0) > 1 || parameters.get(2) < 0 || parameters.get(2) > 1
                || parameters.get(6) < 0 || parameters.get(6) > 1)
            throw new IllegalArgumentException(m("physicsconstraint.0004"));
        double mass = parameters.get(3), strength = parameters.get(1);
        if (mass < .001 || mass > 1000 || strength < 0 || strength / mass * STEP * STEP > 1)
            throw new IllegalArgumentException(m("physicsconstraint.0005"));
        if (Math.abs(parameters.get(4)) > 10000 || Math.abs(parameters.get(5)) > 10000)
            throw new IllegalArgumentException(m("physicsconstraint.0006"));
    }

    public List<EntityId> boneIds() {
        return List.of(boneId);
    }

    public EntityId targetId() {
        return boneId;
    }

    public double inertia() {
        return parameters.get(0);
    }

    public double strength() {
        return parameters.get(1);
    }

    public double damping() {
        return parameters.get(2);
    }

    public double mass() {
        return parameters.get(3);
    }

    public double wind() {
        return parameters.get(4);
    }

    public double gravity() {
        return parameters.get(5);
    }

    public double mix() {
        return enabled ? parameters.get(6) : 0;
    }

    public PhysicsConstraint withName(String v) {
        return new PhysicsConstraint(id, v, order, enabled, boneId, influence, limit, parameters);
    }

    public PhysicsConstraint withOrder(int v) {
        return new PhysicsConstraint(id, name, v, enabled, boneId, influence, limit, parameters);
    }

    public PhysicsConstraint withEnabled(boolean v) {
        return new PhysicsConstraint(id, name, order, v, boneId, influence, limit, parameters);
    }

    public PhysicsConstraint withBone(EntityId v) {
        return new PhysicsConstraint(id, name, order, enabled, v, influence, limit, parameters);
    }

    public PhysicsConstraint withLimit(double v) {
        return new PhysicsConstraint(id, name, order, enabled, boneId, influence, v, parameters);
    }

    public PhysicsConstraint withInfluence(int n, double v) {
        var next = new ArrayList<>(influence);
        next.set(n, v);
        return new PhysicsConstraint(id, name, order, enabled, boneId, next, limit, parameters);
    }

    public PhysicsConstraint withParameter(int n, double v) {
        var next = new ArrayList<>(parameters);
        next.set(n, v);
        return new PhysicsConstraint(id, name, order, enabled, boneId, influence, limit, next);
    }
}
