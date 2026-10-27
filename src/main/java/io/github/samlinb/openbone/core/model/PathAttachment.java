package io.github.samlinb.openbone.core.model;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Spine path layout, including unused open end handles and cumulative lengths.
 * Weighted paths use vertices for control count; influence-local points are authoritative.
 */
public record PathAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, boolean closed,
        boolean constantSpeed, List<Double> lengths, List<List<Influence>> weights) implements Attachment {
    public PathAttachment(EntityId id, String name, EntityId slotId, List<Vec2> vertices, boolean closed,
            boolean constantSpeed, List<Double> lengths) {
        this(id,name,slotId,vertices,closed,constantSpeed,lengths,List.of());
    }
    public PathAttachment {
        Objects.requireNonNull(id);
        name = Names.require(name);
        Objects.requireNonNull(slotId);
        vertices = List.copyOf(vertices);
        lengths = List.copyOf(lengths);
        weights = weights.stream().map(List::copyOf).toList();
        if (!weights.isEmpty() && weights.size() != vertices.size())
            throw new IllegalArgumentException(m("pathattachment.weightCount"));
        weights.forEach(Influence::validate);
        if (vertices.size() % 3 != 0 || vertices.size() < (closed ? 3 : 6) || vertices.size() > 510)
            throw new IllegalArgumentException(m("pathattachment.0001"));
        if (lengths.size() != vertices.size() / 3)
            throw new IllegalArgumentException(m("pathattachment.0002"));
        double prior = 0;
        int index = 0;
        for (double v : lengths) {
            Finite.require(v, "path cumulative length");
            if (v < 0 || index < vertices.size() / 3 - (closed ? 0 : 1) && v < prior)
                throw new IllegalArgumentException(m("pathattachment.0003"));
            prior = v;
            index++;
        }
    }

    public PathAttachment withVertices(List<Vec2> v) {
        if (weighted()) throw new IllegalArgumentException(m("pathattachment.weightedEditing"));
        return new PathAttachment(id, name, slotId, v, closed, constantSpeed, lengths);
    }

    public PathAttachment withName(String v) {
        return new PathAttachment(id, v, slotId, vertices, closed, constantSpeed, lengths, weights);
    }

    public PathAttachment withOptions(boolean c, boolean speed) {
        return new PathAttachment(id, name, slotId, vertices, c, speed, lengths, weights);
    }

    public PathAttachment withLengths(List<Double> v) {
        return new PathAttachment(id, name, slotId, vertices, closed, constantSpeed, v, weights);
    }

    public boolean weighted() { return !weights.isEmpty(); }

    public int deformSize() {
        return (weighted() ? weights.stream().mapToInt(List::size).sum() : vertices.size()) * 2;
    }

    /** Deforms are offsets in each influence bone's local space, before skinning. */
    public List<Vec2> worldVertices(Map<EntityId, Affine2> world, Affine2 slot, List<Double> deform) {
        if (deform != null && deform.size() != deformSize())
            throw new IllegalArgumentException(m("worldpose.0001"));
        var result = new ArrayList<Vec2>();
        int cursor = 0;
        if (!weighted()) {
            for (var point : vertices) {
                double x=point.x()+(deform==null?0:deform.get(cursor++));
                double y=point.y()+(deform==null?0:deform.get(cursor++));
                result.add(slot.apply(x,y));
            }
        } else for (var row : weights) {
            double x=0,y=0;
            for (var influence : row) {
                double px=influence.point().x()+(deform==null?0:deform.get(cursor++));
                double py=influence.point().y()+(deform==null?0:deform.get(cursor++));
                var point=world.get(influence.boneId()).apply(px,py);
                x=Finite.require(x+point.x()*influence.weight(),"weighted path x");
                y=Finite.require(y+point.y()*influence.weight(),"weighted path y");
            }
            result.add(new Vec2(x,y));
        }
        return List.copyOf(result);
    }

    /** Whole-animation bound using every influencing bone and deform component. */
    public double controlBound(Map<EntityId,double[]> world,double slotNorm,double slotPosition,double[] deform) {
        double bound=0;
        int cursor=0;
        if (!weighted()) for (var point:vertices) {
            double x=Finite.require(Math.abs(point.x())+deform[cursor++],"path local bound");
            double y=Finite.require(Math.abs(point.y())+deform[cursor++],"path local bound");
            bound=Math.max(bound,positionBound(slotNorm,slotPosition,x,y));
        } else for (var row:weights) {
            double sum=0;
            for (var influence:row) {
                var bone=world.get(influence.boneId());
                double x=Finite.require(Math.abs(influence.point().x())+deform[cursor++],"path influence bound");
                double y=Finite.require(Math.abs(influence.point().y())+deform[cursor++],"path influence bound");
                double point=positionBound(bone[0],bone[1],x,y);
                sum=Finite.require(Math.nextUp((sum+point*influence.weight())*(1+1e-14)+8*Double.MIN_VALUE),"path skin bound");
            }
            bound=Math.max(bound,sum);
        }
        return bound;
    }

    private static double positionBound(double norm,double position,double x,double y) {
        if(position==0&&(norm==0||x==0&&y==0))return 0;
        double offset=norm==0?0:norm*Math.nextUp(Math.hypot(x,y));
        return Finite.require(Math.nextUp((position+offset)*(1+1e-14)+8*Double.MIN_VALUE),"path control bound");
    }

    /**
     * Canonical cubic chain p0,c1,c2,p1,..., independent of the attachment storage
     * layout.
     */
    public List<Vec2> chain(List<Vec2> points) {
        var result = new ArrayList<>(points.subList(1, closed ? points.size() : points.size() - 1));
        if (closed) {
            result.add(points.get(0));
            result.add(points.get(1));
        }
        return List.copyOf(result);
    }

    public int curveCount() {
        return vertices.size() / 3 - (closed ? 0 : 1);
    }
}
