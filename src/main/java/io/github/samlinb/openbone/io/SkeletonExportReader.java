package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Read-only inspection of 3.8.75 and 4.2.22 exports, NOT an editable-project
 * importer.
 * Mesh/animation/etc. remain in the source file and are explicitly reported as
 * future capabilities.
 */
public final class SkeletonExportReader {
    public record Inspection(String version, List<String> bones, List<String> slots,
            List<String> animations, int regions, List<String> unsupported) {
        public Inspection {
            bones = List.copyOf(bones);
            slots = List.copyOf(slots);
            animations = List.copyOf(animations);
            unsupported = List.copyOf(unsupported);
        }
    }

    public Inspection read(Path path) throws IOException {
        String extension = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (extension.endsWith(".spine") || extension.endsWith(".skel"))
            throw new ProjectFormatException("Binary/native formats are unsupported");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(ProjectCodec.MAX_BYTES + 1);
        }
        JsonNode root = new ProjectCodec().parseTree(bytes);
        if (!root.isObject() || !root.path("skeleton").isObject())
            throw ProjectCodec.bad("$.skeleton", "Expected metadata object");
        String version = ProjectCodec.text(root.get("skeleton"), "spine", "$.skeleton");
        if (!Set.of(SkeletonJsonCodec.VERSION, SkeletonJsonCodec.VERSION_38).contains(version))
            throw ProjectCodec.bad("$.skeleton.spine", "Unsupported export version: " + version);
        List<String> bones = new ArrayList<>(), slots = new ArrayList<>(), animations = new ArrayList<>(),
                unsupported = new ArrayList<>();
        Set<String> boneNames = new HashSet<>(), slotNames = new HashSet<>();
        int index = 0;
        for (JsonNode bone : ProjectCodec.array(root, "bones", "$")) {
            String p = "$.bones[" + index++ + "]", name = ProjectCodec.text(bone, "name", p);
            if (name.isBlank() || !boneNames.add(name))
                throw ProjectCodec.bad(p + ".name", "Empty or duplicate bone name");
            if (bone.has("parent") && !bones.contains(ProjectCodec.text(bone, "parent", p)))
                throw ProjectCodec.bad(p + ".parent", "Parent must precede child");
            for (String key : List.of("x", "y", "rotation", "scaleX", "scaleY", "length", "shearX", "shearY"))
                if (bone.has(key))
                    ProjectCodec.number(bone, key, p);
            if (bone.has("inherit") && !bone.get("inherit").asText().equals("normal"))
                unsupported.add(p + ".inherit");
            if (bone.path("shearX").asDouble() != 0 || bone.path("shearY").asDouble() != 0)
                unsupported.add(p + ".shear");
            bones.add(name);
        }
        index = 0;
        for (JsonNode slot : ProjectCodec.array(root, "slots", "$")) {
            String p = "$.slots[" + index++ + "]", name = ProjectCodec.text(slot, "name", p);
            if (name.isBlank() || !slotNames.add(name))
                throw ProjectCodec.bad(p + ".name", "Empty or duplicate slot name");
            if (!boneNames.contains(ProjectCodec.text(slot, "bone", p)))
                throw ProjectCodec.bad(p + ".bone", "Missing slot bone");
            slots.add(name);
        }
        int regions = 0;
        for (JsonNode skin : ProjectCodec.array(root, "skins", "$")) {
            if (!skin.path("name").asText().equals("default"))
                unsupported.add("$.skins[" + skin.path("name").asText() + "]");
            JsonNode attachments = skin.path("attachments");
            if (!attachments.isObject())
                throw ProjectCodec.bad("$.skins.attachments", "Expected object");
            Iterator<String> keys = attachments.fieldNames();
            while (keys.hasNext()) {
                String slot = keys.next();
                if (!slotNames.contains(slot))
                    throw ProjectCodec.bad("$.skins.attachments." + slot, "Unknown slot");
                JsonNode values = attachments.get(slot);
                if (!values.isObject())
                    throw ProjectCodec.bad("$.skins.attachments." + slot, "Expected object");
                Iterator<String> attachmentNames = values.fieldNames();
                while (attachmentNames.hasNext()) {
                    String name = attachmentNames.next();
                    JsonNode attachment = values.get(name);
                    if (!attachment.isObject())
                        throw ProjectCodec.bad("$.skins.attachments." + slot + "." + name, "Expected object");
                    String type = attachment.has("type") ? ProjectCodec.text(attachment, "type", "$.attachment")
                            : "region";
                    if (type.equals("region"))
                        regions++;
                    else
                        unsupported.add("$.skins.attachments." + slot + "." + name + " (" + type + ")");
                }
            }
        }
        if (root.has("animations")) {
            if (!root.get("animations").isObject())
                throw ProjectCodec.bad("$.animations", "Expected object");
            root.get("animations").fieldNames().forEachRemaining(animations::add);
            if (!animations.isEmpty())
                unsupported.add("$.animations (P4/P5)");
        }
        for (String key : List.of("ik", "transform", "path", "physics", "events"))
            if (root.has(key))
                unsupported.add("$." + key);
        Set<String> known = Set.of("skeleton", "bones", "slots", "skins", "animations", "ik", "transform", "path",
                "physics", "events");
        root.fieldNames().forEachRemaining(key -> {
            if (!known.contains(key))
                unsupported.add("$." + key);
        });
        return new Inspection(version, bones, slots, animations, regions, unsupported);
    }
}
