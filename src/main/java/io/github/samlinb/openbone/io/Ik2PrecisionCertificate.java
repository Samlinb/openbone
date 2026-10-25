package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * IO-only all-branch static IK2 compatibility audit. It never supplies a
 * product pose.
 * Finite arithmetic alone does not certify acos near a straight/folded chain.
 */
final class Ik2PrecisionCertificate {
  private Ik2PrecisionCertificate() {
  }

  static final float PI = (float) Math.PI, DEG = PI / 180;

  record M(float a, float b, float c, float d, float x, float y) {
    static final M IDENTITY = new M(1, 0, 0, 1, 0, 0);

    M child(M q) {
      return new M(a * q.a + b * q.c, a * q.b + b * q.d, c * q.a + d * q.c, c * q.b + d * q.d, a * q.x + b * q.y + x,
          c * q.x + d * q.y + y);
    }
  }

  record Input(Map<EntityId, M> floats, Map<EntityId, Affine2> doubles, float first, float second, float cy, float tx,
      float ty, double firstDouble, double secondDouble) {
  }

  private static float sin(float x) {
    return (float) Math.sin(x);
  }

  private static float cos(float x) {
    return (float) Math.cos(x);
  }

  private static float angle(float y, float x) {
    return (float) Math.atan2(y, x);
  }

  private static float norm(float x, float y) {
    return (float) Math.sqrt(x * x + y * y);
  }

  // Normal inheritance adds zero shear before radians. Retain that addition:
  // -0 rotation becomes +0, which can change an atan2 branch downstream.
  private static M matrix(float x, float y, float r, float sx, float sy) {
    float a = (r + 0f) * DEG, b = (r + 90 + 0f) * DEG;
    return new M(cos(a) * sx, cos(b) * sy, sin(a) * sx, sin(b) * sy, x, y);
  }

  private static M matrix(Transform t) {
    return matrix((float) t.x(), (float) t.y(), (float) t.rotation(), (float) t.scaleX(), (float) t.scaleY());
  }

  private static boolean active(Constraint c) {
    return c.enabled() && (c instanceof IkConstraint p ? p.mix() != 0
        : c instanceof TransformConstraint p ? !p.inactive()
            : c instanceof PathConstraint p ? !p.inactive() : ((PhysicsConstraint) c).mix() != 0);
  }

  private static boolean changesSetup(BoneTimeline t) {
    double neutral = t.channel() == Channel.SCALE ? 1 : 0;
    return t.keys().stream().flatMap(k -> k.values().stream()).anyMatch(v -> v != neutral);
  }

  static ProjectFormatException bad(IkConstraint ik, String detail) {
    return ProjectCodec.bad("$.ik." + ik.name() + ".float-precision",
        detail + "; lossless engineering project remains editable");
  }

  static Set<EntityId> descendants(DocumentSnapshot d, EntityId root) {
    Set<EntityId> ids = new HashSet<>(Set.of(root));
    for (var b : d.bones())
      if (ids.contains(b.parentId()))
        ids.add(b.id());
    return ids;
  }

  private static void ancestors(DocumentSnapshot d, EntityId id, Set<EntityId> ids) {
    for (; id != null; id = d.bone(id).parentId())
      ids.add(id);
  }

  static Input input(DocumentSnapshot d, IkConstraint ik) {
    Map<EntityId, M> floats = new LinkedHashMap<>();
    Map<EntityId, Affine2> doubles = new LinkedHashMap<>();
    for (var b : d.bones()) {
      var f = matrix(b.local());
      var m = b.local().matrix();
      floats.put(b.id(),
          b.parentId() == null ? new M(f.a, f.b, f.c, f.d, f.x + 0f, f.y + 0f) : floats.get(b.parentId()).child(f));
      doubles.put(b.id(), b.parentId() == null ? m : doubles.get(b.parentId()).multiply(m));
    }
    var p = d.bone(ik.boneIds().get(0));
    var c = d.bone(ik.boneIds().get(1));
    var pt = p.local();
    var ct = c.local();
    var pw = floats.get(p.id());
    var up = floats.get(p.parentId());
    boolean circle = Math.abs(Math.abs((float) pt.scaleX()) - Math.abs((float) pt.scaleY())) <= .0001f,
        zeroY = !circle || ik.stretch();
    float cx = (float) ct.x(), cy = zeroY ? 0 : (float) ct.y();
    float wx = zeroY ? pw.a * cx + pw.x : pw.a * cx + pw.b * cy + pw.x,
        wy = zeroY ? pw.c * cx + pw.y : pw.c * cx + pw.d * cy + pw.y, det = up.a * up.d - up.b * up.c,
        inv = Math.abs(det) <= .0001f ? 0 : 1 / det;
    float x = wx - up.x, y = wy - up.y, dx = (x * up.d - y * up.b) * inv - (float) pt.x(),
        dy = (y * up.a - x * up.c) * inv - (float) pt.y(), first = norm(dx, dy);
    var target = floats.get(ik.targetId());
    x = target.x - up.x;
    y = target.y - up.y;
    var u = doubles.get(p.parentId());
    boolean circleDouble = Math.abs(Math.abs(pt.scaleX()) - Math.abs(pt.scaleY())) <= .0001;
    double firstDouble = Math.abs(u.determinant()) <= .0001 ? Math.hypot(pt.x(), pt.y())
        : Math.hypot(Math.abs(pt.scaleX()) * ct.x(),
            Math.abs(pt.scaleY()) * (!circleDouble || ik.stretch() ? 0 : ct.y()));
    return new Input(floats, doubles, first,
        (float) c.length() * Math.abs((float) ct.scaleX()) * Math.abs((float) pt.scaleX()), cy,
        (x * up.d - y * up.b) * inv - (float) pt.x(), (y * up.a - x * up.c) * inv - (float) pt.y(), firstDouble,
        c.length() * Math.abs(ct.scaleX()) * Math.abs(pt.scaleX()));
  }

  static void validate(DocumentSnapshot d) throws ProjectFormatException {
    for (var c : d.constraints())
      if (c instanceof IkConstraint setup && setup.enabled() && setup.boneIds().size() == 2) {
        // IK runtime tracks are stepped absolute groups. Certify every possible state,
        // including activation from a zero-mix Setup; these are states, not time
        // samples.
        Set<IkConstraint> states = new LinkedHashSet<>();
        states.add(setup);
        for (var clip : d.animations())
          for (var track : clip.constraintTracks())
            if (track.constraintId().equals(setup.id()))
              for (var key : track.keys())
                states.add((IkConstraint) track.channel().apply(setup, key.values()));
        for (var ik : states)
          if (active(ik)) {
            var constraints = new ArrayList<>(d.constraints());
            constraints.set(constraints.indexOf(setup), ik);
            validateState(d.withConstraints(constraints), ik);
          }
      }
  }

  private static boolean possiblyActive(DocumentSnapshot d, Constraint c) {
    return active(c) || d.animations().stream().anyMatch(clip -> active(ConstraintTimeline.envelope(clip, c)));
  }

  private static void validateState(DocumentSnapshot d, IkConstraint ik) throws ProjectFormatException {
    var p = d.bone(ik.boneIds().get(0));
    Set<EntityId> causal = new HashSet<>();
    ancestors(d, p.id(), causal);
    ancestors(d, ik.boneIds().get(1), causal);
    ancestors(d, ik.targetId(), causal);
    Set<EntityId> affected = descendants(d, p.id());
    var dynamic = d.animations().stream().filter(clip -> clip.timelines().stream()
        .anyMatch(t -> (causal.contains(t.boneId()) || affected.contains(t.boneId())) && changesSetup(t))).toList();
    for (var other : d.constraints())
      if (other != ik && possiblyActive(d, other)
          && other.boneIds().stream().anyMatch(id -> causal.contains(id) || affected.contains(id)))
        throw bad(ik, "Earlier/later constraint writers require an IK2 precision dependency proof");
    var in = input(d, ik);
    boolean fallback = in.first < .0001f || in.firstDouble < .0001;
    if ((in.first < .0001f) != (in.firstDouble < .0001))
      throw bad(ik, "First-segment float fallback differs");
    auditStatic(d, ik, in, affected);
    if (!dynamic.isEmpty()) {
      if (Math.abs(Math.abs(p.local().scaleX()) - Math.abs(p.local().scaleY())) > .0001)
        throw bad(ik, "Animated nonuniform ellipse requires a complete-hull branch and endpoint precision proof");
      if (fallback)
        throw bad(ik, "Animated first-segment IK1 fallback requires a complete-hull endpoint precision proof");
    }
    // acos/clamp, atan2 and descendant amplification remain sensitive when
    // softness is zero. Every causal or affected motion requires the same proof.
    for (var clip : dynamic) {
      for (var track : clip.timelines())
        if (causal.contains(track.boneId()) && changesSetup(track)
            && (!track.boneId().equals(ik.targetId()) || track.channel() != Channel.TRANSLATE))
          throw bad(ik, "Changing causal linear/chain inputs require a complete IK2 precision proof");
      Set<EntityId> shared = new HashSet<>();
      ancestors(d, p.id(), shared);
      if (shared.contains(ik.targetId()))
        throw bad(ik, "Animated shared target ancestor requires a cancellation precision proof");
      Ik2AcosErrorBounds.validate(d, clip, ik, in, affected);
    }
  }

  private static float wrap(float r) {
    if (r > 180)
      r -= 360;
    else if (r < -180)
      r += 360;
    return r;
  }

  private static void near(float actual, double expected, IkConstraint ik, String label) throws ProjectFormatException {
    if (!Float.isFinite(actual) || Math.abs(actual - expected) > 1e-3 + 1e-5 * Math.abs(expected))
      throw bad(ik,
          "Static float compatibility exceeds tolerance at " + label + ": actual=" + actual + ", expected=" + expected);
  }

  record StaticSolution(float parentRotation, float childRotation, float scaleX, float scaleY, String branch) {
  }

  /**
   * Separate point audit covers every static branch; branch labels are test
   * evidence,
   * never core poses. Quadratic roots and residual sqrt are rounded
   * independently.
   */
  static StaticSolution staticSolution(DocumentSnapshot d, IkConstraint ik, Input in) {
    var parent = d.bone(ik.boneIds().get(0));
    var child = d.bone(ik.boneIds().get(1));
    var p = parent.local();
    var c = child.local();
    float tx = in.tx, ty = in.ty, dd = tx * tx + ty * ty, l1 = in.first, l2 = in.second,
        soft = (float) ik.softness() * (Math.abs((float) p.scaleX()) * (Math.abs((float) c.scaleX()) + 1) * .5f);
    float sx = (float) p.scaleX(), sy = (float) p.scaleY(), mix = (float) ik.mix();
    int bend = ik.bendPositive() ? 1 : -1, orientation = (sx < 0 ? -1 : 1) * (sy < 0 ? -1 : 1);
    if (l1 < .0001f) {
      var up = in.floats.get(parent.parentId());
      var target = in.floats.get(ik.targetId());
      float det = up.a * up.d - up.b * up.c, x = target.x - up.x, y = target.y - up.y, dx = 0, dy = 0;
      if (Math.abs(det) > .0001f) {
        dx = (x * up.d - y * up.b) / det - (float) p.x();
        dy = (y * up.a - x * up.c) / det - (float) p.y();
      }
      // IK1 fallback uses division (not the IK2 reciprocal product), additive
      // rotation and only signed-positive parent length for stretch; uniform=false.
      float delta = -(float) p.rotation();
      delta += angle(dy, dx) * (180 / PI);
      if (sx < 0)
        delta += 180;
      float reach = (float) parent.length() * sx;
      if (ik.stretch() && reach > .0001f && dx * dx + dy * dy > reach * reach)
        sx *= ((float) Math.sqrt(dx * dx + dy * dy) / reach - 1) * mix + 1;
      return new StaticSolution((float) p.rotation() + wrap(delta) * mix, 0, sx, sy, "IK1_FALLBACK");
    }
    if (soft != 0) {
      float radius = (float) Math.sqrt(dd), over = radius - l1 - l2 + soft;
      if (over > 0) {
        float u = Math.min(1, over / (2 * soft)) - 1, removed = (over - soft * (1 - u * u)) / radius;
        tx -= removed * tx;
        ty -= removed * ty;
        dd = tx * tx + ty * ty;
      }
    }
    float parentAngle, theta;
    String branch;
    if (Math.abs(Math.abs(sx) - Math.abs(sy)) <= .0001f) {
      float cosine = (dd - l1 * l1 - l2 * l2) / (2 * l1 * l2);
      if (cosine > 1) {
        cosine = 1;
        theta = 0;
        branch = "CIRCLE_OUTER";
        if (ik.stretch()) {
          float scale = ((float) Math.sqrt(dd) / (l1 + l2) - 1) * mix + 1;
          sx *= scale;
          if (ik.uniform())
            sy *= scale;
        }
      } else if (cosine < -1) {
        cosine = -1;
        theta = PI * bend;
        branch = "CIRCLE_INNER";
      } else {
        theta = (float) Math.acos(cosine) * bend;
        branch = "CIRCLE_ACOS";
      }
      float axisX = l1 + l2 * cosine, axisY = l2 * sin(theta);
      parentAngle = angle(ty * axisX - tx * axisY, tx * axisX + ty * axisY);
    } else {
      // Endpoint ellipse is (l1 + ax*cos(theta), ay*sin(theta)). Preserve each
      // float primitive in the quadratic, minimum-absolute root choice and sqrt.
      float second = (float) child.length() * Math.abs((float) c.scaleX()), ax = Math.abs(sx) * second,
          ay = Math.abs(sy) * second, aa = ax * ax, bb = ay * ay, direction = angle(ty, tx),
          q0 = bb * l1 * l1 + aa * dd - aa * bb, q1 = -2 * bb * l1, q2 = bb - aa, disc = q1 * q1 - 4 * q2 * q0;
      boolean solved = false;
      parentAngle = Float.NaN;
      theta = Float.NaN;
      branch = "ELLIPSE_EXTREMA";
      if (disc >= 0) {
        float q = (float) Math.sqrt(disc);
        if (q1 < 0)
          q = -q;
        q = -(q1 + q) * .5f;
        float r0 = q / q2, r1 = q0 / q, r = Math.abs(r0) < Math.abs(r1) ? r0 : r1, residual = dd - r * r;
        if (residual >= 0) {
          float y = (float) Math.sqrt(residual) * bend;
          parentAngle = direction - angle(y, r);
          theta = angle(y / Math.abs(sy), (r - l1) / Math.abs(sx));
          solved = true;
          branch = Math.abs(r0) < Math.abs(r1) ? "ELLIPSE_ROOT0" : "ELLIPSE_ROOT1";
        }
      }
      if (!solved) {
        float minAngle = PI, minX = l1 - ax, minY = 0, minR = minX * minX, maxAngle = 0, maxX = l1 + ax, maxY = 0,
            maxR = maxX * maxX, extremum = -ax * l1 / (aa - bb);
        if (extremum >= -1 && extremum <= 1) {
          float a = (float) Math.acos(extremum), x = ax * cos(a) + l1, y = ay * sin(a), radius = x * x + y * y;
          if (radius < minR) {
            minR = radius;
            minAngle = a;
            minX = x;
            minY = y;
          }
          if (radius > maxR) {
            maxR = radius;
            maxAngle = a;
            maxX = x;
            maxY = y;
          }
        }
        if (dd <= (minR + maxR) * .5f) {
          parentAngle = direction - angle(minY * bend, minX);
          theta = minAngle * bend;
          branch = "ELLIPSE_EXTREMA_INNER";
        } else {
          parentAngle = direction - angle(maxY * bend, maxX);
          theta = maxAngle * bend;
          branch = "ELLIPSE_EXTREMA_OUTER";
        }
      }
    }
    float offset = angle(in.cy, (float) c.x()) * orientation,
        parentDegrees = (parentAngle - offset) * (180 / PI) + (p.scaleX() < 0 ? 180 : 0),
        childDegrees = (theta + offset) * (180 / PI) * orientation + (c.scaleX() < 0 ? 180 : 0),
        pr = (float) p.rotation() + wrap(parentDegrees - (float) p.rotation()) * mix,
        cr = (float) c.rotation() + wrap(childDegrees - (float) c.rotation()) * mix;
    return new StaticSolution(pr, cr, sx, sy, branch);
  }

  private static void auditStatic(DocumentSnapshot d, IkConstraint ik, Input in, Set<EntityId> affected)
      throws ProjectFormatException {
    var parent = d.bone(ik.boneIds().get(0));
    var child = d.bone(ik.boneIds().get(1));
    var p = parent.local();
    var c = child.local();
    var solved = staticSolution(d, ik, in);
    float pr = solved.parentRotation, cr = solved.childRotation, sx = solved.scaleX, sy = solved.scaleY;
    Map<EntityId, M> world = new LinkedHashMap<>();
    for (var b : d.bones()) {
      M local = b.id().equals(parent.id()) ? matrix((float) p.x(), (float) p.y(), pr, sx, sy)
          : b.id().equals(child.id()) ? matrix((float) c.x(), in.cy, cr, (float) c.scaleX(), (float) c.scaleY())
              : matrix(b.local());
      world.put(b.id(), b.parentId() == null ? new M(local.a, local.b, local.c, local.d, local.x + 0f, local.y + 0f)
          : world.get(b.parentId()).child(local));
    }
    var expected = d.evaluateWorld();
    for (var id : affected) {
      var a = world.get(id);
      var e = expected.bones().get(id);
      var m = e.matrix();
      String name = d.bone(id).name();
      float[] f = { a.a, a.b, a.c, a.d, a.x, a.y, a.x + a.a * (float) d.bone(id).length(),
          a.y + a.c * (float) d.bone(id).length() };
      double[] values = { m.a(), m.b(), m.c(), m.d(), m.tx(), m.ty(), e.tip().x(), e.tip().y() };
      String[] labels = { "a", "b", "c", "d", "originX", "originY", "tipX", "tipY" };
      for (int n = 0; n < f.length; n++)
        near(f[n], values[n], ik, name + "." + labels[n]);
    }
  }
}
