package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.util.*;

/** Strict protocol adapters to the same constraint/deform commands used by desktop editing. */
final class AnimationMcpEdits {
    private AnimationMcpEdits() {}

    static String message(String key, Object... args) {
        return java.text.MessageFormat.format(ResourceBundle.getBundle("i18n.mcp_animation_edits",
                io.github.samlinb.openbone.i18n.M.locale()).getString(key), args);
    }

    static ArrayNode schemas(ObjectMapper json) {
        try {
            var schemas = json.createArrayNode();
            for (String op : List.of("put_constraint_keyframe", "put_deform_keyframe")) {
                boolean constraint = op.equals("put_constraint_keyframe");
                var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
                var properties = schema.putObject("properties");
                properties.putObject("op").put("const", op);
                properties.putObject("animationId").put("type", "string");
                String target = constraint ? "constraintId" : "attachmentId";
                properties.putObject(target).put("type", "string");
                properties.putObject("time").put("type", "number").put("minimum", 0).put("maximum", 600);
                var values = properties.putObject("values").put("type", "array")
                        .put("minItems", constraint ? 1 : 2).put("maxItems", constraint ? 6 : 65536);
                values.putObject("items").put("type", "number").put("minimum", -1e9).put("maximum", 1e9);
                properties.set("curve", json.readTree("""
                    {"oneOf":[{"enum":["linear","stepped"]},{"type":"object","additionalProperties":false,
                    "properties":{"kind":{"const":"bezier"},"x1":{"type":"number","minimum":0,"maximum":1},
                    "y1":{"type":"number","minimum":-1000000000,"maximum":1000000000},
                    "x2":{"type":"number","minimum":0,"maximum":1},
                    "y2":{"type":"number","minimum":-1000000000,"maximum":1000000000}},
                    "required":["kind","x1","y1","x2","y2"]}]}
                    """));
                var required = schema.putArray("required");
                for (String field : List.of("op", "animationId", target, "time", "values")) required.add(field);
                if (constraint) {
                    var channels = properties.putObject("channel").putArray("enum");
                    for (var channel : ConstraintChannel.values()) channels.add(channel.name());
                    required.add("channel");
                }
                schemas.add(schema);
            }
            return schemas;
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    static DocumentSnapshot apply(DocumentSnapshot document, JsonNode edit, String op) {
        boolean constraint = op.equals("put_constraint_keyframe");
        if (constraint) fields(edit, "op", "animationId", "constraintId", "channel", "time", "values", "curve");
        else fields(edit, "op", "animationId", "attachmentId", "time", "values", "curve");
        var clip = id(edit, "animationId");
        var target = id(edit, constraint ? "constraintId" : "attachmentId");
        double time = number(edit.get("time"), "time", 0, 600);
        var input = edit.get("values");
        if (input == null || !input.isArray() || input.isEmpty() || input.size() > (constraint ? 6 : 65536))
            throw new IllegalArgumentException(message("values"));
        var values = new ArrayList<Double>();
        for (var value : input) values.add(number(value, "values", -1e9, 1e9));
        DocumentSnapshot next;
        Keyframe key;
        if (constraint) {
            ConstraintChannel channel;
            try { channel = ConstraintChannel.valueOf(text(edit, "channel")); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(message("channel")); }
            next = AnimationEdits.putConstraint(document, clip, target, channel, time, values);
            key = next.animation(clip).constraintTracks().stream()
                    .filter(t -> t.constraintId().equals(target) && t.channel() == channel)
                    .flatMap(t -> t.keys().stream()).filter(k -> k.time() == time).findFirst().orElseThrow();
        } else {
            next = DeformEdits.put(document, clip, target, time, values);
            key = next.animation(clip).deforms().stream().filter(t -> t.attachmentId().equals(target))
                    .flatMap(t -> t.keys().stream()).filter(k -> k.time() == time).findFirst().orElseThrow();
        }
        // Shared curve editing expands one deform curve to all components and keeps IK flags stepped.
        if (edit.has("curve")) next = AnimationEdits.curves(next, clip, Set.of(key.id()), List.of(curve(edit.get("curve"))));
        return next;
    }

    private static Curve curve(JsonNode value) {
        if (value.isTextual()) return switch (value.textValue()) {
            case "linear" -> Curve.LINEAR; case "stepped" -> Curve.STEPPED;
            default -> throw new IllegalArgumentException(message("curve"));
        };
        fields(value, "kind", "x1", "y1", "x2", "y2");
        if (!text(value, "kind").equals("bezier")) throw new IllegalArgumentException(message("curve"));
        return new Curve(Curve.Kind.BEZIER, number(value.get("x1"), "x1", 0, 1), number(value.get("y1"), "y1", -1e9, 1e9),
                number(value.get("x2"), "x2", 0, 1), number(value.get("y2"), "y2", -1e9, 1e9));
    }

    private static void fields(JsonNode value, String... allowed) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(message("object"));
        var names = Set.of(allowed);
        value.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw new IllegalArgumentException(message("field", name));
        });
    }

    private static String text(JsonNode value, String field) {
        var text = value.get(field);
        if (text == null || !text.isTextual() || text.textValue().isBlank() || text.textValue().length() > 64)
            throw new IllegalArgumentException(message("text", field));
        return text.textValue();
    }

    private static EntityId id(JsonNode value, String field) {
        try { return new EntityId(text(value, field)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(message("uuid", field)); }
    }

    private static double number(JsonNode value, String field, double min, double max) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())
                || value.doubleValue() < min || value.doubleValue() > max)
            throw new IllegalArgumentException(message("number", field, min, max));
        return value.doubleValue();
    }
}
