package io.github.samlinb.openbone.io;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;

final class P8Range {
    private P8Range() {
    }

    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        for (var event : d.resources().events()) {
            RuntimeRange.number(event.floatValue(), "event.float");
            if (event.audio().isEmpty() && (event.volume() != 1 || event.balance() != 0))
                throw ProjectCodec.bad("events", "volume/balance require audio");
        }
        boolean switching = d.animations().stream()
                .anyMatch(a -> !a.discrete().attachments().isEmpty() || !a.discrete().drawOrder().isEmpty());
        if (switching && d.attachments().stream().anyMatch(a -> a instanceof ClippingAttachment))
            throw ProjectCodec.bad("P8.clip",
                    "Discrete attachment/draw-order combined with clipping requires a new float topology proof; project remains lossless");
        if (d.skins().size() > 1 && d.attachments().stream()
                .anyMatch(a -> a instanceof PathAttachment || a instanceof ClippingAttachment))
            throw ProjectCodec.bad("P8.skin",
                    "Multi-skin path/clipping geometry requires per-skin constraint/topology proof; project remains lossless");
        for (var c : d.constraints())
            if (c instanceof PathConstraint p)
                for (var clip : d.animations())
                    for (var k : clip.discrete().attachments())
                        if (k.slotId().equals(p.targetId()))
                            throw ProjectCodec.bad("P8.path",
                                    "Switching path constraint target attachments requires a new solver envelope");
        for (var clip : d.animations()) {
            Map<EntityId, Float> times = new HashMap<>();
            float order = -1, event = -1;
            double previousEvent = -1;
            for (var k : clip.discrete().attachments()) {
                float t = time(k.time());
                if (t <= times.getOrDefault(k.slotId(), -1f))
                    throw ProjectCodec.bad("attachment.time", "Distinct seconds collapse in float32");
                times.put(k.slotId(), t);
            }
            for (var k : clip.discrete().drawOrder()) {
                float t = time(k.time());
                if (t <= order)
                    throw ProjectCodec.bad("drawOrder.time", "Distinct seconds collapse in float32");
                order = t;
            }
            for (var k : clip.discrete().events()) {
                float t = time(k.time());
                if (k.time() != previousEvent && t <= event)
                    throw ProjectCodec.bad("event.time", "Distinct seconds collapse in float32");
                previousEvent = k.time();
                event = t;
                RuntimeRange.number(k.floatValue(), "event.float");
                var def = d.resources().events().stream().filter(e -> e.id().equals(k.eventId())).findFirst()
                        .orElseThrow();
                if (def.audio().isEmpty() && (k.volume() != 1 || k.balance() != 0))
                    throw ProjectCodec.bad("events", "volume/balance require audio");
            }
        }
    }

    private static float time(double value) throws ProjectFormatException {
        RuntimeRange.number(value, "discrete.time");
        return (float) value;
    }
}
