package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;

/**
 * Exhaustive constant-interval proof. This is not a sparse animation sampler:
 * every reachable parameter tuple is enumerated at the union of all step
 * boundaries.
 */
final class ConstraintTimelineFloatCertificate {
    private ConstraintTimelineFloatCertificate() {
    }

    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        if (d.animations().stream().flatMap(a -> a.constraintTracks().stream())
                .anyMatch(t -> t.channel().physicsParameter() >= 0)) {
            PhysicsFloatCertificate.validateDynamic(d);
            return;
        }
        if (d.animations().stream().anyMatch(a -> !a.timelines().isEmpty()))
            throw ProjectCodec.bad("animations.constraintTracks.bones",
                    "Stepped parameter proof currently requires static bone inputs; own tracks remain lossless");
        if (d.constraints().stream().anyMatch(c -> c instanceof PhysicsConstraint p && p.mix() != 0))
            throw ProjectCodec.bad("animations.constraintTracks.physics",
                    "Mixed dynamic parameter and physics clock proof is pending");
        var clean = d.animations().stream()
                .map(a -> new AnimationClip(a.id(), a.name(), a.timelines(), a.deforms(), List.of(), a.discrete()))
                .toList();
        RuntimeRange.validate(
                new DocumentSnapshot(d.bones(), d.slots(), d.attachments(), clean, d.constraints(), d.resources()));
        long work = 0;
        for (var clip : d.animations()) {
            var boundaries = new TreeSet<Double>();
            boundaries.add(0.);
            for (var t : clip.constraintTracks()) {
                float previous = -1;
                for (int i = 0; i < t.keys().size(); i++) {
                    var k = t.keys().get(i);
                    String p = "animations." + clip.name() + ".constraintTracks." + t.channel() + "[" + i + "]";
                    RuntimeRange.number(k.time(), p + ".time");
                    if ((float) k.time() <= previous)
                        throw ProjectCodec.bad(p + ".time", "Distinct key seconds collapse in float32");
                    previous = (float) k.time();
                    boundaries.add(k.time());
                    for (double v : k.values())
                        RuntimeRange.number(v, p + ".value");
                    if (i + 1 < t.keys().size()) {
                        var next = t.keys().get(i + 1);
                        for (int n = 0; n < t.channel().components(); n++)
                            if (!t.channel().discrete(n) && !k.values().get(n).equals(next.values().get(n))
                                    && k.curves().get(n).kind() != Curve.Kind.STEPPED)
                                throw ProjectCodec.bad(p + ".curve",
                                        "Continuous constraint parameter float proof is pending; use stepped segments or retain the lossless project");
                    }
                }
            }
            float previous = -1;
            for (double time : boundaries) {
                if ((float) time <= previous)
                    throw ProjectCodec.bad("animations." + clip.name() + ".constraintTracks.time",
                            "Distinct boundaries across parameter groups collapse in float32");
                previous = (float) time;
            }
            work += (long) boundaries.size() * Math.max(1, d.bones().size()) * Math.max(1, d.constraints().size());
            if (work > 200_000 || boundaries.size() > 512)
                throw ProjectCodec.bad("animations.constraintTracks.proof",
                        "Exhaustive constant-interval certificate exceeds bounded work budget");
            for (double time : boundaries) {
                var constraints = AnimationSampler.constraints(d, clip.id(), time, false);
                RuntimeRange.validate(
                        new DocumentSnapshot(d.bones(), d.slots(), d.attachments(), clean, constraints, d.resources()));
            }
        }
    }
}
