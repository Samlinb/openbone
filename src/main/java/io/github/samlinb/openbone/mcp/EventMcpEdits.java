package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.util.*;

/** Event protocol adapters. All model changes use the desktop's immutable application edits. */
final class EventMcpEdits {
    private EventMcpEdits() {}

    static String message(String key, Object... args) {
        return java.text.MessageFormat.format(ResourceBundle.getBundle("i18n.mcp_event_edits",
                io.github.samlinb.openbone.i18n.M.locale()).getString(key), args);
    }

    static ArrayNode schemas(ObjectMapper json) {
        var result = json.createArrayNode();
        for (String op : List.of("put_event_definition", "put_event_keyframe")) {
            boolean key = op.equals("put_event_keyframe");
            var schema = result.addObject().put("type", "object").put("additionalProperties", false);
            var p = schema.putObject("properties");
            p.putObject("op").put("const", op);
            p.putObject("eventId").put("type", "string");
            var required = schema.putArray("required").add("op").add("eventId");
            if (key) {
                p.putObject("animationId").put("type", "string");
                p.putObject("keyId").put("type", "string");
                p.putObject("time").put("type", "number").put("minimum", 0).put("maximum", 600);
                required.add("animationId").add("keyId").add("time");
            } else {
                p.putObject("name").put("type", "string").put("minLength", 1).put("maxLength", 256);
                required.add("name");
            }
            p.putObject("intValue").put("type", "integer").put("minimum", Integer.MIN_VALUE).put("maximum", Integer.MAX_VALUE);
            p.putObject("floatValue").put("type", "number").put("minimum", -1e9).put("maximum", 1e9);
            p.putObject("stringValue").put("type", "string").put("maxLength", 4096);
            p.putObject("volume").put("type", "number").put("minimum", 0).put("maximum", 1);
            p.putObject("balance").put("type", "number").put("minimum", -1).put("maximum", 1);
        }
        return result;
    }

    static DocumentSnapshot apply(DocumentSnapshot d, JsonNode e, String op) {
        if (op.equals("put_event_definition")) {
            fields(e, "op", "eventId", "name", "intValue", "floatValue", "stringValue", "volume", "balance");
            var id = id(e, "eventId");
            var old = d.resources().events().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
            var name = text(e, "name", 256);
            if (name.isBlank() || !name.equals(name.strip())) throw new IllegalArgumentException(message("name"));
            var updated = new EventDefinition(id, name,
                    integer(e, old == null ? 0 : old.intValue()),
                    number(e, "floatValue", old == null ? 0 : old.floatValue(), -1e9, 1e9),
                    optionalText(e, old == null ? "" : old.stringValue()), old == null ? "" : old.audio(),
                    number(e, "volume", old == null ? 1 : old.volume(), 0, 1),
                    number(e, "balance", old == null ? 0 : old.balance(), -1, 1));
            audioPayload(updated.audio(), updated.volume(), updated.balance());
            return ExtendedEdits.eventDefinition(d, updated);
        }
        fields(e, "op", "animationId", "keyId", "eventId", "time", "intValue", "floatValue", "stringValue", "volume", "balance");
        var clip = id(e, "animationId");
        var key = id(e, "keyId");
        var event = id(e, "eventId");
        var definition = d.resources().events().stream().filter(v -> v.id().equals(event)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(message("unknownEvent", event.value())));
        var old = d.animation(clip).discrete().events().stream().filter(v -> v.id().equals(key)).findFirst().orElse(null);
        double time = number(e, "time", Double.NaN, 0, 600);
        var updated = new DiscreteTracks.EventKey(key, time, event,
                integer(e, old == null ? definition.intValue() : old.intValue()),
                number(e, "floatValue", old == null ? definition.floatValue() : old.floatValue(), -1e9, 1e9),
                optionalText(e, old == null ? definition.stringValue() : old.stringValue()),
                number(e, "volume", old == null ? definition.volume() : old.volume(), 0, 1),
                number(e, "balance", old == null ? definition.balance() : old.balance(), -1, 1));
        audioPayload(definition.audio(), updated.volume(), updated.balance());
        return ExtendedEdits.event(d, clip, updated);
    }

    private static void audioPayload(String audio, double volume, double balance) {
        if (audio.isEmpty() && (volume != 1 || balance != 0))
            throw new IllegalArgumentException(message("audioRequired"));
    }

    /** A mixed remove request stays one transaction; unknown UUIDs are rejected by AnimationEdits. */
    static DocumentSnapshot remove(DocumentSnapshot d, EntityId clip, Set<EntityId> ids) {
        var continuous = new HashSet<>(ids);
        for (var key : d.animation(clip).discrete().keys()) {
            if (continuous.remove(key.id())) d = ExtendedEdits.removeDiscrete(d, clip, key.id());
        }
        return continuous.isEmpty() ? d : AnimationEdits.remove(d, clip, continuous);
    }

    private static void fields(JsonNode e, String... allowed) {
        if (e == null || !e.isObject()) throw new IllegalArgumentException(message("object"));
        var names = Set.of(allowed);
        e.fieldNames().forEachRemaining(n -> { if (!names.contains(n)) throw new IllegalArgumentException(message("field", n)); });
    }

    private static String text(JsonNode e, String field, int max) {
        var v = e.get(field);
        if (v == null || !v.isTextual() || v.textValue().length() > max)
            throw new IllegalArgumentException(message("text", field, max));
        return v.textValue();
    }

    private static EntityId id(JsonNode e, String field) {
        try { return new EntityId(text(e, field, 64)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(message("uuid", field)); }
    }

    private static String optionalText(JsonNode e, String fallback) {
        return e.has("stringValue") ? text(e, "stringValue", 4096) : fallback;
    }

    private static int integer(JsonNode e, int fallback) {
        var v = e.get("intValue");
        if (v == null) return fallback;
        if (!v.isIntegralNumber() || !v.canConvertToInt()) throw new IllegalArgumentException(message("integer"));
        return v.intValue();
    }

    private static double number(JsonNode e, String field, double fallback, double min, double max) {
        var v = e.get(field);
        if (v == null && Double.isFinite(fallback)) return fallback;
        if (v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) || v.doubleValue() < min || v.doubleValue() > max)
            throw new IllegalArgumentException(message("number", field, min, max));
        return v.doubleValue();
    }
}
