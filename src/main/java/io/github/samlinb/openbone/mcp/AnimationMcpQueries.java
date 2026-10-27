package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.util.*;

/** Read-only projection of the shared animation model. Invoked on EDT by EditorMcpTools. */
final class AnimationMcpQueries {
    private static final List<String> TYPES = List.of("bone", "deform", "constraint", "slot_color", "attachment", "draw_order", "event");
    private record Track(ObjectNode metadata, List<? extends DiscreteTracks.Timed> keys) {}

    // A separate bundle keeps this protocol module's text in the editor's selected language.
    static String message(String key, Object... args) {
        return java.text.MessageFormat.format(ResourceBundle.getBundle("i18n.mcp_queries",
                io.github.samlinb.openbone.i18n.M.locale()).getString(key), args);
    }

    static JsonNode summary(AnimationClip clip, ObjectMapper json) {
        var counts = counts(clip);
        var row = json.createObjectNode().put("id", clip.id().value()).put("name", clip.name())
                .put("duration", clip.duration()).put("keyCount", clip.keys().size())
                .put("totalKeyCount", counts.values().stream().mapToLong(Long::longValue).sum());
        row.set("keyCounts", json.valueToTree(counts));
        return row;
    }

    private static Map<String, Long> counts(AnimationClip clip) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("bone", clip.timelines().stream().mapToLong(t -> t.keys().size()).sum());
        counts.put("deform", clip.deforms().stream().mapToLong(t -> t.keys().size()).sum());
        counts.put("constraint", clip.constraintTracks().stream().mapToLong(t -> t.keys().size()).sum());
        counts.put("slot_color", clip.discrete().colors().stream().mapToLong(t -> t.keys().size()).sum());
        counts.put("attachment", (long) clip.discrete().attachments().size());
        counts.put("draw_order", (long) clip.discrete().drawOrder().size());
        counts.put("event", (long) clip.discrete().events().size());
        return counts;
    }

    static Map<String, Object> query(DocumentSnapshot document, JsonNode args, String revision,
            int offset, int limit, ObjectMapper json) {
        EntityId animation = id(args, "animationId");
        AnimationClip clip = document.animation(animation);
        String type = args.has("trackType") ? text(args, "trackType") : "bone";
        if (!TYPES.contains(type) && !type.equals("all") && !type.equals("non_bone"))
            throw new IllegalArgumentException(message("trackType"));
        EntityId bone = args.has("boneId") ? id(args, "boneId") : null;
        if (bone != null) {
            document.bone(bone);
            if (!type.equals("bone") && !type.equals("all"))
                throw new IllegalArgumentException(message("boneFilter"));
        }
        EntityId target = args.has("targetId") ? id(args, "targetId") : null;
        if (bone != null && target != null && !bone.equals(target))
            throw new IllegalArgumentException(message("conflictingTarget"));
        var names = new HashMap<EntityId, String>();
        document.bones().forEach(b -> names.put(b.id(), b.name()));
        document.slots().forEach(s -> names.put(s.id(), s.name()));
        document.attachments().forEach(a -> names.put(a.id(), a.name()));
        document.constraints().forEach(c -> names.put(c.id(), c.name()));
        document.resources().events().forEach(e -> names.put(e.id(), e.name()));
        names.put(clip.id(), clip.name());
        if (target != null && !names.containsKey(target))
            throw new IllegalArgumentException(message("unknownTarget", target.value()));
        String channel = args.has("channel") ? text(args, "channel") : null;
        var channels = new HashSet<>(List.of("DEFORM", "ATTACHMENT", "DRAW_ORDER", "EVENT", "RGBA", "RGB", "ALPHA"));
        for (var c : Channel.values()) channels.add(c.name());
        for (var c : ConstraintChannel.values()) channels.add(c.name());
        if (channel != null && !channels.contains(channel))
            throw new IllegalArgumentException(message("channel", channel));

        var tracks = tracks(document, clip, names, json).stream().filter(t ->
                (type.equals("all") || type.equals(t.metadata.path("trackType").asText())
                        || type.equals("non_bone") && !t.metadata.path("trackType").asText().equals("bone"))
                && (target == null || target.value().equals(t.metadata.path("targetId").asText()))
                && (bone == null || bone.value().equals(t.metadata.path("boneId").asText()))
                && (channel == null || channel.equals(t.metadata.path("channel").asText()))).toList();
        long total = tracks.stream().mapToLong(t -> t.keys.size()).sum();
        // Page references first: large deform arrays are serialized only for the requested page.
        var items = tracks.stream().flatMap(t -> t.keys.stream().map(k -> Map.entry(t.metadata, k)))
                .skip(offset).limit(limit).map(entry -> {
                    ObjectNode row = json.valueToTree(entry.getValue());
                    row.setAll(entry.getKey());
                    return row;
                }).toList();
        long next = Math.min((long) offset + items.size(), total);
        return Map.of("revision", revision, "items", items, "total", total, "offset", offset,
                "hasMore", next < total, "nextOffset", next, "trackType", type,
                "animationKeyCounts", counts(clip));
    }

    private static List<Track> tracks(DocumentSnapshot d, AnimationClip clip, Map<EntityId, String> names, ObjectMapper json) {
        var tracks = new ArrayList<Track>();
        for (var t : clip.timelines()) {
            var row = metadata(json, names, "bone", t.boneId(), t.channel().name(), "continuous")
                    .put("boneId", t.boneId().value()).put("space", "parent_local")
                    .put("valueSemantics", t.channel() == Channel.SCALE ? "setup_multipliers" : "setup_offsets");
            row.set("components", json.valueToTree(switch (t.channel()) {
                case TRANSLATE -> List.of("x", "y"); case ROTATE -> List.of("rotation");
                case SCALE -> List.of("scaleX", "scaleY"); case SHEAR -> List.of("shearX", "shearY");
            }));
            row.put("units", t.channel() == Channel.ROTATE || t.channel() == Channel.SHEAR ? "degrees"
                    : t.channel() == Channel.SCALE ? "factor" : "project_units");
            tracks.add(new Track(row, t.keys()));
        }
        for (var t : clip.deforms()) {
            var attachment = d.attachment(t.attachmentId());
            var row = metadata(json, names, "deform", t.attachmentId(), "DEFORM", "continuous")
                    .put("attachmentId", t.attachmentId().value()).put("slotId", attachment.slotId().value())
                    .put("attachmentType", attachment.getClass().getSimpleName()).put("valueSemantics", "setup_offsets")
                    .put("units", "project_units").put("componentCount", t.keys().get(0).values().size());
            var weights = attachment instanceof PathAttachment p ? p.weights()
                    : attachment instanceof ClippingAttachment c ? c.weights() : ((MeshAttachment) attachment).weights();
            row.put("weighted", !weights.isEmpty()).put("space", weights.isEmpty() ? "slot_bone_local" : "influence_bone_local")
                    .put("valueLayout", weights.isEmpty() ? "xy_per_vertex" : "xy_per_influence");
            var bindings = row.putArray("valueBindings");
            if (weights.isEmpty()) {
                for (int v = 0; v < t.keys().get(0).values().size() / 2; v++)
                    bindings.addObject().put("vertexIndex", v).put("valueOffset", v * 2);
            } else {
                int component = 0;
                for (int v = 0; v < weights.size(); v++) for (int i = 0; i < weights.get(v).size(); i++) {
                    bindings.addObject().put("vertexIndex", v).put("influenceIndex", i)
                            .put("boneId", weights.get(v).get(i).boneId().value()).put("valueOffset", component);
                    component += 2;
                }
            }
            tracks.add(new Track(row, t.keys()));
        }
        for (var t : clip.constraintTracks()) {
            var row = metadata(json, names, "constraint", t.constraintId(), t.channel().name(), "continuous")
                    .put("constraintId", t.constraintId().value()).put("valueSemantics", "absolute");
            row.set("components", json.valueToTree(components(t.channel())));
            var discrete = row.putArray("discreteComponents");
            for (int i = 0; i < t.channel().components(); i++) if (t.channel().discrete(i)) discrete.add(i);
            var constraint = d.constraint(t.constraintId());
            if (constraint instanceof PathConstraint p)
                row.put("positionMode", p.positionMode().name()).put("spacingMode", p.spacingMode().name());
            tracks.add(new Track(row, t.keys()));
        }
        for (var t : clip.discrete().colors()) {
            var row = metadata(json, names, "slot_color", t.slotId(), t.channel().name(), "continuous")
                    .put("slotId", t.slotId().value()).put("valueSemantics", "absolute").put("units", "unit_interval");
            row.set("components", json.valueToTree(t.channel() == SlotColorTimeline.Channel.ALPHA ? List.of("a")
                    : t.channel() == SlotColorTimeline.Channel.RGB ? List.of("r", "g", "b") : List.of("r", "g", "b", "a")));
            tracks.add(new Track(row, t.keys()));
        }
        for (var k : clip.discrete().attachments())
            tracks.add(new Track(metadata(json, names, "attachment", k.slotId(), "ATTACHMENT", "stepped"), List.of(k)));
        if (!clip.discrete().drawOrder().isEmpty())
            tracks.add(new Track(metadata(json, names, "draw_order", clip.id(), "DRAW_ORDER", "stepped"), clip.discrete().drawOrder()));
        for (var k : clip.discrete().events())
            tracks.add(new Track(metadata(json, names, "event", k.eventId(), "EVENT", "instant"), List.of(k)));
        return tracks;
    }

    private static ObjectNode metadata(ObjectMapper json, Map<EntityId, String> names, String type,
            EntityId target, String channel, String interpolation) {
        return json.createObjectNode().put("trackType", type).put("targetId", target.value())
                .put("targetName", names.get(target)).put("channel", channel).put("interpolation", interpolation);
    }

    private static List<String> components(ConstraintChannel channel) {
        return switch (channel) {
            case IK -> List.of("mix", "softness", "bendPositive", "compress", "stretch");
            case TRANSFORM_MIX -> List.of("mixRotate", "mixX", "mixY", "mixScaleX", "mixScaleY", "mixShearY");
            case PATH_POSITION -> List.of("position"); case PATH_SPACING -> List.of("spacing");
            case PATH_MIX -> List.of("mixRotate", "mixX", "mixY");
            default -> List.of(channel.name().substring("PHYSICS_".length()).toLowerCase(Locale.ROOT));
        };
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 64)
            throw new IllegalArgumentException(message("text", field));
        return value.textValue();
    }

    private static EntityId id(JsonNode args, String field) {
        try { return new EntityId(text(args, field)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(message("uuid", field)); }
    }
}
