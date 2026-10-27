package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.samlinb.openbone.core.model.*;
import java.util.*;
import static io.github.samlinb.openbone.io.ProjectCodec.*;

/** Strict translation of skin bone/constraint membership into stable model IDs. */
final class SkinActivationJson {
    private SkinActivationJson() {}
    static SkinActivation readRuntime(JsonNode root, List<Bone> bones, List<Constraint> constraints)
            throws ProjectFormatException {
        var byBone = new LinkedHashMap<String, EntityId>(); bones.forEach(b -> byBone.put(b.name(), b.id()));
        var requiredBones = new LinkedHashSet<EntityId>();
        for (var bone : root.path("bones"))
            if (bone.has("skin") && bool(bone, "skin", "$.bones")) requiredBones.add(byBone.get(text(bone, "name", "$.bones")));
        var requiredConstraints = new LinkedHashSet<EntityId>();
        var byType = new LinkedHashMap<String, Map<String, EntityId>>();
        for (String type : List.of("ik", "transform", "path", "physics")) {
            var names = new LinkedHashMap<String, EntityId>();
            for (var c : constraints) if (type(c).equals(type)) names.put(c.name(), c.id());
            byType.put(type, names);
            for (var c : root.path(type))
                if (c.has("skin") && bool(c, "skin", "$." + type)) requiredConstraints.add(names.get(text(c, "name", "$." + type)));
        }
        var skins = new LinkedHashMap<String, SkinActivation.Members>();
        for (var skin : root.path("skins")) {
            var members = new LinkedHashSet<EntityId>();
            for (var entry : byType.entrySet()) members.addAll(names(skin, entry.getKey(), entry.getValue()));
            var skinBones = names(skin, "bones", byBone);
            if (!skinBones.isEmpty() || !members.isEmpty())
                skins.put(text(skin, "name", "$.skins"), new SkinActivation.Members(skinBones, members));
        }
        return new SkinActivation(requiredBones, requiredConstraints, skins);
    }
    private static Set<EntityId> names(JsonNode node, String key, Map<String, EntityId> lookup) throws ProjectFormatException {
        var result = new LinkedHashSet<EntityId>();
        if (!node.has(key)) return result;
        for (var value : array(node, key, "$.skins")) {
            if (!value.isTextual() || !lookup.containsKey(value.textValue()))
                throw bad("$.skins." + key, io.github.samlinb.openbone.i18n.M.m("skinActivation.unknownMember", value));
            if (!result.add(lookup.get(value.textValue()))) throw bad("$.skins." + key, io.github.samlinb.openbone.i18n.M.m("skinActivation.duplicateMember"));
        }
        return result;
    }
    private static String type(Constraint c) {
        return c instanceof IkConstraint ? "ik" : c instanceof TransformConstraint ? "transform"
                : c instanceof PathConstraint ? "path" : "physics";
    }
    static void writeRuntime(ObjectNode root, DocumentSnapshot d) {
        var activation=d.resources().activation();
        for(var bone:root.path("bones")) {
            var model=d.bones().stream().filter(b->b.name().equals(bone.path("name").asText())).findFirst().orElseThrow();
            if(activation.requiredBones().contains(model.id())) ((ObjectNode)bone).put("skin",true);
        }
        for(var c:d.constraints()) if(activation.requiredConstraints().contains(c.id()))
            for(var node:root.path(type(c))) if(node.path("name").asText().equals(c.name())) ((ObjectNode)node).put("skin",true);
        for(var skin:root.path("skins")) {
            var members=activation.skins().getOrDefault(skin.path("name").asText(),SkinActivation.Members.EMPTY);
            if(!members.bones().isEmpty()) {
                var out=((ObjectNode)skin).putArray("bones");
                d.bones().stream().filter(b->members.bones().contains(b.id())).forEach(b->out.add(b.name()));
            }
            for(var c:d.constraints()) if(members.constraints().contains(c.id())) ((ObjectNode)skin).withArray(type(c)).add(c.name());
        }
    }
    static void writeProject(ObjectNode node, SkinActivation value) {
        ids(node, "requiredBones", value.requiredBones()); ids(node, "requiredConstraints", value.requiredConstraints());
        var skins = node.putArray("skins");
        value.skins().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            var skin = skins.addObject().put("name", e.getKey());
            ids(skin, "bones", e.getValue().bones()); ids(skin, "constraints", e.getValue().constraints());
        });
    }
    private static void ids(ObjectNode node, String key, Set<EntityId> ids) {
        var array = node.putArray(key); ids.stream().map(EntityId::value).sorted().forEach(array::add);
    }
    static SkinActivation readProject(JsonNode node) throws ProjectFormatException {
        fields(node, "$.resources.activation", "requiredBones", "requiredConstraints", "skins");
        var skins = new LinkedHashMap<String, SkinActivation.Members>();
        for (var skin : array(node, "skins", "activation")) {
            fields(skin, "activation.skins", "name", "bones", "constraints");
            if (skins.putIfAbsent(text(skin, "name", "activation.skins"),
                    new SkinActivation.Members(ids(skin, "bones"), ids(skin, "constraints"))) != null)
                throw bad("activation.skins", io.github.samlinb.openbone.i18n.M.m("skinActivation.duplicateSkin"));
        }
        return new SkinActivation(ids(node, "requiredBones"), ids(node, "requiredConstraints"), skins);
    }
    private static Set<EntityId> ids(JsonNode node, String key) throws ProjectFormatException {
        var result = new LinkedHashSet<EntityId>();
        for (var value : array(node, key, "activation")) {
            if (!value.isTextual() || !result.add(new EntityId(value.textValue()))) throw bad("activation." + key, io.github.samlinb.openbone.i18n.M.m("skinActivation.uniqueIds"));
        }
        return result;
    }
}
