package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.io.ProjectCodec.*;

/**
 * Schema6 resources and discrete tracks, plus Schema7 slot color timelines.
 * Every nested field is checked before constructing editable state.
 */
final class P8ProjectJson {
    private P8ProjectJson() {
    }

    static boolean needed(DocumentSnapshot d) {
        return !d.resources().equals(Resources.EMPTY)
                || d.slots().stream().anyMatch(s -> s.blend() != Slot.Blend.NORMAL)
                || d.animations().stream().anyMatch(a -> !a.discrete().equals(DiscreteTracks.EMPTY));
    }

    static void writeResources(ObjectNode root, DocumentSnapshot d) {
        var r = root.putObject("resources").put("activeSkin", d.resources().activeSkin());
        if (!d.resources().metadata().equals(SpineMetadata.EMPTY)) SpineMetadataJson.writeProject(r.putObject("metadata"), d.resources().metadata());
        if (!d.resources().activation().equals(SkinActivation.EMPTY)) SkinActivationJson.writeProject(r.putObject("activation"), d.resources().activation());
        var skins = r.putArray("skins");
        for (var skin : d.resources().skins()) {
            var s = skins.addObject().put("name", skin.name());
            var rows = s.putArray("attachments");
            skin.attachments().forEach((slot, entries) -> entries.forEach((name, id) -> rows.addObject()
                    .put("slotId", slot.value()).put("name", name).put("attachmentId", id.value())));
        }
        var events = r.putArray("events");
        for (var e : d.resources().events())
            events.addObject().put("id", e.id().value()).put("name", e.name()).put("int", e.intValue())
                    .put("float", e.floatValue()).put("string", e.stringValue()).put("audio", e.audio())
                    .put("volume", e.volume()).put("balance", e.balance());
        var atlas = r.putArray("atlas");
        d.resources().atlas().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(EntityId::value))).forEach(entry -> {
                    var t = entry.getValue();
                    var item = atlas.addObject().put("attachmentId", entry.getKey().value()).put("pageWidth", t.pageWidth())
                            .put("pageHeight", t.pageHeight()).put("x", t.x()).put("y", t.y()).put("width", t.width())
                            .put("height", t.height()).put("originalWidth", t.originalWidth())
                            .put("originalHeight", t.originalHeight()).put("offsetX", t.offsetX())
                            .put("offsetY", t.offsetY()).put("rotated", t.rotated());
                    if (t.premultiplied()) item.put("premultiplied", true);
                    if (t.degrees() != 0 && t.degrees() != 90) item.put("degrees", t.degrees());
                });
    }

    static Resources readResources(JsonNode root) throws ProjectFormatException {
        var r = root.get("resources");
        String p = "$.resources";
        if (root.path("schemaVersion").asInt() >= 12) {
            var keys = new ArrayList<>(List.of("activeSkin", "skins", "events", "atlas"));
            if (r.has("metadata")) keys.add("metadata");
            if (r.has("activation")) keys.add("activation");
            fields(r, p, keys.toArray(String[]::new));
        }
        else if (root.path("schemaVersion").asInt() >= 9 && r.has("metadata")) fields(r, p, "activeSkin", "skins", "events", "atlas", "metadata");
        else fields(r, p, "activeSkin", "skins", "events", "atlas");
        var skins = new ArrayList<Skin>();
        for (var s : array(r, "skins", p)) {
            fields(s, p + ".skins", "name", "attachments");
            var rows = new LinkedHashMap<EntityId, Map<String, EntityId>>();
            for (var a : array(s, "attachments", p)) {
                fields(a, p, "slotId", "name", "attachmentId");
                var row = rows.computeIfAbsent(id(a, "slotId", p), k -> new LinkedHashMap<>());
                if (row.putIfAbsent(text(a, "name", p), id(a, "attachmentId", p)) != null)
                    throw bad(p, "Duplicate skin placeholder");
            }
            skins.add(new Skin(text(s, "name", p), rows));
        }
        var events = new ArrayList<EventDefinition>();
        for (var e : array(r, "events", p)) {
            fields(e, p + ".events", "id", "name", "int", "float", "string", "audio", "volume", "balance");
            events.add(new EventDefinition(id(e, "id", p), text(e, "name", p), integer(e.get("int"), p),
                    number(e, "float", p), text(e, "string", p), text(e, "audio", p), number(e, "volume", p),
                    number(e, "balance", p)));
        }
        var atlas = new LinkedHashMap<EntityId, AtlasRegion>();
        for (var a : array(r, "atlas", p)) {
            var keys = new ArrayList<>(List.of("attachmentId", "pageWidth", "pageHeight", "x", "y", "width", "height",
                    "originalWidth", "originalHeight", "offsetX", "offsetY", "rotated"));
            if (a.has("premultiplied")) keys.add("premultiplied");
            if (a.has("degrees")) keys.add("degrees");
            fields(a, p + ".atlas", keys.toArray(String[]::new));
            var region = new AtlasRegion(integer(a.get("pageWidth"), p), integer(a.get("pageHeight"), p),
                    integer(a.get("x"), p), integer(a.get("y"), p), integer(a.get("width"), p),
                    integer(a.get("height"), p), integer(a.get("originalWidth"), p),
                    integer(a.get("originalHeight"), p), integer(a.get("offsetX"), p), integer(a.get("offsetY"), p),
                    bool(a, "rotated", p), a.has("premultiplied") && bool(a, "premultiplied", p),
                    a.has("degrees") ? integer(a.get("degrees"),p) : bool(a,"rotated",p) ? 90 : 0);
            if (atlas.putIfAbsent(id(a, "attachmentId", p), region) != null)
                throw bad(p, "Duplicate atlas attachment");
        }
        return new Resources(skins, text(r, "activeSkin", p), events, atlas,
                r.has("metadata") ? SpineMetadataJson.readProject(r.get("metadata")) : SpineMetadata.EMPTY,
                r.has("activation") ? SkinActivationJson.readProject(r.get("activation")) : SkinActivation.EMPTY);
    }

    static void writeTracks(ObjectNode node, DiscreteTracks tracks) {
        var n = node.putObject("discrete");
        var a = n.putArray("attachments");
        for (var k : tracks.attachments()) {
            var key = a.addObject().put("id", k.id().value()).put("time", k.time()).put("slotId", k.slotId().value());
            if (k.name() == null)
                key.putNull("name");
            else
                key.put("name", k.name());
        }
        var order = n.putArray("drawOrder");
        for (var k : tracks.drawOrder()) {
            var key = order.addObject().put("id", k.id().value()).put("time", k.time());
            k.slots().forEach(id -> key.withArray("slots").add(id.value()));
            if (k.slots().isEmpty())
                key.putArray("slots");
        }
        var e = n.putArray("events");
        for (var k : tracks.events())
            e.addObject().put("id", k.id().value()).put("time", k.time()).put("eventId", k.eventId().value())
                    .put("int", k.intValue()).put("float", k.floatValue()).put("string", k.stringValue())
                    .put("volume", k.volume()).put("balance", k.balance());
        if(!tracks.colors().isEmpty()) {
            var colors=n.putArray("colors");
            for(var t:tracks.colors()) {
                var row=colors.addObject().put("slotId",t.slotId().value()).put("channel",t.channel().name());
                ProjectCodec.putKeys(row.putArray("keys"),t.keys());
            }
        }
    }

    static DiscreteTracks readTracks(JsonNode node) throws ProjectFormatException {
        var n = node.get("discrete");
        String p = "animation.discrete";
        if(n.has("colors")) fields(n, p, "attachments", "drawOrder", "events", "colors");
        else fields(n, p, "attachments", "drawOrder", "events");
        var a = new ArrayList<DiscreteTracks.AttachmentKey>();
        var o = new ArrayList<DiscreteTracks.OrderKey>();
        var e = new ArrayList<DiscreteTracks.EventKey>();
        for (var k : array(n, "attachments", p)) {
            fields(k, p, "id", "time", "slotId", "name");
            a.add(new DiscreteTracks.AttachmentKey(id(k, "id", p), number(k, "time", p), id(k, "slotId", p),
                    k.get("name").isNull() ? null : text(k, "name", p)));
        }
        for (var k : array(n, "drawOrder", p)) {
            fields(k, p, "id", "time", "slots");
            var ids = new ArrayList<EntityId>();
            for (var value : array(k, "slots", p)) {
                if (!value.isTextual())
                    throw bad(p, "Expected UUID");
                ids.add(new EntityId(value.textValue()));
            }
            o.add(new DiscreteTracks.OrderKey(id(k, "id", p), number(k, "time", p), ids));
        }
        for (var k : array(n, "events", p)) {
            fields(k, p, "id", "time", "eventId", "int", "float", "string", "volume", "balance");
            e.add(new DiscreteTracks.EventKey(id(k, "id", p), number(k, "time", p), id(k, "eventId", p),
                    integer(k.get("int"), p), number(k, "float", p), text(k, "string", p), number(k, "volume", p),
                    number(k, "balance", p)));
        }
        var colors=new ArrayList<SlotColorTimeline>();
        if(n.has("colors")) for(var t:array(n,"colors",p)) {
            fields(t,p+".colors","slotId","channel","keys");
            colors.add(new SlotColorTimeline(id(t,"slotId",p),SlotColorTimeline.Channel.valueOf(text(t,"channel",p)),ProjectCodec.readKeys(t,p)));
        }
        return new DiscreteTracks(a, o, e, colors);
    }

    static Rgba readColor(JsonNode n,String p) throws ProjectFormatException {
        var v=numbers(n,"color",p);
        if(v.size()!=4) throw bad(p+".color","Expected four color components");
        return new Rgba(v.get(0),v.get(1),v.get(2),v.get(3));
    }

    private static EntityId id(JsonNode n, String key, String p) throws ProjectFormatException {
        return new EntityId(text(n, key, p));
    }
}
