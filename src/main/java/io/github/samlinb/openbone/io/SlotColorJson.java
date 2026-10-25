package io.github.samlinb.openbone.io;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.io.ProjectCodec.*;

/** Name translation and absolute Bezier controls for single-color slot timelines. */
final class SlotColorJson {
    private SlotColorJson() {}
    static List<Double> values(JsonNode n, SlotColorTimeline.Channel channel) throws ProjectFormatException {
        if (channel == SlotColorTimeline.Channel.ALPHA) return List.of(n.has("value") ? number(n,"value","color key") : 0);
        String color = text(n,"color","color key");
        try {
            Rgba rgba = Rgba.parse(channel == SlotColorTimeline.Channel.RGB ? color + "ff" : color);
            return rgba.values().subList(0,channel.components());
        } catch (IllegalArgumentException e) { throw bad("color key.color",e.getMessage()); }
    }
    static List<SlotColorTimeline> read(JsonNode animation, List<Slot> slots) throws ProjectFormatException {
        var tracks = new ArrayList<SlotColorTimeline>();
        for (var rows = animation.path("slots").fields(); rows.hasNext();) {
            var row = rows.next();
            var slot = slots.stream().filter(s -> s.name().equals(row.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown color slot " + row.getKey()));
            for (var channel : SlotColorTimeline.Channel.values()) {
                String name = channel.name().toLowerCase(Locale.ROOT);
                if (!row.getValue().has(name)) continue;
                JsonNode array = row.getValue().get(name);
                if (!array.isArray() || array.isEmpty()) throw bad("slots."+name,"Expected nonempty key array");
                var keys = new ArrayList<Keyframe>();
                for (int i=0;i<array.size();i++) {
                    var n = array.get(i);
                    if(!n.isObject()) throw bad("color key","Expected object");
                    var allowed=Set.of("time",channel == SlotColorTimeline.Channel.ALPHA ? "value" : "color","curve");
                    for(var fields=n.fieldNames();fields.hasNext();) {
                        String field=fields.next(); if(!allowed.contains(field)) throw bad("color key."+field,"Unknown field");
                    }
                    double time = n.has("time") ? number(n,"time","color key") : 0;
                    var value = values(n,channel); var curves = new ArrayList<Curve>();
                    var curve = n.get("curve");
                    if (curve != null && i+1==array.size()) throw bad("color key.curve","Last key has no outgoing curve");
                    for (int c=0;c<channel.components();c++) {
                        Curve parsed = Curve.LINEAR;
                        if (curve != null) {
                            if (curve.isTextual() && curve.asText().equals("stepped")) parsed = Curve.STEPPED;
                            else {
                                if (!curve.isArray() || curve.size()!=channel.components()*4) throw bad("color key.curve","Expected per-component absolute Bezier controls");
                                double nextTime=array.get(i+1).path("time").asDouble(), dt=nextTime-time;
                                if (dt<=0) throw bad("color key.time","Keys must increase");
                                double v=value.get(c), dv=values(array.get(i+1),channel).get(c)-v;
                                double[] control=new double[4];
                                for(int j=0;j<4;j++) {
                                    JsonNode x=curve.get(c*4+j);
                                    if(!x.isNumber()) throw bad("color key.curve","Expected numeric controls");
                                    control[j]=RuntimeRange.number(x.doubleValue(),"color curve");
                                }
                                if(dv==0 && (control[1]!=v || control[3]!=v)) throw bad("color key.curve","Nonconstant controls at equal endpoints are unsupported");
                                parsed=new Curve(Curve.Kind.BEZIER,(control[0]-time)/dt,dv==0?0:(control[1]-v)/dv,
                                        (control[2]-time)/dt,dv==0?1:(control[3]-v)/dv);
                            }
                        }
                        curves.add(parsed);
                    }
                    keys.add(new Keyframe(EntityId.create(),time,value,curves));
                }
                tracks.add(new SlotColorTimeline(slot.id(),channel,keys));
            }
        }
        return tracks;
    }
    static void write(ObjectNode animation, DocumentSnapshot d, List<SlotColorTimeline> tracks) throws ProjectFormatException {
        for(var t:tracks) {
            String name=d.slots().stream().filter(s->s.id().equals(t.slotId())).findFirst().orElseThrow().name();
            var keys=animation.withObject("/slots").withObject("/"+escape(name)).putArray(t.channel().name().toLowerCase(Locale.ROOT));
            for(int i=0;i<t.keys().size();i++) {
                var k=t.keys().get(i); var n=keys.addObject().put("time",k.time());
                if(t.channel()==SlotColorTimeline.Channel.ALPHA) n.put("value",k.values().get(0));
                else {
                    var v=k.values(); String color=new Rgba(v.get(0),v.get(1),v.get(2),v.size()==4?v.get(3):1).hex();
                    n.put("color",v.size()==3?color.substring(0,6):color);
                }
                if(i+1==t.keys().size()) continue;
                var next=t.keys().get(i+1); boolean stepped=false, continuous=false, bezier=false;
                for(int c=0;c<k.values().size();c++) if(!k.values().get(c).equals(next.values().get(c))) {
                    stepped |= k.curves().get(c).kind()==Curve.Kind.STEPPED;
                    continuous |= k.curves().get(c).kind()!=Curve.Kind.STEPPED;
                    bezier |= k.curves().get(c).kind()==Curve.Kind.BEZIER;
                }
                if(stepped && continuous) throw bad("slots."+name,"A color group cannot mix stepped and continuous components");
                if(stepped) { n.put("curve","stepped"); continue; }
                if(!bezier) continue;
                var out=n.putArray("curve");
                var start=values(n,t.channel());
                // RGB(A) endpoints are stored as bytes in the runtime format.
                var end=new ArrayList<Double>();
                for(double v:next.values()) end.add(t.channel()==SlotColorTimeline.Channel.ALPHA?v:Math.round(v*255)/255.);
                for(int c=0;c<k.values().size();c++) {
                    var curve=k.curves().get(c); boolean cubic=curve.kind()==Curve.Kind.BEZIER;
                    double dt=next.time()-k.time(), v=start.get(c), dv=end.get(c)-v;
                    out.add(k.time()+dt*(cubic?curve.x1():1./3)).add(v+dv*(cubic?curve.y1():1./3))
                            .add(k.time()+dt*(cubic?curve.x2():2./3)).add(v+dv*(cubic?curve.y2():2./3));
                }
            }
        }
    }
    static void export38(ObjectNode animation, DocumentSnapshot d, List<SlotColorTimeline> tracks) throws ProjectFormatException {
        for(var slot:d.slots()) {
            var rows=tracks.stream().filter(t->t.slotId().equals(slot.id())).toList(); if(rows.isEmpty()) continue;
            var times=rows.get(0).keys().stream().map(Keyframe::time).toList();
            for(var t:rows) if(!times.equals(t.keys().stream().map(Keyframe::time).toList()))
                throw bad("slots."+slot.name(),"3.8 requires matching RGB and alpha key times");
            var row=animation.withObject("/slots").withObject("/"+escape(slot.name())); row.remove(List.of("rgba","rgb","alpha"));
            var out=row.putArray("color");
            for(int i=0;i<times.size();i++) {
                Rgba value=slot.color(); for(var t:rows) value=t.sample(value,times.get(i));
                var n=out.addObject().put("time",times.get(i)).put("color",value.hex()); Curve shared=null;
                if(i+1==times.size()) continue;
                for(var t:rows) {
                    var k=t.keys().get(i); var next=t.keys().get(i+1);
                    for(int c=0;c<k.values().size();c++) if(!k.values().get(c).equals(next.values().get(c))) {
                        var curve=k.curves().get(c);
                        if(shared!=null && !sameCurve(shared,curve)) throw bad("slots."+slot.name(),"3.8 requires a shared color curve");
                        shared=curve;
                    }
                }
                if(shared!=null && shared.kind()==Curve.Kind.STEPPED) n.put("curve","stepped");
                if(shared!=null && shared.kind()==Curve.Kind.BEZIER) n.put("curve",shared.x1()).put("c2",shared.y1()).put("c3",shared.x2()).put("c4",shared.y2());
            }
        }
    }
    private static String escape(String s) { return s.replace("~","~0").replace("/","~1"); }
    private static boolean sameCurve(Curve a,Curve b) {
        return a.kind()==b.kind() && Math.abs(a.x1()-b.x1())<1e-10 && Math.abs(a.y1()-b.y1())<1e-10
                && Math.abs(a.x2()-b.x2())<1e-10 && Math.abs(a.y2()-b.y2())<1e-10;
    }
}
