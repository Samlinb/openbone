package io.github.samlinb.openbone.core.math;

import io.github.samlinb.openbone.core.model.PhysicsConstraint;

/**
 * Independent fixed-step spring. All state is returned immutably; failed bulk
 * replay never publishes a partially integrated state. Integer clocks avoid
 * drift.
 */
public final class PhysicsSolver {
  public enum Mode {
    NONE, RESET, UPDATE, POSE
  }

  public record State(boolean initialized, double ux, double uy, double cx, double cy, double tx, double ty,
      double xOffset, double xVelocity, double yOffset, double yVelocity, double rotationOffset,
      double rotationVelocity, double scaleOffset, double scaleVelocity, int steps, int lastTick, int pendingTicks) {
    public State {
      for (double v : new double[] { ux, uy, cx, cy, tx, ty, xOffset, xVelocity, yOffset, yVelocity, rotationOffset,
          rotationVelocity, scaleOffset, scaleVelocity })
        Finite.require(v, "physics state");
      if (steps < 0 || steps > PhysicsEnvelope.MAX_STEPS || lastTick < 0 || lastTick > PhysicsEnvelope.MAX_STEPS
          || pendingTicks < 0 || pendingTicks > PhysicsEnvelope.MAX_STEPS)
        throw new IllegalArgumentException("physics state clock outside fixed replay horizon");
    }

    public static final State ZERO = new State(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public record Result(Affine2 matrix, State state) {
  }

  private PhysicsSolver() {
  }

  private static double clamp(double v, double limit) {
    return Math.max(-limit, Math.min(limit, v));
  }

  private static double turn(double angle) {
    double wrapped = Math.IEEEremainder(Finite.require(angle, "physics angle input"), 2 * Math.PI);
    return wrapped == -Math.PI ? Math.PI : wrapped;
  }

  public static Result apply(PhysicsConstraint p, Affine2 input, double length, State previous, Mode mode) {
    return apply(p, input, length, previous, mode,
        mode == Mode.UPDATE ? Math.addExact(previous.lastTick(), 1) : mode == Mode.RESET ? 0 : previous.lastTick());
  }

  public static Result apply(PhysicsConstraint p, Affine2 input, double length, State previous, Mode mode, int tick) {
    if (mode == Mode.NONE)
      return new Result(input, previous);
    if (tick < 0 || tick > PhysicsEnvelope.MAX_STEPS)
      throw new IllegalArgumentException("physics tick outside 0–36000");
    // A zero mix returns BEFORE lastTick/pending advance, as in the target's clock.
    if (p.mix() == 0)
      return new Result(input, previous);
    State state = mode == Mode.RESET ? State.ZERO : previous;
    boolean x = p.influence().get(0) > 0, y = p.influence().get(1) > 0,
        angular = p.influence().get(2) > 0 || p.influence().get(4) > 0, scale = p.influence().get(3) > 0;
    double ux = state.ux, uy = state.uy, cx = state.cx, cy = state.cy, tx = state.tx, ty = state.ty, xo = state.xOffset,
        xv = state.xVelocity, yo = state.yOffset, yv = state.yVelocity, ro = state.rotationOffset,
        rv = state.rotationVelocity, so = state.scaleOffset, sv = state.scaleVelocity;
    int steps = state.steps, last = state.lastTick, pending = state.pendingTicks;
    double wx = input.tx(), wy = input.ty(), a = input.a(), b = input.b(), c = input.c(), d = input.d(), mix = p.mix(),
        dt = PhysicsConstraint.STEP;
    if (mode != Mode.POSE) {
      int delta = mode == Mode.RESET ? 0 : tick - last;
      if (delta < 0)
        throw new IllegalArgumentException("physics backward clock requires reset/replay");
      pending = Math.addExact(pending, delta);
      last = tick;
      if (!state.initialized) {
        ux = wx;
        uy = wy;
      } else {
        double bound = p.limit() * (delta * dt), m = dt / p.mass(), damping = p.damping();
        int count = pending;
        // Input inertia is injected once per update, not once per catch-up step.
        if (x) {
          xo = Finite.require(xo + clamp(Finite.require(ux - wx, "physics X input difference") * p.inertia(), bound),
              "physics X inertia");
          ux = wx;
        }
        if (y) {
          yo = Finite.require(yo + clamp(Finite.require(uy - wy, "physics Y input difference") * p.inertia(), bound),
              "physics Y inertia");
          uy = wy;
        }
        if (x || y) {
          for (int n = 0; n < count; n++) {
            if (x) {
              xv = Finite.require(xv + (p.wind() * PhysicsConstraint.REFERENCE_SCALE - xo * p.strength()) * m,
                  "physics X velocity");
              xo = Finite.require(xo + xv * dt, "physics X offset");
              xv *= damping;
            }
            if (y) {
              yv = Finite.require(yv - (p.gravity() * PhysicsConstraint.REFERENCE_SCALE + yo * p.strength()) * m,
                  "physics Y velocity");
              yo = Finite.require(yo + yv * dt, "physics Y offset");
              yv *= damping;
            }
          }
          if (x)
            wx = Finite.require(wx + xo * mix * p.influence().get(0), "physics world X");
          if (y)
            wy = Finite.require(wy + yo * mix * p.influence().get(1), "physics world Y");
        }
        if (angular || scale) {
          double ca = Math.atan2(c, a), dx = clamp(Finite.require(cx - wx, "physics angular X difference"), bound),
              dy = clamp(Finite.require(cy - wy, "physics angular Y difference"), bound),
              mr = (p.influence().get(2) + p.influence().get(4)) * mix;
          if (angular)
            ro = Finite.require(
                ro + turn(Math.atan2(Finite.require(dy + ty, "physics previous pole Y"),
                    Finite.require(dx + tx, "physics previous pole X")) - ca - ro * mr) * p.inertia(),
                "physics rotation inertia");
          double direction = ca + (angular ? ro * mr : 0), co = Math.cos(direction), si = Math.sin(direction),
              reach = Finite.require(length * Math.hypot(a, c), "physics input reach");
          // Tiny positive reach is an explicit engineering degeneracy; IO excludes it.
          if (scale && reach >= 1e-6)
            so = Finite.require(so + (dx * co + dy * si) * p.inertia() / reach, "physics scale inertia");
          for (int n = 0; n < count; n++) {
            if (scale) {
              sv = Finite.require(sv + (p.wind() * co - p.gravity() * si - so * p.strength()) * m,
                  "physics scale velocity");
              so = Finite.require(so + sv * dt, "physics scale offset");
              sv *= damping;
            }
            if (angular) {
              rv = Finite
                  .require(rv - ((p.wind() * si + p.gravity() * co) * (length / PhysicsConstraint.REFERENCE_SCALE)
                      + ro * p.strength()) * m, "physics rotation velocity");
              ro = Finite.require(ro + rv * dt, "physics rotation offset");
              rv *= damping;
              if (n + 1 < count) {
                direction = ca + ro * mr;
                co = Math.cos(direction);
                si = Math.sin(direction);
              }
            }
          }
        }
        if (x || y || angular || scale) {
          pending = 0;
          steps = Math.addExact(steps, count);
        }
      }
      cx = wx;
      cy = wy;
    } else {
      if (x)
        wx = Finite.require(wx + xo * mix * p.influence().get(0), "physics pose X");
      if (y)
        wy = Finite.require(wy + yo * mix * p.influence().get(1), "physics pose Y");
    }
    if (angular) {
      double offset = ro * mix, rotation = offset * p.influence().get(2), shear = offset * p.influence().get(4), co, si;
      if (p.influence().get(4) > 0) {
        if (p.influence().get(2) > 0) {
          co = Math.cos(rotation);
          si = Math.sin(rotation);
          double nb = co * b - si * d;
          d = si * b + co * d;
          b = nb;
        }
        rotation += shear;
      } else {
        co = Math.cos(rotation);
        si = Math.sin(rotation);
        double nb = co * b - si * d;
        d = si * b + co * d;
        b = nb;
      }
      co = Math.cos(rotation);
      si = Math.sin(rotation);
      double na = co * a - si * c;
      c = si * a + co * c;
      a = na;
    }
    if (scale) {
      double factor = Finite.require(1 + so * mix * p.influence().get(3), "physics scale factor");
      a *= factor;
      c *= factor;
    }
    var matrix = new Affine2(a, b, c, d, wx, wy);
    if (mode != Mode.POSE) {
      tx = Finite.require(length * a, "physics previous endpoint X");
      ty = Finite.require(length * c, "physics previous endpoint Y");
      state = new State(true, ux, uy, cx, cy, tx, ty, xo, xv, yo, yv, ro, rv, so, sv, steps, last, pending);
    }
    return new Result(matrix, state);
  }
}
