package io.github.samlinb.openbone.core.model;

import java.util.*;

/** Skin membership controls evaluation, independently of attachment fallback to default. */
public record SkinActivation(Set<EntityId> requiredBones, Set<EntityId> requiredConstraints,
        Map<String, Members> skins) {
    public record Members(Set<EntityId> bones, Set<EntityId> constraints) {
        public Members { bones = Set.copyOf(bones); constraints = Set.copyOf(constraints); }
        public static final Members EMPTY = new Members(Set.of(), Set.of());
    }
    public static final SkinActivation EMPTY = new SkinActivation(Set.of(), Set.of(), Map.of());
    public SkinActivation {
        requiredBones = Set.copyOf(requiredBones);
        requiredConstraints = Set.copyOf(requiredConstraints);
        skins = Map.copyOf(skins);
        skins.keySet().forEach(Names::require);
    }

    public void validate(List<Bone> bones, List<Constraint> constraints, List<Skin> available) {
        var boneIds = new HashSet<EntityId>(); bones.forEach(b -> boneIds.add(b.id()));
        var constraintIds = new HashSet<EntityId>(); constraints.forEach(c -> constraintIds.add(c.id()));
        var names = new HashSet<String>(); available.forEach(s -> names.add(s.name()));
        if (!boneIds.containsAll(requiredBones) || !constraintIds.containsAll(requiredConstraints)
                || !names.containsAll(skins.keySet())) throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("skinActivation.invalidReference"));
        for (var members : skins.values())
            if (!boneIds.containsAll(members.bones()) || !constraintIds.containsAll(members.constraints()))
                throw new IllegalArgumentException(io.github.samlinb.openbone.i18n.M.m("skinActivation.invalidMember"));
    }

    public Set<EntityId> activeBones(List<Bone> bones, String skin) {
        var active = new HashSet<EntityId>();
        var byId = new HashMap<EntityId, Bone>();
        bones.forEach(b -> { byId.put(b.id(), b); if (!requiredBones.contains(b.id())) active.add(b.id()); });
        for (var id : skins.getOrDefault(skin, Members.EMPTY).bones()) {
            // A selected skin explicitly activates each member and all its ancestors.
            for (Bone bone = byId.get(id); bone != null; bone = byId.get(bone.parentId())) active.add(bone.id());
        }
        return Set.copyOf(active);
    }

    public List<Constraint> activeConstraints(List<Constraint> constraints, List<Slot> slots,
            Set<EntityId> activeBones, String skin) {
        var members = skins.getOrDefault(skin, Members.EMPTY).constraints();
        return constraints.stream().filter(c -> activeBones.contains(c.targetBone(slots))
                && c.boneIds().stream().allMatch(activeBones::contains)
                && (!requiredConstraints.contains(c.id()) || members.contains(c.id()))).toList();
    }

    public SkinActivation retain(Set<EntityId> removed) {
        var next = new LinkedHashMap<String, Members>();
        skins.forEach((name, m) -> next.put(name, new Members(without(m.bones(), removed), without(m.constraints(), removed))));
        return new SkinActivation(without(requiredBones, removed), without(requiredConstraints, removed), next);
    }
    private static Set<EntityId> without(Set<EntityId> source, Set<EntityId> removed) {
        var result = new HashSet<>(source); result.removeAll(removed); return result;
    }
}
