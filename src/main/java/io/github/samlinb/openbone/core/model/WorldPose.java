package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Immutable renderer-facing evaluated geometry. No texture/UI/runtime objects.
 */
public record WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions,
        Map<EntityId, MeshWorld> meshes, Map<EntityId, PolygonWorld> clips, Map<EntityId, List<Vec2>> paths,
        List<Slot> drawSlots, Map<EntityId, PolygonWorld> bounds) {
    public WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions,
            Map<EntityId, MeshWorld> meshes, Map<EntityId, PolygonWorld> clips, Map<EntityId, List<Vec2>> paths, List<Slot> drawSlots) {
        this(bones, regions, meshes, clips, paths, drawSlots, Map.of());
    }
    public WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions,
            Map<EntityId, MeshWorld> meshes, Map<EntityId, PolygonWorld> clips, Map<EntityId, List<Vec2>> paths) {
        this(bones, regions, meshes, clips, paths, List.of());
    }

    public WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions,
            Map<EntityId, MeshWorld> meshes, Map<EntityId, PolygonWorld> clips) {
        this(bones, regions, meshes, clips, Map.of());
    }

    public WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions) {
        this(bones, regions, Map.of(), Map.of());
    }

    public WorldPose(Map<EntityId, BoneWorld> bones, Map<EntityId, RegionWorld> regions,
            Map<EntityId, MeshWorld> meshes) {
        this(bones, regions, meshes, Map.of());
    }

    public record PolygonWorld(List<Vec2> vertices, List<Integer> triangles) {
        public PolygonWorld {
            vertices = List.copyOf(vertices);
            triangles = List.copyOf(triangles);
        }
    }

    public record MeshWorld(List<Vec2> vertices, List<Vec2> uvs, List<Integer> triangles) {
        public MeshWorld {
            vertices = List.copyOf(vertices);
            uvs = List.copyOf(uvs);
            triangles = List.copyOf(triangles);
        }
    }

    public record BoneWorld(Affine2 matrix, Vec2 origin, Vec2 tip) {
    }

    /** Vertices BL, TL, TR, BR, with image UV (0,1), (0,0), (1,0), (1,1). */
    public record RegionWorld(Affine2 matrix, List<Vec2> vertices) {
        public RegionWorld {
            vertices = List.copyOf(vertices);
        }
    }

    public WorldPose {
        drawSlots = List.copyOf(drawSlots);
        bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        regions = Collections.unmodifiableMap(new LinkedHashMap<>(regions));
        meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
        clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
        bounds = Collections.unmodifiableMap(new LinkedHashMap<>(bounds));
        var copied = new LinkedHashMap<EntityId, List<Vec2>>();
        paths.forEach((id, v) -> copied.put(id, List.copyOf(v)));
        paths = Collections.unmodifiableMap(copied);
    }

    /**
     * Renaming a slot is not a pose change; evaluated visibility/order/blend still
     * are.
     */
    private List<Object> drawIdentity() {
        return drawSlots.stream()
                .map(s -> (Object) Arrays.asList(s.id(), s.boneId(), s.activeAttachmentId(), s.blend())).toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorldPose p && bones.equals(p.bones) && regions.equals(p.regions)
                && meshes.equals(p.meshes) && clips.equals(p.clips) && paths.equals(p.paths) && bounds.equals(p.bounds)
                && drawIdentity().equals(p.drawIdentity());
    }

    @Override
    public int hashCode() {
        return Objects.hash(bones, regions, meshes, clips, paths, bounds, drawIdentity());
    }

    static WorldPose evaluate(List<Bone> setup, List<Slot> slots, List<Attachment> attachments,
            Map<EntityId, Transform> pose) {
        return evaluate(setup, slots, attachments, pose, Map.of());
    }

    static WorldPose evaluate(List<Bone> setup, List<Slot> slots, List<Attachment> attachments,
            Map<EntityId, Transform> pose, Map<EntityId, List<Double>> deforms) {
        return evaluate(setup, slots, attachments, pose, deforms, List.of());
    }

    static WorldPose evaluate(List<Bone> setup, List<Slot> slots, List<Attachment> attachments,
            Map<EntityId, Transform> pose, Map<EntityId, List<Double>> deforms, List<Constraint> constraints) {
        return evaluate(setup, slots, attachments, pose, deforms, constraints,
                new PhysicsFrame(PhysicsSolver.Mode.NONE, Map.of()));
    }

    static WorldPose evaluate(List<Bone> setup, List<Slot> slots, List<Attachment> attachments,
            Map<EntityId, Transform> pose, Map<EntityId, List<Double>> deforms, List<Constraint> constraints,
            PhysicsFrame physics) {
        Map<EntityId, BoneWorld> worlds = new LinkedHashMap<>();
        var matrices = ConstraintSolver.evaluate(setup, pose, constraints, slots, attachments, physics, deforms);
        Set<EntityId> remaining = new HashSet<>(pose.keySet());
        for (Bone bone : setup) {
            remaining.remove(bone.id());
            Affine2 world = matrices.get(bone.id());
            worlds.put(bone.id(), new BoneWorld(world, world.apply(0, 0), world.apply(bone.length(), 0)));
        }
        if (!remaining.isEmpty())
            throw new IllegalArgumentException("Unknown pose bone IDs: " + remaining);
        Map<EntityId, Slot> bySlot = new HashMap<>();
        slots.forEach(slot -> bySlot.put(slot.id(), slot));
        Map<EntityId, RegionWorld> regions = new LinkedHashMap<>();
        Map<EntityId, MeshWorld> meshes = new LinkedHashMap<>();
        Map<EntityId, PolygonWorld> clips = new LinkedHashMap<>();
        Map<EntityId, PolygonWorld> bounds = new LinkedHashMap<>();
        Map<EntityId, List<Vec2>> paths = new LinkedHashMap<>();
        Set<EntityId> remainingDeforms = new HashSet<>(deforms.keySet());
        for (Attachment attachment : attachments) {
            if (attachment instanceof BoundingBoxAttachment box) {
                var matrix = worlds.get(bySlot.get(box.slotId()).boneId()).matrix();
                bounds.put(box.id(), new PolygonWorld(box.vertices().stream().map(p -> matrix.apply(p.x(), p.y())).toList(), box.triangles()));
                continue;
            }
            if (attachment instanceof PathAttachment p) {
                remainingDeforms.remove(p.id());
                var matrix = worlds.get(bySlot.get(p.slotId()).boneId()).matrix();
                paths.put(p.id(), p.worldVertices(matrices,matrix,deforms.get(p.id())));
                continue;
            }
            if (attachment instanceof ClippingAttachment c) {
                var matrix = worlds.get(bySlot.get(c.slotId()).boneId()).matrix();
                clips.put(c.id(), new PolygonWorld(c.vertices().stream().map(p -> matrix.apply(p.x(), p.y())).toList(),
                        c.triangles()));
                continue;
            }
            if (attachment instanceof MeshAttachment m) {
                remainingDeforms.remove(m.id());
                var offsets = deforms.get(m.id());
                if (offsets != null && offsets.size() != m.deformSize())
                    throw new IllegalArgumentException(m("worldpose.0001"));
                int offset = 0;
                var matrix = worlds.get(bySlot.get(m.slotId()).boneId()).matrix();
                List<Vec2> vertices = new ArrayList<>();
                if (!m.weighted())
                    for (var v : m.vertices()) {
                        double x = v.x() + (offsets == null ? 0 : offsets.get(offset++)),
                                y = v.y() + (offsets == null ? 0 : offsets.get(offset++));
                        vertices.add(
                                matrix.apply(Finite.require(x, "deform local x"), Finite.require(y, "deform local y")));
                    }
                else
                    for (var influences : m.weights()) {
                        double x = 0, y = 0;
                        for (var i : influences) {
                            double lx = Finite.require(i.point().x() + (offsets == null ? 0 : offsets.get(offset++)),
                                    "skin deform local x"),
                                    ly = Finite.require(i.point().y() + (offsets == null ? 0 : offsets.get(offset++)),
                                            "skin deform local y");
                            var p = worlds.get(i.boneId()).matrix().apply(lx, ly);
                            x = Finite.require(x + p.x() * i.weight(), "skin intermediate x");
                            y = Finite.require(y + p.y() * i.weight(), "skin intermediate y");
                        }
                        vertices.add(new Vec2(x, y));
                    }
                meshes.put(m.id(), new MeshWorld(vertices, m.uvs(), m.triangles()));
                continue;
            }
            // The sealed type forces an explicit branch when additional attachment kinds
            // are admitted.
            if (!(attachment instanceof RegionAttachment r))
                throw new IllegalArgumentException("Unsupported attachment kind");
            Affine2 world = worlds.get(bySlot.get(r.slotId()).boneId()).matrix().multiply(r.local().matrix());
            double x = r.width() / 2, y = r.height() / 2;
            regions.put(r.id(), new RegionWorld(world,
                    List.of(world.apply(-x, -y), world.apply(-x, y), world.apply(x, y), world.apply(x, -y))));
        }
        if (!remainingDeforms.isEmpty())
            throw new IllegalArgumentException("Unknown deform attachment IDs: " + remainingDeforms);
        return new WorldPose(worlds, regions, meshes, clips, paths, List.of(), bounds);
    }
}
