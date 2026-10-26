package io.github.samlinb.openbone.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;
import java.nio.file.*;
import java.util.*;

/** Strict MCP adapters to shared model edits. PNG inspection runs on the MCP worker. */
final class SceneMcpEdits {
    private SceneMcpEdits() {}

    static ArrayNode schemas(ObjectMapper json) {
        ArrayNode result = json.createArrayNode();
        String transform = """
            {"type":"object","additionalProperties":false,"properties":{
              "x":{"type":"number"},"y":{"type":"number"},"rotation":{"type":"number"},
              "scaleX":{"type":"number"},"scaleY":{"type":"number"},"shearX":{"type":"number"},"shearY":{"type":"number"}},
             "required":["x","y","rotation","scaleX","scaleY"]}
            """;
        add(json,result,"create_bone", "\"boneId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"parentId\":{\"type\":[\"string\",\"null\"]},\"setup\":"+transform+",\"length\":{\"type\":\"number\",\"minimum\":0}", "boneId","name","parentId","setup","length");
        add(json,result,"edit_bone", "\"boneId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"setup\":"+transform+",\"length\":{\"type\":\"number\",\"minimum\":0}", "boneId");
        add(json,result,"reparent_bone", "\"boneId\":{\"type\":\"string\"},\"parentId\":{\"type\":[\"string\",\"null\"]},\"policy\":{\"enum\":[\"keep_local\",\"keep_world\"]}", "boneId","parentId","policy");
        add(json,result,"create_slot", "\"slotId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"boneId\":{\"type\":\"string\"},\"index\":{\"type\":\"integer\",\"minimum\":0}", "slotId","name","boneId");
        add(json,result,"edit_slot", "\"slotId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"boneId\":{\"type\":\"string\"},\"activeAttachmentId\":{\"type\":[\"string\",\"null\"]}", "slotId");
        String region = "\"attachmentId\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"pngPath\":{\"type\":\"string\"},\"setup\":"+transform+",\"width\":{\"type\":\"number\",\"exclusiveMinimum\":0},\"height\":{\"type\":\"number\",\"exclusiveMinimum\":0}";
        add(json,result,"create_region",region+",\"slotId\":{\"type\":\"string\"},\"makeActive\":{\"type\":\"boolean\"}","attachmentId","slotId","name","pngPath");
        add(json,result,"edit_region",region,"attachmentId");
        add(json,result,"set_slot_order","\"slotIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"uniqueItems\":true}","slotIds");
        return result;
    }

    private static void add(ObjectMapper json, ArrayNode out, String op, String properties, String... required) {
        try {
            var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
            schema.set("properties",json.readTree("{\"op\":{\"const\":\""+op+"\"},"+properties+"}"));
            var names=schema.putArray("required"); names.add("op"); for(String name:required)names.add(name);
            out.add(schema);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    static DocumentSnapshot apply(DocumentSnapshot d, JsonNode e, String op) throws Exception {
        return switch(op) {
            case "create_bone" -> {
                fields(e,"op","boneId","name","parentId","setup","length");
                yield ModelEdits.addBone(d,new Bone(id(e,"boneId"),text(e,"name"),nullableId(e,"parentId"),transform(e.get("setup")),num(e,"length",0),Rgba.WHITE,Bone.Inherit.normal));
            }
            case "edit_bone" -> {
                fields(e,"op","boneId","name","setup","length");
                yield ModelEdits.editBone(d,id(e,"boneId"),b->new Bone(b.id(),e.has("name")?text(e,"name"):b.name(),b.parentId(),e.has("setup")?transform(e.get("setup")):b.local(),e.has("length")?num(e,"length",0):b.length(),b.color(),b.inherit()));
            }
            case "reparent_bone" -> {
                fields(e,"op","boneId","parentId","policy");
                var policy=switch(text(e,"policy")) { case "keep_local"->ModelEdits.ReparentPolicy.KEEP_LOCAL; case "keep_world"->ModelEdits.ReparentPolicy.KEEP_WORLD; default->throw new IllegalArgumentException("policy must be keep_local or keep_world"); };
                yield ModelEdits.reparent(d,id(e,"boneId"),nullableId(e,"parentId"),policy);
            }
            case "create_slot" -> {
                fields(e,"op","slotId","name","boneId","index");
                int index=d.slots().size();
                if(e.has("index")) { if(!e.get("index").isIntegralNumber()||!e.get("index").canConvertToInt())throw new IllegalArgumentException("index must be integer"); index=e.get("index").intValue(); }
                if(index<0||index>d.slots().size())throw new IllegalArgumentException("index outside slot list");
                yield ModelEdits.addSlot(d,new Slot(id(e,"slotId"),text(e,"name"),id(e,"boneId"),null),index);
            }
            case "edit_slot" -> {
                fields(e,"op","slotId","name","boneId","activeAttachmentId");
                yield ModelEdits.editSlot(d,id(e,"slotId"),s->new Slot(s.id(),e.has("name")?text(e,"name"):s.name(),e.has("boneId")?id(e,"boneId"):s.boneId(),e.has("activeAttachmentId")?nullableId(e,"activeAttachmentId"):s.activeAttachmentId(),s.blend(),s.color()));
            }
            case "create_region" -> {
                fields(e,"op","attachmentId","slotId","name","pngPath","setup","width","height","makeActive");
                Path path=png(e); var image=ImageAssets.readPng(path);
                yield ModelEdits.addAttachment(d,new RegionAttachment(id(e,"attachmentId"),text(e,"name"),id(e,"slotId"),path.toString(),e.has("setup")?transform(e.get("setup")):Transform.IDENTITY,e.has("width")?positive(e,"width"):image.getWidth(),e.has("height")?positive(e,"height"):image.getHeight()),bool(e,"makeActive",true));
            }
            case "edit_region" -> {
                fields(e,"op","attachmentId","name","pngPath","setup","width","height");
                String source=null;
                if(e.has("pngPath")) { Path path=png(e); ImageAssets.readPng(path); source=path.toString(); }
                String validatedSource=source;
                yield ModelEdits.editRegion(d,id(e,"attachmentId"),r->new RegionAttachment(r.id(),e.has("name")?text(e,"name"):r.name(),r.slotId(),validatedSource==null?r.source():validatedSource,e.has("setup")?transform(e.get("setup")):r.local(),e.has("width")?positive(e,"width"):r.width(),e.has("height")?positive(e,"height"):r.height()));
            }
            case "set_slot_order" -> {
                fields(e,"op","slotIds"); var values=e.get("slotIds");
                if(values==null||!values.isArray()||values.size()!=d.slots().size())throw new IllegalArgumentException("slotIds must list every slot exactly once, back to front");
                List<EntityId> ids=new ArrayList<>(); for(var v:values) {if(!v.isTextual())throw new IllegalArgumentException("slotIds must contain UUID strings");ids.add(new EntityId(v.textValue()));}
                yield ModelEdits.reorderSlots(d,ids);
            }
            default -> throw new IllegalArgumentException("Unknown edit op: "+op);
        };
    }

    static Path absolutePath(JsonNode node,String key) {
        Path p=Path.of(text(node,key)); if(!p.isAbsolute())throw new IllegalArgumentException(key+" must be an absolute local path"); return p.normalize();
    }
    private static Path png(JsonNode node) throws Exception {
        Path p=absolutePath(node,"pngPath").toRealPath();
        if(!Files.isRegularFile(p)||!Files.isReadable(p)||!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))throw new IllegalArgumentException("pngPath must be a readable PNG file"); return p;
    }
    static void fields(JsonNode e,String... allowed) { if(e==null||!e.isObject())throw new IllegalArgumentException("Expected object");var names=Set.of(allowed); e.fieldNames().forEachRemaining(k->{if(!names.contains(k))throw new IllegalArgumentException("Unknown field: "+k);}); }
    static String text(JsonNode e,String key) {var n=e.get(key);if(n==null||!n.isTextual()||n.textValue().isBlank()||n.textValue().length()>4096)throw new IllegalArgumentException(key+" must be a nonempty string");return n.textValue();}
    static EntityId id(JsonNode e,String key) {return new EntityId(text(e,key));}
    private static EntityId nullableId(JsonNode e,String key) {if(!e.has(key))throw new IllegalArgumentException(key+" is required (UUID or null)");return e.get(key).isNull()?null:id(e,key);}
    static boolean bool(JsonNode e,String key,boolean fallback) {if(!e.has(key))return fallback;if(!e.get(key).isBoolean())throw new IllegalArgumentException(key+" must be boolean");return e.get(key).booleanValue();}
    static double num(JsonNode e,String key,double min) {var n=e==null?null:e.get(key);if(n==null||!n.isNumber()||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>1e9)throw new IllegalArgumentException("Invalid numeric field: "+key);return n.doubleValue();}
    private static double positive(JsonNode e,String key) {double n=num(e,key,0);if(n<=0)throw new IllegalArgumentException(key+" must be positive");return n;}
    private static Transform transform(JsonNode e) {fields(e,"x","y","rotation","scaleX","scaleY","shearX","shearY");return new Transform(num(e,"x",-1e9),num(e,"y",-1e9),num(e,"rotation",-1e9),num(e,"scaleX",-1e9),num(e,"scaleY",-1e9),e.has("shearX")?num(e,"shearX",-1e9):0,e.has("shearY")?num(e,"shearY",-1e9):0);}
}
