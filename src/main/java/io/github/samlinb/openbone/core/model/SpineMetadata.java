package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.Finite;

/** Source export information, independent of evaluated geometry and image packing. */
public record SpineMetadata(Map<String, String> text, Map<String, Double> numbers, Map<EntityId, Mesh> meshes,
        Map<String,String> importArchive, boolean orderedConstraintWrites) {
    public SpineMetadata(Map<String,String> text,Map<String,Double> numbers,Map<EntityId,Mesh> meshes,
            Map<String,String> importArchive) {
        this(text,numbers,meshes,importArchive,false);
    }
    public SpineMetadata(Map<String,String> text,Map<String,Double> numbers,Map<EntityId,Mesh> meshes) {
        this(text,numbers,meshes,Map.of());
    }
    public static final SpineMetadata EMPTY = new SpineMetadata(Map.of(), Map.of(), Map.of());
    public SpineMetadata {
        text = Map.copyOf(text); numbers = Map.copyOf(numbers); meshes = Map.copyOf(meshes);importArchive=Map.copyOf(importArchive);
        if(importArchive.size()>10000||importArchive.entrySet().stream().anyMatch(e->e.getKey().length()>4096||e.getValue().length()>1_000_000))
            throw new IllegalArgumentException("Import archive exceeds supported limits");
        if (!Set.of("hash", "audio").containsAll(text.keySet())
                || !Set.of("x", "y", "width", "height", "fps").containsAll(numbers.keySet()))
            throw new IllegalArgumentException("Unknown skeleton metadata field");
        text.values().forEach(v -> { if (v.length() > 4096) throw new IllegalArgumentException("Skeleton text too long"); });
        numbers.forEach((k, v) -> {
            Finite.require(v, k);
            if (Set.of("width", "height", "fps").contains(k) && v < 0)
                throw new IllegalArgumentException("Negative skeleton " + k);
        });
    }

    /** Edges use coordinate offsets (twice the vertex index), as in Spine JSON. */
    public record Mesh(List<Integer> edges, Double width, Double height) {
        public Mesh {
            if (edges != null) {
                edges = List.copyOf(edges);
                if (edges.size() % 2 != 0 || edges.stream().anyMatch(i -> i < 0 || i % 2 != 0))
                    throw new IllegalArgumentException("Mesh edges require pairs of even coordinate offsets");
            }
            for (Double value : new Double[]{width, height}) if (value != null) {
                Finite.require(value, "mesh size");
                if (value < 0) throw new IllegalArgumentException("Negative mesh size");
            }
        }
        public void validate(MeshAttachment mesh) {
            if (edges != null && edges.stream().anyMatch(i -> i >= mesh.vertices().size() * 2))
                throw new IllegalArgumentException("Mesh edge references missing vertex");
        }
    }

    public SpineMetadata retain(Set<EntityId> removed) {
        var next = new HashMap<>(meshes); next.keySet().removeAll(removed);
        return new SpineMetadata(text, numbers, next, importArchive,orderedConstraintWrites);
    }

    public SpineMetadata withoutEdges(EntityId id) {
        if (!meshes.containsKey(id)) return this;
        var next = new HashMap<>(meshes); var old = next.get(id);
        next.put(id, new Mesh(null, old.width(), old.height()));
        return new SpineMetadata(text, numbers, next, importArchive,orderedConstraintWrites);
    }

    public SpineMetadata withOrderedConstraintWrites(boolean value) {
        return new SpineMetadata(text,numbers,meshes,importArchive,value);
    }
}
