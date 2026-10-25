package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * IO-only fixed-tick compatibility audit. Checks EVERY tick of each finite
 * replay
 * horizon; a separate envelope proves intermediate and sub-tick POSE
 * arithmetic.
 * These float matrices are never returned to the renderer, sampler or product
 * solver.
 */
final class PhysicsFloatCertificate {
  private static final float DT = 1f / 60, PI = (float) Math.PI, DEG = PI / 180;

  private PhysicsFloatCertificate() {
  }

  private record M(float a, float b, float c, float d, float x, float y) {
    M child(M q) {
      return new M(a * q.a + b * q.c, a * q.b + b * q.d, c * q.a + d * q.c, c * q.b + d * q.d, a * q.x + b * q.y + x,
          c * q.x + d * q.y + y);
    }
  }

  private static final class S {
    boolean ready;
    float ux, uy, cx, cy, tx, ty, xo, xv, yo, yv, ro, rv, so, sv, lastTime, remaining;
    int steps;
  }

  private static float cos(float v) {
    return (float) Math.cos(v);
  }

  private static float sin(float v) {
    return (float) Math.sin(v);
  }

  private static float angle(float y, float x) {
    return (float) Math.atan2(y, x);
  }

  private static float norm(float x, float y) {
    return (float) Math.sqrt(x * x + y * y);
  }

  private static float clamp(float v, float q) {
    return Math.max(-q, Math.min(q, v));
  }

  private static M local(Transform t) {
    float r = (float) t.rotation() * DEG, y = ((float) t.rotation() + 90) * DEG;
    return new M(cos(r) * (float) t.scaleX(), cos(y) * (float) t.scaleY(), sin(r) * (float) t.scaleX(),
        sin(y) * (float) t.scaleY(), (float) t.x(), (float) t.y());
  }

  private static void rebuild(DocumentSnapshot d, Map<EntityId, Transform> pose, Map<EntityId, M> locks,
      Map<EntityId, M> world) {
    for (var b : d.bones()) {
      if (locks.containsKey(b.id()))
        world.put(b.id(), locks.get(b.id()));
      else {
        var m = local(pose.getOrDefault(b.id(), b.local()));
        world.put(b.id(), b.parentId() == null ? m : world.get(b.parentId()).child(m));
      }
    }
  }

  private static M solve(PhysicsConstraint p, M input, float length, S s, boolean update, float delta)
      throws ProjectFormatException {
    float a = input.a, b = input.b, c = input.c, d = input.d, x = input.x, y = input.y, mix = (float) p.mix(),
        inertia = (float) p.inertia(), strength = (float) p.strength(), mass = 1f / (float) p.mass(),
        wind = (float) p.wind(), gravity = (float) p.gravity(), damping = (float) p.damping();
    boolean px = p.influence().get(0) > 0, py = p.influence().get(1) > 0,
        angular = p.influence().get(2) > 0 || p.influence().get(4) > 0, scale = p.influence().get(3) > 0;
    if (update) {
      if (!s.ready) {
        s.ready = true;
        s.ux = x;
        s.uy = y;
      } else {
        float q = (float) p.limit() * delta, m = mass * DT;
        if (px) {
          s.xo += clamp((s.ux - x) * inertia, q);
          s.ux = x;
          s.xv += (wind * 100 - s.xo * strength) * m;
          s.xo += s.xv * DT;
          s.xv *= damping;
          x += s.xo * mix * (float) (double) p.influence().get(0);
        }
        if (py) {
          s.yo += clamp((s.uy - y) * inertia, q);
          s.uy = y;
          s.yv -= (gravity * 100 + s.yo * strength) * m;
          s.yo += s.yv * DT;
          s.yv *= damping;
          y += s.yo * mix * (float) (double) p.influence().get(1);
        }
        if (angular || scale) {
          float ca = angle(c, a), dx = clamp(s.cx - x, q), dy = clamp(s.cy - y, q),
              mr = ((float) (double) p.influence().get(2) + (float) (double) p.influence().get(4)) * mix;
          if (angular) {
            float r = angle(dy + s.ty, dx + s.tx) - ca - s.ro * mr;
            s.ro += (r - (float) Math.ceil(r * (1f / (2 * PI)) - .5f) * (2 * PI)) * inertia;
          }
          float direction = ca + (angular ? s.ro * mr : 0), co = cos(direction), si = sin(direction),
              reach = length * norm(a, c);
          if (scale && reach > 0)
            s.so += (dx * co + dy * si) * inertia / reach;
          if (scale) {
            s.sv += (wind * co - gravity * si - s.so * strength) * m;
            s.so += s.sv * DT;
            s.sv *= damping;
          }
          if (angular) {
            s.rv -= ((wind * si + gravity * co) * (length / 100) + s.ro * strength) * m;
            s.ro += s.rv * DT;
            s.rv *= damping;
          }
        }
      }
      s.cx = x;
      s.cy = y;
    } else {
      if (px)
        x += s.xo * mix * (float) (double) p.influence().get(0);
      if (py)
        y += s.yo * mix * (float) (double) p.influence().get(1);
    }
    if (angular) {
      float offset = s.ro * mix, r = offset * (float) (double) p.influence().get(2), co, si;
      if (p.influence().get(4) > 0) {
        if (p.influence().get(2) > 0) {
          co = cos(r);
          si = sin(r);
          float nb = co * b - si * d;
          d = si * b + co * d;
          b = nb;
        }
        r += offset * (float) (double) p.influence().get(4);
      } else {
        co = cos(r);
        si = sin(r);
        float nb = co * b - si * d;
        d = si * b + co * d;
        b = nb;
      }
      co = cos(r);
      si = sin(r);
      float na = co * a - si * c;
      c = si * a + co * c;
      a = na;
    }
    if (scale) {
      float factor = 1 + s.so * mix * (float) (double) p.influence().get(3);
      a *= factor;
      c *= factor;
    }
    if (update) {
      s.tx = length * a;
      s.ty = length * c;
    }
    for (float v : new float[] { a, b, c, d, x, y, s.ux, s.uy, s.cx, s.cy, s.tx, s.ty, s.xo, s.xv, s.yo, s.yv, s.ro,
        s.rv, s.so, s.sv })
      if (!Float.isFinite(v))
        throw ProjectCodec.bad("physics.float-certificate.state",
            "Non-finite fixed-tick float state; project remains lossless");
    return new M(a, b, c, d, x, y);
  }

  private static float checked(float value) throws ProjectFormatException {
    if (!Float.isFinite(value))
      throw ProjectCodec.bad("physics.float-certificate.state", "Non-finite float catch-up intermediate");
    return value;
  }

  private static M timed(PhysicsConstraint p, M input, float length, S s, float clock, boolean reset)
      throws ProjectFormatException {
    if (p.mix() == 0)
      return input;
    if (reset) {
      s.ready = false;
      s.xo = s.xv = s.yo = s.yv = s.ro = s.rv = s.so = s.sv = s.remaining = 0;
      s.lastTime = clock;
      s.steps = 0;
    }
    float a = input.a, b = input.b, c = input.c, d = input.d, x = input.x, y = input.y, mix = (float) p.mix(),
        inertia = (float) p.inertia(), strength = (float) p.strength(), mass = 1f / (float) p.mass(),
        wind = (float) p.wind(), gravity = (float) p.gravity(), damping = (float) p.damping(),
        delta = Math.max(clock - s.lastTime, 0);
    s.lastTime = clock;
    s.remaining = checked(s.remaining + delta);
    boolean px = p.influence().get(0) > 0, py = p.influence().get(1) > 0,
        angular = p.influence().get(2) > 0 || p.influence().get(4) > 0, scale = p.influence().get(3) > 0;
    if (!s.ready) {
      s.ready = true;
      s.ux = x;
      s.uy = y;
    } else {
      int count = 0;
      float remaining = s.remaining;
      if (px || py || angular || scale) {
        while (remaining >= DT) {
          if (++count > PhysicsEnvelope.MAX_STEPS)
            throw ProjectCodec.bad("physics.float-certificate.clock", "Float catch-up exceeds 36000 steps");
          remaining -= DT;
        }
        s.remaining = remaining;
        s.steps = Math.addExact(s.steps, count);
      }
      float q = checked((float) p.limit() * delta), m = checked(mass * DT);
      if (px) {
        s.xo = checked(s.xo + clamp(checked((s.ux - x) * inertia), q));
        s.ux = x;
      }
      if (py) {
        s.yo = checked(s.yo + clamp(checked((s.uy - y) * inertia), q));
        s.uy = y;
      }
      for (int tick = 0; tick < count; tick++) {
        if (px) {
          s.xv = checked(s.xv + (wind * 100 - s.xo * strength) * m);
          s.xo = checked(s.xo + s.xv * DT);
          s.xv = checked(s.xv * damping);
        }
        if (py) {
          s.yv = checked(s.yv - (gravity * 100 + s.yo * strength) * m);
          s.yo = checked(s.yo + s.yv * DT);
          s.yv = checked(s.yv * damping);
        }
      }
      if (px)
        x = checked(x + s.xo * mix * (float) (double) p.influence().get(0));
      if (py)
        y = checked(y + s.yo * mix * (float) (double) p.influence().get(1));
      if (angular || scale) {
        float ca = angle(c, a), dx = clamp(checked(s.cx - x), q), dy = clamp(checked(s.cy - y), q),
            mr = ((float) (double) p.influence().get(2) + (float) (double) p.influence().get(4)) * mix;
        if (angular) {
          float r = checked(angle(checked(dy + s.ty), checked(dx + s.tx)) - ca - s.ro * mr);
          s.ro = checked(s.ro + (r - (float) Math.ceil(r * (1f / (2 * PI)) - .5f) * (2 * PI)) * inertia);
        }
        float direction = checked(ca + (angular ? s.ro * mr : 0)), co = cos(direction), si = sin(direction);
        if (scale) {
          float reach = checked(length * norm(a, c));
          if (reach > 0)
            s.so = checked(s.so + (dx * co + dy * si) * inertia / reach);
        }
        for (int tick = 0; tick < count; tick++) {
          if (scale) {
            s.sv = checked(s.sv + (wind * co - gravity * si - s.so * strength) * m);
            s.so = checked(s.so + s.sv * DT);
            s.sv = checked(s.sv * damping);
          }
          if (angular) {
            s.rv = checked(s.rv - ((wind * si + gravity * co) * (length / 100) + s.ro * strength) * m);
            s.ro = checked(s.ro + s.rv * DT);
            s.rv = checked(s.rv * damping);
            if (tick + 1 < count) {
              direction = checked(ca + s.ro * mr);
              co = cos(direction);
              si = sin(direction);
            }
          }
        }
      }
    }
    s.cx = x;
    s.cy = y;
    if (angular) {
      float offset = s.ro * mix, r = offset * (float) (double) p.influence().get(2), co, si;
      if (p.influence().get(4) > 0) {
        if (p.influence().get(2) > 0) {
          co = cos(r);
          si = sin(r);
          float nb = co * b - si * d;
          d = si * b + co * d;
          b = nb;
        }
        r += offset * (float) (double) p.influence().get(4);
      } else {
        co = cos(r);
        si = sin(r);
        float nb = co * b - si * d;
        d = si * b + co * d;
        b = nb;
      }
      co = cos(r);
      si = sin(r);
      float na = co * a - si * c;
      c = si * a + co * c;
      a = na;
    }
    if (scale) {
      float factor = 1 + s.so * mix * (float) (double) p.influence().get(3);
      a *= factor;
      c *= factor;
    }
    s.tx = checked(length * a);
    s.ty = checked(length * c);
    for (float v : new float[] { a, b, c, d, x, y, s.ux, s.uy, s.cx, s.cy, s.tx, s.ty, s.xo, s.xv, s.yo, s.yv, s.ro,
        s.rv, s.so, s.sv, s.lastTime, s.remaining })
      checked(v);
    return new M(a, b, c, d, x, y);
  }

  private static Map<EntityId, Transform> floatPose(DocumentSnapshot d, AnimationClip clip, float time)
      throws ProjectFormatException {
    var result = new LinkedHashMap<EntityId, Transform>();
    for (var t : clip.timelines()) {
      var bone = d.bone(t.boneId());
      var setup = bone.local();
      var previous = result.getOrDefault(bone.id(), setup);
      Float v = FloatTimelineValue.sample(t.keys(), 0, time);
      if (v == null)
        continue;
      Float w = t.channel().components() == 2 ? FloatTimelineValue.sample(t.keys(), 1, time) : null;
      result.put(bone.id(), switch (t.channel()) {
        case TRANSLATE -> new Transform(checked((float) setup.x() + v), checked((float) setup.y() + w),
            previous.rotation(), previous.scaleX(), previous.scaleY());
        case ROTATE -> new Transform(previous.x(), previous.y(), checked((float) setup.rotation() + v),
            previous.scaleX(), previous.scaleY());
        case SCALE -> new Transform(previous.x(), previous.y(), previous.rotation(),
            checked((float) setup.scaleX() * v), checked((float) setup.scaleY() * w));
      });
    }
    return result;
  }

  private static PhysicsConstraint floatParameters(PhysicsConstraint setup, AnimationClip clip, float time)
      throws ProjectFormatException {
    var parameters = new ArrayList<Double>();
    for (double v : setup.parameters())
      parameters.add((double) (float) v);
    for (var t : clip.constraintTracks())
      if (t.constraintId().equals(setup.id())) {
        int n = t.channel().physicsParameter();
        float base = (float) (double) setup.parameters().get(n);
        if (n == 3)
          base = 1f / (1f / base);
        Float value = FloatTimelineValue.sample(t.keys(), 0, time);
        parameters.set(n, (double) checked(value == null ? base : base + (value - base)));
      }
    try {
      return new PhysicsConstraint(setup.id(), setup.name(), setup.order(), setup.enabled(), setup.boneId(),
          setup.influence(), setup.limit(), parameters);
    } catch (IllegalArgumentException e) {
      throw ProjectCodec.bad("physics.float-certificate.parameters",
          "Float timeline/blend leaves the certified parameter domain: " + e.getMessage());
    }
  }

  private static void compare(M m, Affine2 a, double length) throws ProjectFormatException {
    var tip = a.apply(length, 0);
    float[] actual = { m.a, m.b, m.c, m.d, m.x, m.y, m.x + m.a * (float) length, m.y + m.c * (float) length };
    double[] expected = { a.a(), a.b(), a.c(), a.d(), a.tx(), a.ty(), tip.x(), tip.y() };
    for (int n = 0; n < 8; n++)
      if (!Float.isFinite(actual[n]) || Math.abs(actual[n] - expected[n]) > 1e-3 + 1e-5 * Math.abs(expected[n]))
        throw ProjectCodec.bad("physics.float-certificate.compatibility",
            "Double/float fixed-tick branch differs at component " + n + "; project remains lossless");
  }

  private static boolean writes(Constraint c) {
    return c.enabled() && (c instanceof IkConstraint p ? p.mix() != 0
        : c instanceof TransformConstraint p ? !p.inactive() : c instanceof PathConstraint p ? !p.inactive() : false);
  }

  static void validate(DocumentSnapshot d) throws ProjectFormatException {
    var physics = d.constraints().stream().filter(c -> c instanceof PhysicsConstraint p && p.mix() != 0)
        .map(c -> (PhysicsConstraint) c).toList();
    if (physics.isEmpty())
      return;
    Set<EntityId> affected = new HashSet<>();
    physics.forEach(p -> affected.add(p.boneId()));
    for (var b : d.bones())
      if (affected.contains(b.parentId()))
        affected.add(b.id());
    for (var c : d.constraints())
      if (writes(c) && c.boneIds().stream().anyMatch(affected::contains))
        throw ProjectCodec.bad("physics.float-certificate.dependencies",
            "A different constraint writes in the affected subtree; mixed float replay proof is pending");
    List<EntityId> clips = d.animations().isEmpty() ? List.of(AnimationClip.DEFAULT_ID)
        : d.animations().stream().map(AnimationClip::id).toList();
    long work = 0;
    for (var clip : clips) {
      double duration = PhysicsPlayer.duration(d, clip);
      int ticks = PhysicsEnvelope.tickAt(duration);
      work += (long) (ticks + 1) * d.bones().size() * physics.size();
      if (work > 2_000_000)
        throw ProjectCodec.bad("physics.float-certificate.work",
            "Fixed-tick proof exceeds bounded work budget; split or shorten the project");
      var states = new HashMap<EntityId, S>();
      Map<EntityId, PhysicsSolver.State> doubleStates = Map.of();
      List<Constraint> physicalOnly = new ArrayList<>(physics);
      float previousClock = 0;
      for (int tick = 0; tick <= ticks; tick++) {
        double time = tick / 60.;
        var pose = d.animations().isEmpty() ? Map.<EntityId, Transform>of()
            : AnimationSampler.pose(d, clip, (float) time, false);
        float clock = tick == 0 ? 0 : Math.nextUp(Math.nextUp((float) (tick * (double) DT))),
            delta = clock - previousClock;
        previousClock = clock;
        Map<EntityId, M> locks = new HashMap<>(), world = new LinkedHashMap<>();
        rebuild(d, pose, locks, world);
        for (var p : physics) {
          var matrix = solve(p, world.get(p.boneId()), (float) d.bone(p.boneId()).length(),
              states.computeIfAbsent(p.id(), id -> new S()), true, delta);
          locks.put(p.boneId(), matrix);
          rebuild(d, pose, locks, world);
        }
        var transaction = new PhysicsFrame(tick == 0 ? PhysicsSolver.Mode.RESET : PhysicsSolver.Mode.UPDATE,
            doubleStates);
        var doublePose = d.animations().isEmpty() ? Map.<EntityId, Transform>of()
            : AnimationSampler.pose(d, clip, time, false);
        var expected = ConstraintSolver.evaluate(d.bones(), doublePose, physicalOnly, d.slots(), d.attachments(),
            transaction);
        doubleStates = transaction.snapshot();
        for (var id : affected)
          compare(world.get(id), expected.get(id), d.bone(id).length());
      }
      if (duration > ticks / 60.) {
        var pose = d.animations().isEmpty() ? Map.<EntityId, Transform>of()
            : AnimationSampler.pose(d, clip, duration, false);
        Map<EntityId, M> locks = new HashMap<>(), world = new LinkedHashMap<>();
        rebuild(d, pose, locks, world);
        for (var p : physics) {
          locks.put(p.boneId(),
              solve(p, world.get(p.boneId()), (float) d.bone(p.boneId()).length(), states.get(p.id()), false, 0));
          rebuild(d, pose, locks, world);
        }
        var expected = ConstraintSolver.evaluate(d.bones(), pose, physicalOnly, d.slots(), d.attachments(),
            new PhysicsFrame(PhysicsSolver.Mode.POSE, doubleStates));
        for (var id : affected)
          compare(world.get(id), expected.get(id), d.bone(id).length());
      }
    }
  }

  private static float clock(int tick) {
    return tick == 0 ? 0 : Math.nextUp(Math.nextUp((float) (tick * (double) DT)));
  }

  private static void validateTrack(ConstraintTimeline t, PhysicsConstraint setup, String path)
      throws ProjectFormatException {
    float previous = -1;
    for (int n = 0; n < t.keys().size(); n++) {
      var k = t.keys().get(n);
      RuntimeRange.number(k.time(), path + ".time");
      if ((float) k.time() <= previous)
        throw ProjectCodec.bad(path + ".time", "Distinct seconds collapse in float32");
      previous = (float) k.time();
      double v = k.values().get(0);
      RuntimeRange.number(v, path + ".value");
      if (t.channel() == ConstraintChannel.PHYSICS_MIX && setup.enabled()) {
        double before = n == 0 ? setup.parameters().get(6) : t.keys().get(n - 1).values().get(0);
        if (before != v && k.time() != PhysicsEnvelope.tickAt(k.time()) / 60.)
          throw ProjectCodec.bad(path + ".time",
              "Mix transitions require exact 60Hz tick boundaries in the current runtime domain");
        if (n + 1 < t.keys().size() && v != t.keys().get(n + 1).values().get(0)
            && k.curves().get(0).kind() != Curve.Kind.STEPPED)
          throw ProjectCodec.bad(path + ".curve",
              "Continuous physics mix float proof is pending; use stepped 60Hz-boundary mix keys or retain the lossless project");
      }
      if (n + 1 < t.keys().size()) {
        var next = t.keys().get(n + 1);
        double dt = next.time() - k.time(), dv = next.values().get(0) - v;
        RuntimeRange.number(dt, path + ".time difference");
        RuntimeRange.number(dv, path + ".value difference");
        var curve = k.curves().get(0);
        if (curve.kind() == Curve.Kind.BEZIER) {
          for (double x : List.of(curve.x1(), curve.x2()))
            RuntimeRange.number(k.time() + dt * x, path + ".curve.time");
          for (double y : List.of(curve.y1(), curve.y2()))
            RuntimeRange.number(v + dv * y, path + ".curve.value");
          RuntimeRange.certifyTimeKnots(k.time(), next.time(), curve);
        }
      }
    }
  }

  static void validateDynamic(DocumentSnapshot d) throws ProjectFormatException {
    if (d.constraints().stream().anyMatch(c -> !(c instanceof PhysicsConstraint)) || d.animations().stream()
        .flatMap(a -> a.constraintTracks().stream()).anyMatch(t -> t.channel().physicsParameter() < 0))
      throw ProjectCodec.bad("physics.float-certificate.dependencies",
          "Animated physics currently requires a physics-only constraint graph; own project remains lossless");
    if (d.attachments().stream().anyMatch(a -> a instanceof ClippingAttachment
        && d.slots().stream().anyMatch(slot -> a.id().equals(slot.activeAttachmentId()))))
      throw ProjectCodec.bad("physics.float-certificate.clipping",
          "Dynamic physics plus active clipping proof is pending");
    var physics = d.constraints().stream().map(c -> (PhysicsConstraint) c).toList();
    var active = physics.stream()
        .filter(p -> p.enabled() && d.animations().stream().anyMatch(a -> PhysicsTraceProof.potential(List.of(p), a)))
        .toList();
    for (var p : physics) {
      for (double v : p.influence())
        RuntimeRange.number(v, "physics influence");
      for (double v : p.parameters())
        RuntimeRange.number(v, "physics parameter");
      RuntimeRange.number(p.limit(), "physics limit");
    }
    for (var p : active) {
      for (EntityId parent = d.bone(p.boneId()).parentId(); parent != null; parent = d.bone(parent).parentId()) {
        EntityId ancestor = parent;
        if (active.stream().anyMatch(writer -> writer.boneId().equals(ancestor)))
          throw ProjectCodec.bad("physics.float-certificate.parent",
              "A potentially active physics ancestor cannot supply the static applied-pose inverse");
      }
      var inverse = ClipFloatCertificate.staticInverseBound(d, d.bone(p.boneId()).parentId());
      if (inverse.isEmpty() || inverse.getAsDouble() == 0)
        throw ProjectCodec.bad("physics.float-certificate.parent",
            "Static nonsingular parent required for animated physics");
      if (p.influence().get(3) > 0) {
        if (d.animations().stream().flatMap(a -> a.timelines().stream())
            .anyMatch(t -> t.boneId().equals(p.boneId()) && t.channel() == Channel.SCALE))
          throw ProjectCodec.bad("physics.float-certificate.scaleX", "Animated scale reach proof is pending");
        double reach = d.bone(p.boneId()).length() * Math.abs(d.bone(p.boneId()).local().scaleX())
            / (2 * inverse.getAsDouble());
        if (reach != 0 && reach < 1e-6)
          throw ProjectCodec.bad("physics.float-certificate.scaleX",
              "Tiny positive reach uses the engineering degeneracy");
      }
    }
    for (var clip : d.animations())
      for (var t : clip.constraintTracks())
        validateTrack(t, (PhysicsConstraint) d.constraint(t.constraintId()), "animations." + clip.name() + ".physics."
            + d.constraint(t.constraintId()).name() + "." + t.channel().name().substring(8).toLowerCase(Locale.ROOT));
    var clean = d.animations().stream()
        .map(a -> new AnimationClip(a.id(), a.name(), a.timelines(), a.deforms(), List.of(), a.discrete())).toList();
    RuntimeRange.validate(new DocumentSnapshot(d.bones(), d.slots(), d.attachments(), clean, List.of(), d.resources()));
    long work = 0;
    for (var clip : d.animations()) {
      Map<EntityId, PhysicsTraceProof.Offsets> bounds = new LinkedHashMap<>();
      for (var p : physics)
        bounds.put(p.id(), PhysicsTraceProof.Offsets.ZERO);
      if (!PhysicsTraceProof.potential(d.constraints(), clip)) {
        RuntimeRange.tracedPhysicsBounds(d, clip, bounds);
        continue;
      }
      double duration = PhysicsPlayer.duration(d, clip.id());
      int ticks = PhysicsEnvelope.tickAt(duration);
      work += (long) (ticks + 1) * d.bones().size() * physics.size();
      if (work > PhysicsTraceProof.MAX_WORK)
        throw ProjectCodec.bad("physics.float-certificate.work",
            "Full animated fixed-tick proof exceeds bounded work budget");
      Set<EntityId> affected = new HashSet<>();
      active.forEach(p -> affected.add(p.boneId()));
      for (var b : d.bones())
        if (affected.contains(b.parentId()))
          affected.add(b.id());
      var states = new HashMap<EntityId, S>();
      Map<EntityId, PhysicsSolver.State> doubleStates = Map.of();
      for (int tick = 0; tick <= ticks; tick++) {
        double time = tick / 60.;
        float nominal = (float) time;
        var pose = floatPose(d, clip, nominal);
        Map<EntityId, M> locks = new HashMap<>(), world = new LinkedHashMap<>();
        rebuild(d, pose, locks, world);
        for (var setup : active) {
          var p = floatParameters(setup, clip, nominal);
          var state = states.computeIfAbsent(p.id(), id -> new S());
          locks.put(p.boneId(),
              timed(p, world.get(p.boneId()), (float) d.bone(p.boneId()).length(), state, clock(tick), tick == 0));
          rebuild(d, pose, locks, world);
          var before = bounds.get(p.id());
          bounds.put(p.id(),
              new PhysicsTraceProof.Offsets(Math.max(before.xy(), Math.max(Math.abs(state.xo), Math.abs(state.yo))),
                  Math.max(before.rotation(), Math.abs(state.ro)), Math.max(before.scale(), Math.abs(state.so)),
                  Math.max(before.velocity(), Math.max(Math.max(Math.abs(state.xv), Math.abs(state.yv)),
                      Math.max(Math.abs(state.rv), Math.abs(state.sv))))));
        }
        var transaction = new PhysicsFrame(tick == 0 ? PhysicsSolver.Mode.RESET : PhysicsSolver.Mode.UPDATE,
            doubleStates, tick);
        var expected = ConstraintSolver.evaluate(d.bones(), AnimationSampler.pose(d, clip.id(), time, false),
            AnimationSampler.constraints(d, clip.id(), time, false), d.slots(), d.attachments(), transaction);
        doubleStates = transaction.snapshot();
        for (var id : affected)
          compare(world.get(id), expected.get(id), d.bone(id).length());
        for (var p : active) {
          var f = states.get(p.id());
          var e = doubleStates.getOrDefault(p.id(), PhysicsSolver.State.ZERO);
          if (Math.abs(f.xo - e.xOffset()) > 1e-4 || Math.abs(f.yo - e.yOffset()) > 1e-4
              || Math.abs(f.ro - e.rotationOffset()) > 1e-6 || Math.abs(f.so - e.scaleOffset()) > 1e-6)
            throw ProjectCodec.bad("physics.float-certificate.state-phase",
                "Float/double hidden offset phase differs at tick " + tick + " for " + p.name());
          if (f.ready != e.initialized() || f.steps != e.steps() || Math.round(f.remaining / DT) != e.pendingTicks()
              || f.lastTime != clock(e.lastTick()))
            throw ProjectCodec.bad("physics.float-certificate.clock",
                "Float time accumulation differs from the prescribed integer replay at tick " + tick + " for "
                    + p.name());
        }
      }
      if (duration > ticks / 60.) {
        var pose = floatPose(d, clip, (float) duration);
        Map<EntityId, M> locks = new HashMap<>(), world = new LinkedHashMap<>();
        rebuild(d, pose, locks, world);
        for (var setup : active) {
          var p = floatParameters(setup, clip, (float) duration);
          locks.put(p.boneId(),
              solve(p, world.get(p.boneId()), (float) d.bone(p.boneId()).length(), states.get(p.id()), false, 0));
          rebuild(d, pose, locks, world);
        }
        var expected = ConstraintSolver.evaluate(d.bones(), AnimationSampler.pose(d, clip.id(), duration, false),
            AnimationSampler.constraints(d, clip.id(), duration, false), d.slots(), d.attachments(),
            new PhysicsFrame(PhysicsSolver.Mode.POSE, doubleStates));
        for (var id : affected)
          compare(world.get(id), expected.get(id), d.bone(id).length());
      }
      RuntimeRange.tracedPhysicsBounds(d, clip, bounds);
    }
  }

  /**
   * Float arithmetic perturbations: Duhamel/Gronwall with a bound K for all A^n.
   * q=0 is triangular and uses its separate polynomial bound, avoiding a fake
   * pole.
   */
  static double gain(PhysicsConstraint p, int ticks) throws ProjectFormatException {
    double n = Math.max(1, ticks), mass = 1f / (float) p.mass(), q = (float) p.strength() * mass * DT * DT,
        d = (float) p.damping(), trace = 1 - q + d, disc = trace * trace - 4 * d, unit = Math.scalb(1., -24), gain;
    if (q == 0)
      gain = 3 * n * n * Math.exp(32 * unit * n);
    else {
      double k = d == 0 ? 2 : Math.min(3 * n, disc == 0 ? 3 * n : 2 * (3 + d) / Math.sqrt(Math.abs(disc)));
      gain = n * k * Math.exp(k * 32 * unit * n);
    }
    return RuntimeRange.number(gain, "physics spring rounding gain");
  }
}
