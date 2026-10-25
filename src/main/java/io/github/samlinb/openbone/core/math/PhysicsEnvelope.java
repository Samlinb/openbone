package io.github.samlinb.openbone.core.math;

import io.github.samlinb.openbone.core.model.PhysicsConstraint;

/**
 * Whole fixed-tick horizon proof, not a time sampler. For q=k/m*dt² in [0,1]
 * and damping in [0,1], A=[[1-q,1],[-dq,d]] has |roots|<=1. Cayley-Hamilton
 * gives ||A^n||inf<=3n, including repeated roots, hence sum ||A^j||<=1.5 N².
 */
public final class PhysicsEnvelope {
    public static final int MAX_SECONDS = 600, MAX_STEPS = MAX_SECONDS * 60;

    private PhysicsEnvelope() {
    }

    /**
     * Greatest n with the published double tick time n/60 <= seconds. No epsilon
     * may consume an integration step at the representable instant before a tick.
     */
    public static int tickAt(double seconds) {
        Finite.require(seconds, "physics phase");
        if (seconds < 0 || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("physics phase outside 0–600");
        int tick = (int) Math.floor(seconds * 60);
        while (tick > 0 && seconds < tick / 60.)
            tick--;
        while (tick < MAX_STEPS && seconds >= (tick + 1) / 60.)
            tick++;
        return tick;
    }

    private static double upper(double... factors) {
        double result = 1;
        for (double factor : factors) {
            if (factor == 0)
                return 0;
            result = Math.nextUp(Finite.require(result * factor, "physics positive product bound"));
        }
        return result;
    }

    public record Bounds(double matrixNorm, double positionNorm, double xyOffset, double rotationOffset,
            double scaleOffset, double velocity) {
        public Bounds {
            for (double v : new double[] { matrixNorm, positionNorm, xyOffset, rotationOffset, scaleOffset, velocity })
                Finite.require(v, "physics whole-horizon envelope");
        }
    }

    public static Bounds whole(PhysicsConstraint p, double norm, double position, double length) {
        // The extra factor two covers double operation perturbations over at most 36000
        // steps (Gronwall with K<=3N and 32 ulps per affine update is <1.0001).
        if (p.mix() == 0)
            return new Bounds(norm, position, 0, 0, 0, 0);
        double gain = 3.0 * MAX_STEPS * MAX_STEPS, dt = PhysicsConstraint.STEP,
                force = Math.nextUp(Math.abs(p.wind()) + Math.abs(p.gravity())), nudge = upper(p.limit(), dt),
                m = Math.nextUp(dt / p.mass()), rounding = 16 * Double.MIN_VALUE;
        // Preserve multiplication order and absolute subnormal allowance: wind*100
        // may survive even if wind*dt*dt would first underflow to zero.
        double xy = upper(gain,
                Math.nextUp(2 * nudge + upper(force, PhysicsConstraint.REFERENCE_SCALE, m, dt) + rounding)),
                r = upper(gain,
                        Math.nextUp(2 * Math.PI + upper(force, length, 1 / PhysicsConstraint.REFERENCE_SCALE, m, dt)
                                + rounding)),
                scale = upper(gain, Math.nextUp(4 * nudge / 1e-6 + upper(force, m, dt) + rounding));
        double difference = Finite.require(2 * (position + xy), "physics previous input difference bound");
        Finite.require(length * norm + difference, "physics endpoint plus displacement bound");
        double outNorm = p.influence().get(4) > 0 ? upper(norm, Math.sqrt(2)) : norm;
        if (p.influence().get(3) > 0)
            outNorm = upper(outNorm, Math.nextUp(1 + upper(scale, p.mix(), p.influence().get(3))));
        double influence = Math.max(p.influence().get(0), p.influence().get(1)), outPosition = influence == 0 ? position
                : Math.nextUp(Finite.require(position + upper(Math.sqrt(2), xy, p.mix(), influence),
                        "physics position bound"));
        return new Bounds(outNorm, outPosition, xy, r, scale,
                Math.nextUp(Finite.require(Math.max(Math.max(xy, r), scale) / dt, "physics velocity bound")));
    }

    public static Bounds fromTrace(PhysicsConstraint p, double norm, double position,
            PhysicsTraceProof.Offsets states) {
        if (p.mix() == 0)
            return new Bounds(norm, position, 0, 0, 0, 0);
        Finite.require(states.rotation() * (p.influence().get(2) + p.influence().get(4)) * p.mix(),
                "physics sub-tick angular product");
        double outNorm = p.influence().get(4) > 0 ? upper(norm, Math.sqrt(2)) : norm;
        if (p.influence().get(3) > 0)
            outNorm = upper(outNorm, Math.nextUp(1 + upper(states.scale(), p.mix(), p.influence().get(3))));
        double influence = Math.max(p.influence().get(0), p.influence().get(1)),
                sum = influence == 0 ? position
                        : Finite.require(position + upper(Math.sqrt(2), states.xy(), p.mix(), influence),
                                "physics complete-trace sub-tick position"),
                outPosition = sum == 0 ? 0 : Math.nextUp(sum);
        return new Bounds(outNorm, outPosition, states.xy(), states.rotation(), states.scale(), states.velocity());
    }
}
