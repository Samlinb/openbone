package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Schema translation only. Curves and coordinates stay in the 4.x absolute
 * contract.
 */
final class SpineVersionJson {
    private SpineVersionJson() {
    }

    static void rename(ObjectNode node, String old, String next) {
        if (node.has(old))
            node.set(next, node.remove(old));
    }

    static ObjectNode normalize(ObjectNode root, SpineVersion version) throws ProjectFormatException {
        if (root.path("skeleton").path("audio").isNull())
            ((ObjectNode) root.path("skeleton")).remove("audio");
        // Official JSON exports round key times to four decimals and controls to
        // three. Clamp only the resulting half-unit time quantization at an endpoint.
        clampRoundedControlTimes(root.path("animations"));
        if (version == SpineVersion.V40 || version == SpineVersion.V41) {
            for (var bone : root.path("bones"))
                rename((ObjectNode) bone, "transform", "inherit");
        }
        if (version == SpineVersion.V40)
            for (var animation : root.path("animations")) {
                if (animation.has("deform")) {
                    var deform = ((ObjectNode) animation).remove("deform");
                    var attachments = ((ObjectNode) animation).putObject("attachments");
                    deform.fields()
                            .forEachRemaining(s -> s.getValue().fields()
                                    .forEachRemaining(slot -> slot.getValue().fields()
                                            .forEachRemaining(a -> attachments.withObject("/" + escape(s.getKey()))
                                                    .withObject("/" + escape(slot.getKey())).putObject(a.getKey())
                                                    .set("deform", a.getValue()))));
                }
            }
        if (version == SpineVersion.V43) {
            if (root.has("constraints")) {
                if (!root.get("constraints").isArray())
                    throw ProjectCodec.bad("$.constraints", "Expected array");
                int order = 0;
                for (var item : root.remove("constraints")) {
                    if (!(item instanceof ObjectNode constraint))
                        throw ProjectCodec.bad("$.constraints", "Expected object");
                    String type = constraint.path("type").asText();
                    if (!Set.of("ik", "transform", "path", "physics").contains(type))
                        throw ProjectCodec.bad("$.constraints", "Unsupported 4.3 constraint: " + type);
                    constraint.remove("type");
                    constraint.put("order", order++);
                    if (type.equals("transform"))
                        transform43(constraint, false);
                    if (type.equals("path"))
                        rename(constraint, "slot", "target");
                    if (!type.equals("transform") && constraint.has("scaleY"))
                        throw ProjectCodec.bad("$.constraints.scaleY",
                                "4.3 scaleY mode has no equivalent in the editor");
                    root.withArray(type).add(constraint);
                }
            }
        }
        ((ObjectNode) root.path("skeleton")).put("spine", SkeletonJsonCodec.VERSION);
        return root;
    }

    static void export(ObjectNode root, SpineVersion version, String sourceVersion) throws ProjectFormatException {
        if (version != SpineVersion.V42 && version != SpineVersion.V43 && !root.path("physics").isEmpty())
            throw ProjectCodec.bad("$.physics", "Spine " + version.family() + " has no physics constraints");
        if (version == SpineVersion.V40 || version == SpineVersion.V41)
            for (var bone : root.path("bones"))
                rename((ObjectNode) bone, "inherit", "transform");
        if (version == SpineVersion.V40)
            for (var animation : root.path("animations")) {
                if (animation.has("attachments")) {
                    var attachments = ((ObjectNode) animation).remove("attachments");
                    var deform = ((ObjectNode) animation).putObject("deform");
                    for (var skins = attachments.fields(); skins.hasNext();) {
                        var skin = skins.next();
                        for (var slots = skin.getValue().fields(); slots.hasNext();) {
                            var slot = slots.next();
                            for (var all = slot.getValue().fields(); all.hasNext();) {
                                var attachment = all.next();
                                if (attachment.getValue().has("sequence"))
                                    throw ProjectCodec.bad("$.animations.attachments", "Spine 4.0 has no sequences");
                                deform.withObject("/" + escape(skin.getKey())).withObject("/" + escape(slot.getKey()))
                                        .set(attachment.getKey(), attachment.getValue().path("deform"));
                            }
                        }
                    }
                }
            }
        if (version == SpineVersion.V43) {
            var constraints = new ArrayList<ObjectNode>();
            for (String type : List.of("ik", "transform", "path", "physics")) {
                var items = root.remove(type);
                if (items == null)
                    continue;
                for (var item : items) {
                    var constraint = (ObjectNode) item;
                    constraint.put("type", type);
                    if (type.equals("transform"))
                        transform43(constraint, true);
                    if (type.equals("path"))
                        rename(constraint, "target", "slot");
                    if (type.equals("ik")) {
                        if (constraint.path("uniform").asBoolean())
                            throw ProjectCodec.bad("$.ik.uniform",
                                    "Uniform IK scaling needs an explicit 4.3 scaleY conversion");
                        constraint.remove("uniform");
                    }
                    constraints.add(constraint);
                }
            }
            constraints.sort(Comparator.comparingInt(c -> c.path("order").asInt()));
            var output = root.putArray("constraints");
            for (var constraint : constraints) {
                constraint.remove("order");
                output.add(constraint);
            }
        }
        ((ObjectNode) root.path("skeleton")).put("spine", sourceVersion);
    }

    private static void transform43(ObjectNode node, boolean exporting) throws ProjectFormatException {
        if (exporting) {
            rename(node, "target", "source");
            boolean local = node.path("local").asBoolean();
            node.remove("local");
            node.put("localSource", local).put("localTarget", local);
            rename(node, "relative", "additive");
            var properties = node.putObject("properties");
            for (String property : List.of("rotate", "x", "y", "scaleX", "scaleY", "shearY"))
                properties.putObject(property).putObject("to").putObject(property);
        } else {
            if (node.path("clamp").asBoolean()
                    || node.path("localSource").asBoolean() != node.path("localTarget").asBoolean())
                throw ProjectCodec.bad("$.constraints.transform", "4.3 clamp/mixed local spaces cannot be represented");
            var properties = node.remove("properties");
            if (properties == null || !properties.isObject())
                throw ProjectCodec.bad("$.constraints.properties", "Expected transform property mappings");
            var active = new HashSet<String>();
            for (var fields = properties.fields(); fields.hasNext();) {
                var field = fields.next();
                String key = field.getKey();
                var from = field.getValue();
                var to = from.path("to");
                // Official TransformConstraint.update uses max only inside its clamp branch.
                // An unclamped identity mapping remains identical for any finite max value.
                if(to.path(key).has("max"))ProjectCodec.number(to.path(key),"max","$.constraints.properties."+key);
                if (!Set.of("rotate", "x", "y", "scaleX", "scaleY", "shearY").contains(key)
                        || from.path("offset").asDouble() != 0 || to.size() != 1 || !to.has(key)
                        || to.path(key).path("offset").asDouble() != 0 || to.path(key).path("scale").asDouble(1) != 1)
                    throw ProjectCodec.bad("$.constraints.properties",
                            "4.3 cross-property/remapped transforms cannot be represented");
                active.add(key);
            }
            for (String property : List.of("rotate", "x", "y", "scaleX", "scaleY", "shearY"))
                if (!active.contains(property))
                    node.put("mix" + Character.toUpperCase(property.charAt(0)) + property.substring(1), 0);
            rename(node, "source", "target");
            node.put("local", node.path("localSource").asBoolean());
            node.remove(List.of("localSource", "localTarget", "clamp"));
            rename(node, "additive", "relative");
        }
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void clampRoundedControlTimes(JsonNode node) {
        if (node.isArray()) {
            for (int i = 0; i + 1 < node.size(); i++) {
                var key = node.get(i);
                var next = node.get(i + 1);
                var curve = key.get("curve");
                if (curve == null || !curve.isArray())
                    continue;
                double start = key.path("time").asDouble(), end = next.path("time").asDouble();
                for (int c = 0; c < curve.size(); c += 2) {
                    double value = curve.get(c).asDouble();
                    if (value < start && start - value <= .00055)
                        ((ArrayNode) curve).set(c, DoubleNode.valueOf(start));
                    if (value > end && value - end <= .00055)
                        ((ArrayNode) curve).set(c, DoubleNode.valueOf(end));
                }
            }
        }
        if (node.isContainerNode())
            for (var child : node)
                clampRoundedControlTimes(child);
    }
}
