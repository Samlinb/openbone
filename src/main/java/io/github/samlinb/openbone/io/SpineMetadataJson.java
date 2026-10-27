package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.samlinb.openbone.core.model.*;

/** Shared representation for source metadata in Spine exports and native projects. */
final class SpineMetadataJson {
    private SpineMetadataJson() {}

    static SpineMetadata readRuntime(JsonNode root, Map<String, EntityId> ids) throws ProjectFormatException {
        var meshes = new LinkedHashMap<EntityId, SpineMetadata.Mesh>();
        for (var skin : root.path("skins")) for (var slots = skin.path("attachments").fields(); slots.hasNext();) {
            var slot = slots.next();
            for (var attachments = slot.getValue().fields(); attachments.hasNext();) {
                var attachment = attachments.next(); var n = attachment.getValue();
                if (!n.path("type").asText().equals("mesh") || !(n.has("edges") || n.has("width") || n.has("height"))) continue;
                var id = ids.get(skin.path("name").asText() + "\0" + slot.getKey() + "\0" + attachment.getKey());
                if (id != null) meshes.put(id, readMesh(n, "$.skins." + skin.path("name").asText() + "." + slot.getKey() + "." + attachment.getKey()));
            }
        }
        return readSkeleton(root.path("skeleton"), meshes);
    }

    private static SpineMetadata readSkeleton(JsonNode n, Map<EntityId, SpineMetadata.Mesh> meshes) throws ProjectFormatException {
        var text = new LinkedHashMap<String, String>(); var numbers = new LinkedHashMap<String, Double>();
        for (String k : List.of("hash", "audio")) if (n.has(k)) text.put(k, ProjectCodec.text(n, k, "$.skeleton"));
        for (String k : List.of("x", "y", "width", "height", "fps")) if (n.has(k)) numbers.put(k, ProjectCodec.number(n, k, "$.skeleton"));
        return new SpineMetadata(text, numbers, meshes);
    }

    private static SpineMetadata.Mesh readMesh(JsonNode n, String p) throws ProjectFormatException {
        return new SpineMetadata.Mesh(n.has("edges") ? ProjectCodec.integers(n, "edges", p) : null,
                n.has("width") ? ProjectCodec.number(n, "width", p) : null,
                n.has("height") ? ProjectCodec.number(n, "height", p) : null);
    }

    static void writeSkeleton(ObjectNode n, SpineMetadata metadata) {
        new TreeMap<>(metadata.text()).forEach(n::put);
        new TreeMap<>(metadata.numbers()).forEach(n::put);
    }

    static void writeMesh(ObjectNode n, SpineMetadata.Mesh info) {
        if (info == null) return;
        if (info.edges() != null) info.edges().forEach(n.putArray("edges")::add);
        if (info.width() != null) n.put("width", info.width());
        if (info.height() != null) n.put("height", info.height());
    }

    static void writeProject(ObjectNode n, SpineMetadata metadata) {
        writeSkeleton(n.putObject("skeleton"), metadata);
        if(metadata.orderedConstraintWrites())n.put("orderedConstraintWrites",true);
        if(!metadata.importArchive().isEmpty()) {
            var archive=n.putObject("importArchive");new TreeMap<>(metadata.importArchive()).forEach(archive::put);
        }
        var array = n.putArray("meshes");
        metadata.meshes().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::value)))
                .forEach(e -> writeMesh(array.addObject().put("attachmentId", e.getKey().value()), e.getValue()));
    }

    static SpineMetadata readProject(JsonNode n) throws ProjectFormatException {
        optionalFields(n, "$.resources.metadata", "skeleton", "meshes", "importArchive", "orderedConstraintWrites");
        optionalFields(n.get("skeleton"), "$.resources.metadata.skeleton", "hash", "audio", "x", "y", "width", "height", "fps");
        var meshes = new LinkedHashMap<EntityId, SpineMetadata.Mesh>();
        for (var mesh : ProjectCodec.array(n, "meshes", "$.resources.metadata")) {
            optionalFields(mesh, "$.resources.metadata.meshes", "attachmentId", "edges", "width", "height");
            if (meshes.putIfAbsent(new EntityId(ProjectCodec.text(mesh, "attachmentId", "$.resources.metadata.meshes")), readMesh(mesh, "$.resources.metadata.meshes")) != null)
                throw ProjectCodec.bad("$.resources.metadata.meshes", "Duplicate mesh metadata");
        }
        var metadata=readSkeleton(n.get("skeleton"),meshes);var archive=new LinkedHashMap<String,String>();
        if(n.has("importArchive")) {
            if(!n.get("importArchive").isObject())throw ProjectCodec.bad("$.resources.metadata.importArchive","Expected object");
            for(var entries=n.get("importArchive").fields();entries.hasNext();) {
                var entry=entries.next();if(!entry.getValue().isTextual())throw ProjectCodec.bad("$.resources.metadata.importArchive","Expected string values");
                archive.put(entry.getKey(),entry.getValue().asText());
            }
        }
        return new SpineMetadata(metadata.text(),metadata.numbers(),metadata.meshes(),archive,
                n.has("orderedConstraintWrites")&&ProjectCodec.bool(n,"orderedConstraintWrites","$.resources.metadata"));
    }

    private static void optionalFields(JsonNode n, String p, String... keys) throws ProjectFormatException {
        if (n == null || !n.isObject()) throw ProjectCodec.bad(p, "Expected object");
        var allowed = Set.of(keys);
        for (var fields = n.fieldNames(); fields.hasNext();) {
            String key = fields.next();
            if (!allowed.contains(key)) throw ProjectCodec.bad(p + "." + key, "Unknown field");
        }
    }
}
