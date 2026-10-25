package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.io.ProjectCodec.*;

/**
 * Independent name/index translation for discrete 4.2.22 data. No runtime
 * classes.
 */
final class P8RuntimeJson {
    private P8RuntimeJson() {
    }

    static void definitions(ObjectNode root, DocumentSnapshot d) {
        if (d.resources().events().isEmpty())
            return;
        var out = root.putObject("events");
        for (var e : d.resources().events()) {
            var n = out.putObject(e.name()).put("int", e.intValue()).put("float", e.floatValue()).put("string",
                    e.stringValue());
            if (!e.audio().isEmpty())
                n.put("audio", e.audio()).put("volume", e.volume()).put("balance", e.balance());
        }
    }

    static List<EventDefinition> definitions(JsonNode root) throws ProjectFormatException {
        var result = new ArrayList<EventDefinition>();
        if (!root.has("events"))
            return result;
        object(root.get("events"), "events");
        for (var it = root.get("events").fields(); it.hasNext();) {
            var e = it.next();
            var n = e.getValue();
            only(n, "events." + e.getKey(), "int", "float", "string", "audio", "volume", "balance");
            String audio = text(n, "audio", "", true);
            if (audio.isEmpty() && (n.has("volume") || n.has("balance")))
                throw bad("events", "volume/balance require audio");
            result.add(new EventDefinition(EntityId.create(), e.getKey(), integer(n, "int", 0), number(n, "float", 0),
                    text(n, "string", "", true), audio, number(n, "volume", 1), number(n, "balance", 0)));
        }
        return result;
    }

    static void write(ObjectNode node, DocumentSnapshot d, AnimationClip clip) {
        Map<EntityId, Slot> slots = new HashMap<>();
        d.slots().forEach(s -> slots.put(s.id(), s));
        if (!clip.discrete().attachments().isEmpty()) {
            var tracks = node.putObject("slots");
            Map<EntityId, ArrayNode> rows = new HashMap<>();
            for (var k : clip.discrete().attachments()) {
                var row = rows.computeIfAbsent(k.slotId(),
                        id -> tracks.putObject(slots.get(id).name()).putArray("attachment"));
                var n = row.addObject().put("time", k.time());
                if (k.name() == null)
                    n.putNull("name");
                else
                    n.put("name", k.name());
            }
        }
        if (!clip.discrete().drawOrder().isEmpty()) {
            var out = node.putArray("drawOrder");
            for (var k : clip.discrete().drawOrder()) {
                var n = out.addObject().put("time", k.time());
                var offsets = n.putArray("offsets");
                for (int i = 0; i < d.slots().size(); i++)
                    offsets.addObject().put("slot", d.slots().get(i).name()).put("offset",
                            k.slots().indexOf(d.slots().get(i).id()) - i);
            }
        }
        if (!clip.discrete().events().isEmpty()) {
            var out = node.putArray("events");
            for (var k : clip.discrete().events()) {
                var e = d.resources().events().stream().filter(v -> v.id().equals(k.eventId())).findFirst()
                        .orElseThrow();
                var n = out.addObject().put("time", k.time()).put("name", e.name()).put("int", k.intValue())
                        .put("float", k.floatValue()).put("string", k.stringValue());
                if (!e.audio().isEmpty())
                    n.put("volume", k.volume()).put("balance", k.balance());
            }
        }
    }

    static DiscreteTracks read(JsonNode node, List<Slot> slots, List<EventDefinition> definitions)
            throws ProjectFormatException {
        Map<String, Slot> byName = new LinkedHashMap<>();
        slots.forEach(s -> byName.put(s.name(), s));
        var a = new ArrayList<DiscreteTracks.AttachmentKey>();
        var o = new ArrayList<DiscreteTracks.OrderKey>();
        var e = new ArrayList<DiscreteTracks.EventKey>();
        if (node.has("slots")) {
            object(node.get("slots"), "animation.slots");
            for (var it = node.get("slots").fields(); it.hasNext();) {
                var row = it.next();
                Slot slot = byName.get(row.getKey());
                if (slot == null)
                    throw bad("animation.slots", "Unknown slot");
                only(row.getValue(), "animation.slots", "attachment");
                double previous = -1;
                for (var k : array(row.getValue(), "attachment", "animation.slots")) {
                    only(k, "attachment.key", "time", "name");
                    double time = number(k, "time", 0);
                    if (time <= previous)
                        throw bad("attachment.time", "Keys must strictly increase");
                    previous = time;
                    a.add(new DiscreteTracks.AttachmentKey(EntityId.create(), time, slot.id(),
                            text(k, "name", null, true)));
                }
            }
        }
        if (node.has("drawOrder")) {
            double previous = -1;
            for (var k : array(node, "drawOrder", "animation")) {
                only(k, "drawOrder.key", "time", "offsets");
                double time = number(k, "time", 0);
                if (time <= previous)
                    throw bad("drawOrder.time", "Keys must strictly increase");
                previous = time;
                var ids = new ArrayList<EntityId>(Collections.nCopies(slots.size(), null));
                var unchanged = new ArrayList<EntityId>();
                var moved = new HashSet<EntityId>();
                int source = -1;
                if (k.has("offsets"))
                    for (var offset : array(k, "offsets", "drawOrder")) {
                        only(offset, "drawOrder.offset", "slot", "offset");
                        Slot slot = byName.get(text(offset, "slot", null, false));
                        if (slot == null)
                            throw bad("drawOrder.slot", "Unknown slot");
                        int index = slots.indexOf(slot), target = index + integer(offset, "offset", 0);
                        if (index <= source || target < 0 || target >= slots.size() || ids.get(target) != null
                                || !moved.add(slot.id()))
                            throw bad("drawOrder.offset",
                                    "Offsets must have increasing source indices and distinct valid destinations");
                        source = index;
                        ids.set(target, slot.id());
                    }
                for (var slot : slots)
                    if (!moved.contains(slot.id()))
                        unchanged.add(slot.id());
                int cursor = 0;
                for (int i = 0; i < ids.size(); i++)
                    if (ids.get(i) == null)
                        ids.set(i, unchanged.get(cursor++));
                o.add(new DiscreteTracks.OrderKey(EntityId.create(), time, ids));
            }
        }
        if (node.has("events")) {
            double previous = -1;
            for (var k : array(node, "events", "animation")) {
                only(k, "event.key", "time", "name", "int", "float", "string", "volume", "balance");
                double time = number(k, "time", 0);
                if (time < previous)
                    throw bad("event.time", "Keys must not decrease");
                previous = time;
                String name = text(k, "name", null, false);
                var def = definitions.stream().filter(v -> v.name().equals(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown event: " + name));
                if (def.audio().isEmpty() && (k.has("volume") || k.has("balance")))
                    throw bad("event", "volume/balance require audio");
                e.add(new DiscreteTracks.EventKey(EntityId.create(), time, def.id(), integer(k, "int", def.intValue()),
                        number(k, "float", def.floatValue()), text(k, "string", def.stringValue(), false),
                        number(k, "volume", def.volume()), number(k, "balance", def.balance())));
            }
        }
        return new DiscreteTracks(a, o, e);
    }

    /**
     * Structural scan collects all new paths alongside the legacy unknown-field
     * report.
     */
    static void scan(JsonNode root, List<String> errors) {
        if (root.has("events") && scanObject(root.get("events"), "$.events", errors))
            for (var it = root.get("events").fields(); it.hasNext();) {
                var e = it.next();
                scanObject(e.getValue(), "$.events." + e.getKey(), errors, "int", "float", "string", "audio", "volume",
                        "balance");
            }
        if (!root.path("animations").isObject())
            return;
        for (var it = root.get("animations").fields(); it.hasNext();) {
            var entry = it.next();
            var n = entry.getValue();
            String p = "$.animations." + entry.getKey();
            if (n.has("slots") && scanObject(n.get("slots"), p + ".slots", errors))
                for (var rows = n.get("slots").fields(); rows.hasNext();) {
                    var row = rows.next();
                    String q = p + ".slots." + row.getKey();
                    if (scanObject(row.getValue(), q, errors, "attachment") && row.getValue().has("attachment")
                            && scanArray(row.getValue().get("attachment"), q + ".attachment", errors)) {
                        int i = 0;
                        for (var k : row.getValue().get("attachment"))
                            scanObject(k, q + ".attachment[" + i++ + "]", errors, "time", "name");
                    }
                }
            if (n.has("events") && scanArray(n.get("events"), p + ".events", errors)) {
                int i = 0;
                for (var k : n.get("events"))
                    scanObject(k, p + ".events[" + i++ + "]", errors, "time", "name", "int", "float", "string",
                            "volume", "balance");
            }
            if (n.has("drawOrder") && scanArray(n.get("drawOrder"), p + ".drawOrder", errors)) {
                int i = 0;
                for (var k : n.get("drawOrder")) {
                    String q = p + ".drawOrder[" + i++ + "]";
                    if (scanObject(k, q, errors, "time", "offsets") && k.has("offsets")
                            && scanArray(k.get("offsets"), q + ".offsets", errors)) {
                        int j = 0;
                        for (var o : k.get("offsets"))
                            scanObject(o, q + ".offsets[" + j++ + "]", errors, "slot", "offset");
                    }
                }
            }
        }
    }

    private static boolean scanObject(JsonNode n, String p, List<String> errors, String... allowed) {
        if (n == null || !n.isObject()) {
            errors.add(p + " (expected object)");
            return false;
        }
        if (allowed.length > 0) {
            var keys = Set.of(allowed);
            for (var it = n.fieldNames(); it.hasNext();) {
                var key = it.next();
                if (!keys.contains(key))
                    errors.add(p + "." + key);
            }
        }
        return true;
    }

    private static boolean scanArray(JsonNode n, String p, List<String> errors) {
        if (n == null || !n.isArray()) {
            errors.add(p + " (expected array)");
            return false;
        }
        return true;
    }

    private static void object(JsonNode n, String p) throws ProjectFormatException {
        if (n == null || !n.isObject())
            throw bad(p, "Expected object");
    }

    private static void only(JsonNode n, String p, String... allowed) throws ProjectFormatException {
        object(n, p);
        Set<String> keys = Set.of(allowed);
        for (var it = n.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!keys.contains(key))
                throw bad(p + "." + key, "Unknown field");
        }
    }

    private static double number(JsonNode n, String key, double fallback) throws ProjectFormatException {
        return n.has(key) ? ProjectCodec.number(n, key, "P8") : fallback;
    }

    private static int integer(JsonNode n, String key, int fallback) throws ProjectFormatException {
        return n.has(key) ? ProjectCodec.integer(n.get(key), "P8." + key) : fallback;
    }

    private static String text(JsonNode n, String key, String fallback, boolean nullable)
            throws ProjectFormatException {
        if (!n.has(key))
            return fallback;
        if (nullable && n.get(key).isNull())
            return fallback;
        return ProjectCodec.text(n, key, "P8");
    }
}
