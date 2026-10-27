package io.github.samlinb.openbone.viewport;

import java.util.*;
import java.util.concurrent.CancellationException;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Vec2;
import io.github.samlinb.openbone.core.model.*;

/** Detached CPU evaluation of one immutable animation before fitting the live camera once. */
public final class AnimationFraming {
    private AnimationFraming() {}
    public record Bounds(double minX,double minY,double maxX,double maxY,int samples) {}

    public static Bounds sample(DocumentSnapshot document,EntityId animation,int fps,double posterTime) {
        var clip=document.animation(animation);var times=sampleTimes(clip,fps,posterTime);
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(double time:times) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            var pose=AnimationSampler.world(document,animation,time,false);
            for(var draw:VisibleGeometry.evaluate(document,pose))if(draw.color().a()>0)
                for(Vec2 point:draw.geometry().vertices()) {
                    minX=Math.min(minX,point.x());minY=Math.min(minY,point.y());
                    maxX=Math.max(maxX,point.x());maxY=Math.max(maxY,point.y());
                }
        }
        return minX==Double.POSITIVE_INFINITY?null:new Bounds(minX,minY,maxX,maxY,times.size());
    }

    static SortedSet<Double> sampleTimes(AnimationClip clip,int fps,double posterTime) {
        if(fps<1||fps>60)throw new IllegalArgumentException("Invalid framing FPS");
        double duration=clip.duration();var times=new TreeSet<Double>();times.add(0.);times.add(duration);times.add(posterTime);
        int intervals=(int)Math.ceil(duration*fps);
        if(intervals<=255) {
            for(int i=0;i<=intervals;i++)times.add(Math.min(duration,i/(double)fps));
        } else {
            for(int i=0;i<256;i++)times.add(duration*i/255.);
            // Include source transition times and global component extrema, rather
            // than relying only on a uniform grid for sparse or stepped actions.
            var critical=new TreeSet<Double>();
            clip.timelines().forEach(t->extrema(t.keys(),critical));
            clip.constraintTracks().forEach(t->extrema(t.keys(),critical));
            clip.deforms().forEach(t->extrema(t.keys(),critical));
            clip.discrete().attachments().forEach(k->critical.add(k.time()));
            clip.discrete().drawOrder().forEach(k->critical.add(k.time()));
            var candidates=new ArrayList<>(critical);
            if(candidates.size()<=128)times.addAll(candidates);
            else for(int i=0;i<128;i++)times.add(candidates.get((int)((long)i*(candidates.size()-1)/127)));
        }
        return Collections.unmodifiableSortedSet(times);
    }
    private static void extrema(List<Keyframe> keys,Set<Double> result) {
        if(keys.isEmpty())return;
        result.add(keys.get(0).time());result.add(keys.get(keys.size()-1).time());
        int components=keys.get(0).values().size();
        for(int component=0;component<components;component++) {
            Keyframe min=keys.get(0),max=min;
            for(var key:keys){if(key.values().get(component)<min.values().get(component))min=key;if(key.values().get(component)>max.values().get(component))max=key;}
            result.add(min.time());result.add(max.time());
        }
    }
}
