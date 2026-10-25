package io.github.samlinb.openbone.core.model;

import java.util.Objects;
import io.github.samlinb.openbone.core.math.Finite;
import static io.github.samlinb.openbone.i18n.M.m;

public record EventDefinition(EntityId id, String name, int intValue, double floatValue, String stringValue,
        String audio, double volume, double balance) {
    public EventDefinition {
        Objects.requireNonNull(id);
        Names.require(name);
        Finite.require(floatValue, "event.float");
        Objects.requireNonNull(stringValue);
        Objects.requireNonNull(audio);
        Finite.require(volume, "event.volume");
        Finite.require(balance, "event.balance");
        if (volume < 0 || volume > 1 || balance < -1 || balance > 1)
            throw new IllegalArgumentException(m("eventdefinition.0001"));
    }
}
