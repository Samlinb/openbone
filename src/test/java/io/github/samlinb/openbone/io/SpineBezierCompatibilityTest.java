package io.github.samlinb.openbone.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.samlinb.openbone.core.model.Rgba;

class SpineBezierCompatibilityTest {
    private static double chord(double time,double start,double x1,double y1,double x2,double y2,double end) {
        if(time<=0)return start;if(time>=1)return end;
        double px=0,py=start;
        for(int i=1;i<=10;i++) {
            double u=i/10.,v=1-u;
            double x=i==10?1:3*v*v*u*x1+3*v*u*u*x2+u*u*u;
            double y=i==10?end:v*v*v*start+3*v*v*u*y1+3*v*u*u*y2+u*u*u*end;
            if(time<=x)return py+(y-py)*(time-px)/(x-px);
            px=x;py=y;
        }
        return end;
    }
    @Test void flatEndpointsAndLoopingTimeHandlesKeepTheFirstCrossingFunction()throws Exception {
        String text="""
            {"skeleton":{"spine":"4.2.22"},"bones":[{"name":"root"}],"slots":[],
             "skins":[{"name":"default","attachments":{}}],"animations":{"a":{"bones":{"root":{
             "translate":[{"curve":[1.5,2,-0.5,-1,0.4,5,0.8,-3]},{"time":1,"y":4}]}}}}}
            """;
        var model=new SkeletonJsonCodec().decode(text.getBytes(StandardCharsets.UTF_8),Path.of("."));
        var track=model.animations().get(0).timelines().get(0);
        for(int i=0;i<=1000;i++) {
            double time=(float)(i/1000.);var sample=track.sample(time).orElseThrow();
            assertEquals(chord(time,0,1.5,2,-.5,-1,0),sample.get(0),1e-11,"time="+time);
            assertEquals(chord(time,0,.4,5,.8,-3,4),sample.get(1),1e-11);
        }
        for(var key:track.keys())assertEquals(chord(key.time(),0,1.5,2,-.5,-1,0),track.sample(key.time()).orElseThrow().get(0),1e-11);
    }
    @Test void flatColorEndpointsPreserveOvershootClampingAndOtherComponents()throws Exception {
        String text="""
            {"skeleton":{"spine":"4.2.22"},"bones":[{"name":"root"}],"slots":[{"name":"slot","bone":"root"}],
             "skins":[{"name":"default","attachments":{}}],"animations":{"a":{"slots":{"slot":{
             "alpha":[{"value":0.5,"curve":[1.5,2,-0.5,-1]},{"time":1,"value":0.5}]}}}}}
            """;
        var model=new SkeletonJsonCodec().decode(text.getBytes(StandardCharsets.UTF_8),Path.of("."));
        var track=model.animations().get(0).discrete().colors().get(0);
        for(int i=0;i<=1000;i++) {
            double time=(float)(i/1000.);
            assertEquals(Rgba.clamp(chord(time,.5,1.5,2,-.5,-1,.5)),track.sample(Rgba.WHITE,time).a(),1e-11,"time="+time);
        }
    }
}
