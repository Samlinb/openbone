package io.github.samlinb.openbone.core.math;

import java.util.*;
import io.github.samlinb.openbone.core.model.EntityId;

/**
 * One disposable solve transaction. Commit states only after evaluation
 * succeeds.
 */
public final class PhysicsFrame {
    private final PhysicsSolver.Mode mode;
    private final Map<EntityId, PhysicsSolver.State> states;
    private final int tick;

    public PhysicsFrame(PhysicsSolver.Mode mode, Map<EntityId, PhysicsSolver.State> previous) {
        this(mode, previous,
                mode == PhysicsSolver.Mode.UPDATE
                        ? previous.values().stream().mapToInt(PhysicsSolver.State::lastTick).max().orElse(0) + 1
                        : 0);
    }

    public PhysicsFrame(PhysicsSolver.Mode mode, Map<EntityId, PhysicsSolver.State> previous, int tick) {
        this.mode = Objects.requireNonNull(mode);
        if (tick < 0 || tick > PhysicsEnvelope.MAX_STEPS)
            throw new IllegalArgumentException("physics frame tick outside 0–36000");
        this.tick = tick;
        this.states = new HashMap<>(previous);
    }

    public int tick() {
        return tick;
    }

    public PhysicsSolver.Mode mode() {
        return mode;
    }

    public PhysicsSolver.State state(EntityId id) {
        return states.getOrDefault(id, PhysicsSolver.State.ZERO);
    }

    public void put(EntityId id, PhysicsSolver.State value) {
        states.put(id, value);
    }

    public Map<EntityId, PhysicsSolver.State> snapshot() {
        return Map.copyOf(states);
    }
}
