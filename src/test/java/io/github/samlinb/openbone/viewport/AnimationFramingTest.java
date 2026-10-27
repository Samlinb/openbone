package io.github.samlinb.openbone.viewport;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.EntityId;
import static org.junit.jupiter.api.Assertions.*;

class AnimationFramingTest {
    static Keyframe key(double time,double x){return new Keyframe(EntityId.create(),time,List.of(x,0.),List.of(Curve.LINEAR,Curve.LINEAR));}
    @Test void shortActionsCoverEveryEncodedFrameIncludingTheirEndpoint(){
        var clip=new AnimationClip(EntityId.create(),"short",List.of(new BoneTimeline(EntityId.create(),Channel.TRANSLATE,List.of(key(0,0),key(7.033333778381348,10)))));
        var times=AnimationFraming.sampleTimes(clip,24,3.5);int intervals=(int)Math.ceil(clip.duration()*24);
        for(int i=0;i<=intervals;i++)assertTrue(times.contains(Math.min(clip.duration(),i/24.)));
        assertTrue(times.contains(3.5));assertTrue(times.size()>=intervals+1);
    }
    @Test void longActionsUseBoundedDenseGridPlusSourceExtrema(){
        var clip=new AnimationClip(EntityId.create(),"long",List.of(new BoneTimeline(EntityId.create(),Channel.TRANSLATE,List.of(key(0,0),key(1.123,1000),key(60,0)))));
        var times=AnimationFraming.sampleTimes(clip,24,30.);
        assertTrue(times.contains(1.123),"Sparse source-key extreme must not disappear between uniform samples");
        assertTrue(times.size()>=256);assertTrue(times.size()<=386);
    }
}
