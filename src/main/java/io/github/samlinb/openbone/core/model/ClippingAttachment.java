package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;

/**
 * Static unweighted polygon in slot-bone space. Derived triangles are cached,
 * not serialized.
 */
public final class ClippingAttachment implements Attachment {
    private final EntityId id, slotId, endSlotId;
    private final String name;
    private final List<Vec2> vertices;
    private final List<Integer> triangles;
    private final List<List<Influence>> weights;
    private final boolean runtimeTopology;
    public static final Rgba DEFAULT_COLOR = Rgba.parse("ce3a3aff");
    private final Rgba color;

    public ClippingAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, EntityId endSlotId) {
        this(id, name, slotId, vertices, endSlotId, DEFAULT_COLOR);
    }

    public ClippingAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, EntityId endSlotId, Rgba color) {
        this(id,name,slotId,vertices,endSlotId,color,List.of(),false);
    }

    public ClippingAttachment(EntityId id,String name,EntityId slotId,List<Vec2> vertices,EntityId endSlotId,Rgba color,
            List<List<Influence>> weights,boolean runtimeTopology) {
        this.color = Objects.requireNonNull(color);
        this.id = Objects.requireNonNull(id);
        this.name = Names.require(name);
        this.slotId = Objects.requireNonNull(slotId);
        this.vertices = List.copyOf(vertices);
        this.endSlotId = endSlotId;
        this.weights=weights.stream().map(List::copyOf).toList();
        this.runtimeTopology=runtimeTopology;
        if(!this.weights.isEmpty() && this.weights.size()!=vertices.size())throw new IllegalArgumentException("Clipping weight count differs from vertex count");
        this.weights.forEach(Influence::validate);
        this.triangles = runtimeTopology ? MeshTopology.triangulateRuntimePolygon(this.vertices)
                : MeshTopology.triangulate(this.vertices, this.vertices.size());
    }

    public EntityId id() {
        return id;
    }

    public Rgba color() { return color; }

    public String name() {
        return name;
    }

    public EntityId slotId() {
        return slotId;
    }

    public EntityId endSlotId() {
        return endSlotId;
    }

    public List<Vec2> vertices() {
        return vertices;
    }

    public List<Integer> triangles() {
        return triangles;
    }

    public ClippingAttachment withVertices(List<Vec2> v) {
        if(weighted())throw new IllegalArgumentException("Weighted clipping uses bone-local influence points");
        return new ClippingAttachment(id, name, slotId, v, endSlotId, color,weights,runtimeTopology);
    }

    public List<List<Influence>> weights(){return weights;}
    public boolean weighted(){return !weights.isEmpty();}
    public boolean runtimeTopology(){return runtimeTopology;}
    public int deformSize(){return 2*(weighted()?weights.stream().mapToInt(List::size).sum():vertices.size());}

    public List<Vec2> worldVertices(Map<EntityId,Affine2> world,Affine2 slot,List<Double> offsets) {
        if(offsets!=null&&offsets.size()!=deformSize())throw new IllegalArgumentException("Clipping deform size differs from layout");
        var result=new ArrayList<Vec2>();int cursor=0;
        if(!weighted())for(var vertex:vertices) {
            double x=vertex.x()+(offsets==null?0:offsets.get(cursor++));
            double y=vertex.y()+(offsets==null?0:offsets.get(cursor++));
            result.add(slot.apply(x,y));
        } else for(var row:weights) {
            double x=0,y=0;
            for(var influence:row) {
                double px=influence.point().x()+(offsets==null?0:offsets.get(cursor++));
                double py=influence.point().y()+(offsets==null?0:offsets.get(cursor++));
                var p=world.get(influence.boneId()).apply(px,py);
                x=Finite.require(x+p.x()*influence.weight(),"weighted clipping x");
                y=Finite.require(y+p.y()*influence.weight(),"weighted clipping y");
            }
            result.add(new Vec2(x,y));
        }
        return List.copyOf(result);
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ClippingAttachment c && id.equals(c.id) && name.equals(c.name)
                && slotId.equals(c.slotId) && vertices.equals(c.vertices) && Objects.equals(endSlotId, c.endSlotId) && color.equals(c.color)
                && weights.equals(c.weights) && runtimeTopology==c.runtimeTopology;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, slotId, vertices, endSlotId, color,weights,runtimeTopology);
    }
}
