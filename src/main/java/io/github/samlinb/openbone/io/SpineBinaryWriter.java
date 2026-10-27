package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent writer for audited 3.8/4.0/4.1/4.2/4.3 wire families. */
public final class SpineBinaryWriter {
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final DataOutputStream out=new DataOutputStream(bytes);
    private final LinkedHashMap<String,Integer> strings=new LinkedHashMap<>();
    private final List<String> bones=new ArrayList<>(),slots=new ArrayList<>(),skins=new ArrayList<>(),events=new ArrayList<>();
    private final ObjectNode root;
    private final SpineVersion family;
    private SpineBinaryWriter(ObjectNode root,SpineVersion family){this.root=root;this.family=family;}

    public static byte[] encode(DocumentSnapshot model,Map<EntityId,String> paths,String images,String version)throws IOException {
        var codec=new SkeletonJsonCodec();var family=SpineVersion.target(version);
        // Validate down-conversion before writing any output. The wire writer consumes the canonical tree.
        byte[] converted=codec.encode(model,paths,images,version);
        if("3.8.75".equals(version))throw ProjectCodec.bad("$.skeleton.spine","Binary 3.8.75 has an obsolete layout; choose 3.8.99");
        ObjectNode tree=(ObjectNode)new ObjectMapper().readTree(codec.encode(model,paths,images));
        if(family.legacy()) {
            var warnings=new ArrayList<String>();tree=Spine38Json.normalize(new ObjectMapper().readTree(converted),warnings);
            if(!warnings.isEmpty())throw new ProjectFormatException("3.8 binary export would filter data: "+String.join("; ",warnings));
        }
        var writer=new SpineBinaryWriter(tree,family);
        writer.write(version);
        if(writer.bytes.size()>ProjectCodec.MAX_BYTES)throw new ProjectFormatException("Binary export exceeds 16 MiB");
        return writer.bytes.toByteArray();
    }
    private void varint(int value)throws IOException {while((value&~127)!=0){out.writeByte((value&127)|128);value>>>=7;}out.writeByte(value);}
    private void signed(int value)throws IOException {varint((value<<1)^(value>>31));}
    private void text(String value)throws IOException {
        if(value==null){varint(0);return;}byte[] data=value.getBytes(StandardCharsets.UTF_8);varint(data.length+1);out.write(data);
    }
    private void ref(String value)throws IOException {varint(value==null?0:strings.get(value));}
    private static String nullable(JsonNode n,String field){return n.hasNonNull(field)?n.get(field).asText():null;}
    private void number(double value)throws IOException {float f=(float)value;if(!Float.isFinite(f))throw new ProjectFormatException("Value exceeds binary float range");out.writeFloat(f);}
    private void floats(JsonNode node,String... fields)throws IOException {for(String field:fields)number(node.path(field).asDouble(defaultValue(field)));}
    private static double defaultValue(String field){return field.equals("scaleX")||field.equals("scaleY")||field.startsWith("mix")||field.equals("volume")?1:0;}
    private void color(String value)throws IOException {out.writeInt((int)Long.parseLong(value,16));}
    private void color(JsonNode node)throws IOException {color(node.path("color").asText("ffffffff"));}
    private void collect(JsonNode node){
        if(node.isTextual())strings.computeIfAbsent(node.asText(),k->strings.size()+1);
        else if(node.isObject())node.fields().forEachRemaining(e->{strings.computeIfAbsent(e.getKey(),k->strings.size()+1);collect(e.getValue());});
        else if(node.isArray())node.forEach(this::collect);
    }
    private int index(List<String> list,String value)throws IOException {int i=list.indexOf(value);if(i<0)throw new ProjectFormatException("Missing binary reference: "+value);return i;}
    private void mode(JsonNode node,String field,String fallback,String... names)throws IOException {varint(index(List.of(names),node.path(field).asText(fallback)));}
    private void write(String version)throws IOException {
        var meta=root.path("skeleton");if(family.legacy())text(meta.path("hash").asText(""));else out.writeLong(0);
        text(version);floats(meta,"x","y","width","height");if(family.atLeast(SpineVersion.V42))number(100);
        out.writeBoolean(true);number(meta.path("fps").asDouble(30));text(nullable(meta,"images"));text(nullable(meta,"audio"));
        collect(root);varint(strings.size());for(String value:strings.keySet())text(value);
        varint(root.path("bones").size());for(var bone:root.path("bones")) {
            String name=bone.path("name").asText();text(name);if(!bones.isEmpty())varint(index(bones,bone.path("parent").asText()));bones.add(name);
            floats(bone,"rotation","x","y","scaleX","scaleY","shearX","shearY");if(family!=SpineVersion.V43)floats(bone,"length");
            mode(bone,"inherit","normal","normal","onlyTranslation","noRotationOrReflection","noScale","noScaleOrReflection");
            if(family==SpineVersion.V43)floats(bone,"length");out.writeBoolean(false);color(bone);
            if(family.atLeast(SpineVersion.V42)){text(null);if(family==SpineVersion.V43){number(1);number(0);}out.writeBoolean(true);}
        }
        varint(root.path("slots").size());for(var slot:root.path("slots")) {
            String name=slot.path("name").asText();slots.add(name);text(name);varint(index(bones,slot.path("bone").asText()));color(slot);out.writeInt(-1);ref(nullable(slot,"attachment"));
            mode(slot,"blend","normal","normal","additive","multiply","screen");if(family.atLeast(SpineVersion.V42))out.writeBoolean(true);
        }
        if(family==SpineVersion.V43) {
            var constraints=new ArrayList<JsonNode>();for(String kind:List.of("ik","path","physics","transform"))root.path(kind).forEach(constraints::add);
            constraints.sort(Comparator.comparingInt(n->n.path("order").asInt()));varint(constraints.size());
            for(var constraint:constraints){
                String kind=constraint.has("bone")?"physics":constraint.has("positionMode")?"path":constraint.has("mix")?"ik":"transform";
                text(constraint.path("name").asText());out.writeByte(kind.equals("ik")?0:kind.equals("path")?1:kind.equals("transform")?2:3);constraint(constraint,kind,true);
            }
        } else for(String kind:List.of("ik","transform","path","physics")) {
            if(kind.equals("physics")&&!family.atLeast(SpineVersion.V42))continue;
            varint(root.path(kind).size());for(var constraint:root.path(kind)) {
                text(constraint.path("name").asText());varint(constraint.path("order").asInt());constraint(constraint,kind,false);
            }
        }
        var skinNodes=new ArrayList<JsonNode>();for(var skin:root.path("skins"))if(skin.path("name").asText().equals("default"))skinNodes.add(skin);
        if(skinNodes.isEmpty())throw new ProjectFormatException("Default skin required");
        for(var skin:root.path("skins"))if(!skin.path("name").asText().equals("default"))skinNodes.add(skin);
        boolean defaultPresent=!skinNodes.get(0).path("attachments").isEmpty();
        skin(skinNodes.get(0));varint(skinNodes.size()-1);
        for(int i=1;i<skinNodes.size();i++) {
            var skin=skinNodes.get(i);String name=skin.path("name").asText();if(family.atLeast(SpineVersion.V42))text(name);else ref(name);
            if(family.atLeast(SpineVersion.V42))color("ffffffff");int lists=family==SpineVersion.V43?2:family==SpineVersion.V42?5:4;
            for(int j=0;j<lists;j++)varint(0);skin(skin);
        }
        if(!defaultPresent)skins.remove("default");
        varint(root.path("events").size());for(var definitions=root.path("events").fields();definitions.hasNext();) {
            var definition=definitions.next();events.add(definition.getKey());if(family.atLeast(SpineVersion.V42))text(definition.getKey());else ref(definition.getKey());
            var event=definition.getValue();signed(event.path("int").asInt());floats(event,"float");text(event.path("string").asText(""));text(nullable(event,"audio"));if(event.has("audio"))floats(event,"volume","balance");
        }
        varint(root.path("animations").size());for(var animations=root.path("animations").fields();animations.hasNext();) {var animation=animations.next();text(animation.getKey());animation(animation.getValue());}
    }
    private void constraint(JsonNode c,String kind,boolean v43)throws IOException {
        if(kind.equals("physics")){varint(index(bones,c.path("bone").asText()));physics(c);return;}
        if(!v43&&(family!=SpineVersion.V42||kind.equals("path")))out.writeBoolean(false);
        varint(c.path("bones").size());for(var bone:c.path("bones"))varint(index(bones,bone.asText()));varint(index(kind.equals("path")?slots:bones,c.path("target").asText()));
        switch(kind) {
            case "ik"->{
                if(family.atLeast(SpineVersion.V42)) {
                    int flags=32|64|128;if(c.path("bendPositive").asBoolean(true))flags|=v43?0:2;else if(v43)flags|=4;
                    if(c.path("compress").asBoolean())flags|=v43?8:4;if(c.path("stretch").asBoolean())flags|=v43?16:8;if(!v43&&c.path("uniform").asBoolean())flags|=16;
                    out.writeByte(flags);floats(c,"mix","softness");
                }else{floats(c,"mix","softness");out.writeByte(c.path("bendPositive").asBoolean(true)?1:-1);out.writeBoolean(c.path("compress").asBoolean());out.writeBoolean(c.path("stretch").asBoolean());out.writeBoolean(c.path("uniform").asBoolean());}
            }
            case "transform"->{
                if(v43) {
                    out.writeByte(192|(c.path("local").asBoolean()?6:0)|(c.path("relative").asBoolean()?8:0));
                    for(int i=0;i<6;i++){out.writeByte(i);number(0);out.writeByte(1);out.writeByte(i);number(0);number(1);number(1);}
                    out.writeByte(63);floats(c,"rotation","x","y","scaleX","scaleY","shearY");out.writeByte(63);floats(c,"mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY");
                }else if(family==SpineVersion.V42){out.writeByte(248|(c.path("local").asBoolean()?2:0)|(c.path("relative").asBoolean()?4:0));floats(c,"rotation","x","y","scaleX","scaleY");out.writeByte(127);floats(c,"shearY","mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY");}
                else{out.writeBoolean(c.path("local").asBoolean());out.writeBoolean(c.path("relative").asBoolean());floats(c,"rotation","x","y","scaleX","scaleY","shearY");mix(c,true);}
            }
            case "path"->{
                if(family.atLeast(SpineVersion.V42)) {
                    int flags=index(List.of("fixed","percent"),c.path("positionMode").asText("percent"))|index(List.of("length","fixed","percent","proportional"),c.path("spacingMode").asText("length"))<<1|index(List.of("tangent","chain","chainScale"),c.path("rotateMode").asText("tangent"))<<3;
                    out.writeByte((v43?flags<<1:flags)|128);
                }else{mode(c,"positionMode","percent","fixed","percent");mode(c,"spacingMode","length","length","fixed","percent","proportional");mode(c,"rotateMode","tangent","tangent","chain","chainScale");}
                floats(c,"rotation","position","spacing");mix(c,false);
            }
        }
    }
    private void mix(JsonNode c,boolean transform)throws IOException {
        floats(c,"mixRotate","mixX");if(!family.legacy())floats(c,"mixY");
        if(transform){floats(c,"mixScaleX");if(!family.legacy())floats(c,"mixScaleY");floats(c,"mixShearY");}
    }
    private void physics(JsonNode c)throws IOException {
        out.writeByte(254);floats(c,"x","y","rotate","scaleX","shearX","limit");out.writeByte(60);floats(c,"inertia","strength","damping");number(1/c.path("mass").asDouble(1));floats(c,"wind","gravity");out.writeByte(128);floats(c,"mix");
    }
    private void skin(JsonNode skin)throws IOException {
        skins.add(skin.path("name").asText());varint(skin.path("attachments").size());
        for(var rows=skin.path("attachments").fields();rows.hasNext();) {var row=rows.next();varint(index(slots,row.getKey()));varint(row.getValue().size());
            for(var entries=row.getValue().fields();entries.hasNext();) {var entry=entries.next();ref(entry.getKey());attachment(entry.getKey(),entry.getValue());}
        }
    }
    private void values(JsonNode values)throws IOException {for(var value:values)number(value.asDouble());}
    private void shorts(JsonNode values,boolean length)throws IOException {if(length)varint(values.size());for(var value:values)out.writeShort(value.asInt());}
    private void vertices(JsonNode a,int count,boolean modern)throws IOException {
        var values=a.path("vertices");boolean weighted=values.size()!=count*2;if(modern)varint(count);else out.writeBoolean(weighted);
        if(!weighted){values(values);return;}
        int cursor=0;for(int i=0;i<count;i++){int size=values.get(cursor++).asInt();varint(size);for(int j=0;j<size;j++){varint(values.get(cursor++).asInt());for(int k=0;k<3;k++)number(values.get(cursor++).asDouble());}}
    }
    private void attachment(String name,JsonNode a)throws IOException {
        String kind=a.path("type").asText("region");int type=switch(kind){case "region"->0;case "boundingbox"->1;case "mesh"->2;case "path"->4;case "clipping"->6;default->throw new ProjectFormatException("Unsupported binary attachment: "+kind);};
        int size=type==2?a.path("uvs").size()/2:a.path("vertexCount").asInt();boolean weighted=a.path("vertices").size()!=size*2;
        boolean modern=family.atLeast(SpineVersion.V42);
        if(modern) {
            int flags=type;
            if(type==0||type==2)flags|=16|32;
            if(type==0)flags|=128;
            if(type==2&&weighted)flags|=128;
            if((type==1||type==6)&&weighted)flags|=16;
            if(type==4)flags|=(a.path("closed").asBoolean()?16:0)|(a.path("constantSpeed").asBoolean(true)?32:0)|(weighted?64:0);
            out.writeByte(flags);
        }else{ref(null);out.writeByte(type);}
        switch(type) {
            case 0->{ref(a.path("path").asText(name));if(modern)color(a);floats(a,"rotation","x","y","scaleX","scaleY","width","height");if(!modern)color(a);if(family==SpineVersion.V41)out.writeBoolean(false);}
            case 2->{
                ref(a.path("path").asText(name));color(a);
                if(modern){varint(a.path("hull").asInt());vertices(a,size,true);values(a.path("uvs"));if(a.path("triangles").size()!=(size*2-a.path("hull").asInt()-2)*3)throw new ProjectFormatException("Mesh triangle count cannot be represented by 4.2+ binary");shorts(a.path("triangles"),false);}
                else{varint(size);values(a.path("uvs"));shorts(a.path("triangles"),true);vertices(a,size,false);varint(a.path("hull").asInt());if(family==SpineVersion.V41)out.writeBoolean(false);}
                shorts(a.path("edges"),true);floats(a,"width","height");
            }
            default->{
                if(type==4&&!modern){out.writeBoolean(a.path("closed").asBoolean());out.writeBoolean(a.path("constantSpeed").asBoolean(true));}
                if(type==6)varint(index(slots,a.path("end").asText()));if(!modern)varint(size);vertices(a,size,modern);
                if(type==4)values(a.path("lengths"));color(a);
            }
        }
    }
    private static int bezier(JsonNode keys,int dims){int count=0;for(var key:keys)if(key.path("curve").isArray())count+=dims;return count;}
    private void frame(JsonNode key,String[] fields,int dims,boolean packed)throws IOException {
        floats(key,"time");if(packed&&dims>1) {String color=key.path("color").asText(dims==4?"ffffffff":"ffffff");for(int i=0;i<dims;i++)out.writeByte(Integer.parseInt(color.substring(i*2,i*2+2),16));}
        else for(String field:fields){double value=key.path(field).asDouble(defaultValue(field));if(packed)out.writeByte((int)Math.round(value*255));else number(value);}
    }
    private void curve(JsonNode key,JsonNode next,String[] fields,int dims,boolean packed)throws IOException {
        var curve=key.get("curve");if(curve==null){out.writeByte(0);return;}if(curve.isTextual()){out.writeByte(1);return;}out.writeByte(2);
        if(!family.legacy()){values(curve);return;}
        double time=key.path("time").asDouble(),dt=next.path("time").asDouble()-time;
        double from=0,to=1;int component=0;
        if(!fields[0].equals("progress"))for(int c=0;c<dims;c++) {
            if(packed&&dims>1){from=Integer.parseInt(key.path("color").asText().substring(c*2,c*2+2),16)/255.;to=Integer.parseInt(next.path("color").asText().substring(c*2,c*2+2),16)/255.;}
            else {from=key.path(fields[Math.min(c,fields.length-1)]).asDouble();to=next.path(fields[Math.min(c,fields.length-1)]).asDouble();}
            component=c;if(fields[0].startsWith("mix")&&fields.length==4)component=new int[]{0,1,3,5}[c];
            if(from!=to)break;
        }
        double dv=to-from;
        int base=component*4;
        number((curve.get(base).asDouble()-time)/dt);number(dv==0?0:(curve.get(base+1).asDouble()-from)/dv);number((curve.get(base+2).asDouble()-time)/dt);number(dv==0?1:(curve.get(base+3).asDouble()-from)/dv);
    }
    private void track(JsonNode keys,String[] fields,int dims,boolean packed)throws IOException {
        varint(keys.size());if(!family.legacy())varint(bezier(keys,dims));
        for(int i=0;i<keys.size();i++) {
            var key=keys.get(i);frame(key,fields,dims,packed);
            if(family.legacy()){if(i+1<keys.size())curve(key,keys.get(i+1),fields,dims,packed);}
            else if(i>0)curve(keys.get(i-1),key,fields,dims,packed);
        }
    }
    private void animation(JsonNode a)throws IOException {
        if(!family.legacy())varint(timelineCount(a));
        varint(a.path("slots").size());for(var rows=a.path("slots").fields();rows.hasNext();) {var row=rows.next();varint(index(slots,row.getKey()));varint(row.getValue().size());
            for(var tracks=row.getValue().fields();tracks.hasNext();) {var track=tracks.next();String kind=track.getKey();var keys=track.getValue();
                int type=switch(kind){case "attachment"->0;case "rgba"->1;case "rgb"->2;case "alpha"->5;default->throw new ProjectFormatException("Unsupported slot channel: "+kind);};
                if(family.legacy()&&type!=0&&type!=1)throw new ProjectFormatException("3.8 binary requires RGBA slot animation");out.writeByte(type);
                if(type==0){varint(keys.size());for(var key:keys){floats(key,"time");ref(nullable(key,"name"));}}
                else track(keys,type==5?new String[]{"value"}:new String[]{"color"},type==1?4:type==2?3:1,true);
            }
        }
        varint(a.path("bones").size());for(var rows=a.path("bones").fields();rows.hasNext();) {var row=rows.next();varint(index(bones,row.getKey()));varint(row.getValue().size());
            for(var tracks=row.getValue().fields();tracks.hasNext();) {var track=tracks.next();int type=index(List.of("rotate","translate","translatex","translatey","scale","scalex","scaley","shear","shearx","sheary"),track.getKey());
                if(family.legacy()){int old=switch(type){case 0->0;case 1->1;case 4->2;case 7->3;default->throw new ProjectFormatException("3.8 has no split-axis channels");};out.writeByte(old);}else out.writeByte(type);
                var fields=type==1||type==4||type==7?new String[]{"x","y"}:new String[]{"value"};track(track.getValue(),fields,fields.length,false);
            }
        }
        constraintTimelines(a);if(family==SpineVersion.V43)varint(0);
        varint(a.path("attachments").size());for(var all=a.path("attachments").fields();all.hasNext();) {var skin=all.next();varint(index(skins,skin.getKey()));varint(skin.getValue().size());
            for(var allSlots=skin.getValue().fields();allSlots.hasNext();) {var slot=allSlots.next();varint(index(slots,slot.getKey()));varint(slot.getValue().size());
                for(var attachments=slot.getValue().fields();attachments.hasNext();) {var attachment=attachments.next();ref(attachment.getKey());if(family.atLeast(SpineVersion.V41))out.writeByte(0);
                    var keys=attachment.getValue().path("deform");varint(keys.size());if(!family.legacy())varint(bezier(keys,1));
                    for(int i=0;i<keys.size();i++) {
                        var key=keys.get(i);if(i==0||family.legacy())floats(key,"time");
                        varint(key.path("vertices").size());if(!key.path("vertices").isEmpty()){varint(key.path("offset").asInt());values(key.path("vertices"));}
                        if(i+1<keys.size()) {if(!family.legacy())floats(keys.get(i+1),"time");curve(key,keys.get(i+1),new String[]{"progress"},1,false);}
                    }
                }
            }
        }
        varint(a.path("drawOrder").size());for(var key:a.path("drawOrder")){floats(key,"time");varint(key.path("offsets").size());for(var offset:key.path("offsets")){varint(index(slots,offset.path("slot").asText()));varint(offset.path("offset").asInt());}}
        if(family==SpineVersion.V43)varint(0); // Draw-order folder timelines.
        varint(a.path("events").size());for(var key:a.path("events")){floats(key,"time");String name=key.path("name").asText();varint(index(events,name));var definition=root.path("events").path(name);
            signed(key.path("int").asInt(definition.path("int").asInt()));number(key.path("float").asDouble(definition.path("float").asDouble()));
            if(!family.atLeast(SpineVersion.V42))out.writeBoolean(true);text(key.path("string").asText(definition.path("string").asText("")));if(definition.has("audio"))floats(key,"volume","balance");
        }
        if(family==SpineVersion.V43)color("ffffffff");
    }
    private static int timelineCount(JsonNode a){
        int n=0;for(String section:List.of("slots","bones"))for(var row:a.path(section))n+=row.size();
        for(var skin:a.path("attachments"))for(var slot:skin)n+=slot.size();
        n+=a.path("ik").size()+a.path("transform").size();for(var path:a.path("path"))n+=path.size();for(var physics:a.path("physics"))n+=physics.size();
        return n+(a.path("drawOrder").isEmpty()?0:1)+(a.path("events").isEmpty()?0:1);
    }
    private int constraintIndex(String kind,String name)throws IOException {
        int i=0;for(var item:root.path(kind)){if(item.path("name").asText().equals(name))return family==SpineVersion.V43?item.path("order").asInt():i;i++;}
        throw new ProjectFormatException("Missing constraint: "+name);
    }
    private void constraintTimelines(JsonNode animation)throws IOException {
        varint(animation.path("ik").size());for(var entries=animation.path("ik").fields();entries.hasNext();) {
            var entry=entries.next();var keys=entry.getValue();varint(constraintIndex("ik",entry.getKey()));varint(keys.size());if(!family.legacy())varint(bezier(keys,2));
            for(int i=0;i<keys.size();i++) {
                var key=keys.get(i);
                if(family.atLeast(SpineVersion.V42)) {
                    int flags=7|(key.path("bendPositive").asBoolean(true)?8:0)|(key.path("compress").asBoolean()?16:0)|(key.path("stretch").asBoolean()?32:0);
                    var previous=i>0?keys.get(i-1):null;
                    if(previous!=null){if(previous.path("curve").isTextual())flags|=64;else if(previous.path("curve").isArray())flags|=128;}
                    out.writeByte(flags);floats(key,"time","mix","softness");if((flags&128)!=0)values(previous.path("curve"));
                }else{
                    floats(key,"time","mix","softness");if(!family.legacy()&&i>0)curve(keys.get(i-1),key,new String[]{"mix","softness"},2,false);
                    out.writeByte(key.path("bendPositive").asBoolean(true)?1:-1);out.writeBoolean(key.path("compress").asBoolean());out.writeBoolean(key.path("stretch").asBoolean());
                    if(family.legacy()&&i+1<keys.size())curve(key,keys.get(i+1),new String[]{"mix","softness"},2,false);
                }
            }
        }
        varint(animation.path("transform").size());for(var entries=animation.path("transform").fields();entries.hasNext();) {
            var entry=entries.next();varint(constraintIndex("transform",entry.getKey()));
            var fields=family.legacy()?new String[]{"mixRotate","mixX","mixScaleX","mixShearY"}:new String[]{"mixRotate","mixX","mixY","mixScaleX","mixScaleY","mixShearY"};
            track(entry.getValue(),fields,fields.length,false);
        }
        varint(animation.path("path").size());for(var entries=animation.path("path").fields();entries.hasNext();) {
            var entry=entries.next();varint(constraintIndex("path",entry.getKey()));varint(entry.getValue().size());
            for(var groups=entry.getValue().fields();groups.hasNext();) {var group=groups.next();int type=index(List.of("position","spacing","mix"),group.getKey());out.writeByte(type);
                var fields=type==2?(family.legacy()?new String[]{"mixRotate","mixX"}:new String[]{"mixRotate","mixX","mixY"}):new String[]{"value"};track(group.getValue(),fields,fields.length,false);
            }
        }
        if(family.atLeast(SpineVersion.V42)) {
            varint(animation.path("physics").size());for(var entries=animation.path("physics").fields();entries.hasNext();) {
                var entry=entries.next();varint(constraintIndex("physics",entry.getKey())+1);varint(entry.getValue().size());
                for(var groups=entry.getValue().fields();groups.hasNext();) {var group=groups.next();out.writeByte(index(List.of("inertia","strength","damping","invalid","mass","wind","gravity","mix"),group.getKey()));track(group.getValue(),new String[]{"value"},1,false);}
            }
        }
    }
}
