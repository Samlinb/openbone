package io.github.samlinb.openbone.viewport;

import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.core.math.Vec2;
import static org.junit.jupiter.api.Assertions.*;

class CameraFramingTest {
    @Test void explicitWideBoundsFitBelowWheelMinimumWithoutChangingWheelLimits(){
        var camera=new Camera2D();camera.fit(-30_000,-20_000,50_000,20_000,944,609,.70);
        assertTrue(camera.zoom()<.05);assertEquals(10_000,camera.x());assertEquals(0,camera.y());
        for(var point:new Vec2[]{new Vec2(-30_000,-20_000),new Vec2(50_000,20_000)}) {
            var screen=camera.screen(point,944,609);
            assertTrue(screen.x()>=944*.15-1e-8&&screen.x()<=944*.85+1e-8);
            assertTrue(screen.y()>=609*.15-1e-8&&screen.y()<=609*.85+1e-8);
        }
        camera.zoomAt(.00001,472,304.5,944,609);assertEquals(.05,camera.zoom());
        camera.zoomAt(100,472,304.5,944,609);assertEquals(20,camera.zoom());
    }
    @Test void finiteHugeBoundsAvoidSpanOverflowAndInvalidFitsAreAtomic(){
        var camera=new Camera2D();camera.fit(-Double.MAX_VALUE,-1,Double.MAX_VALUE,1,944,609,.70);
        assertTrue(Double.isFinite(camera.zoom())&&camera.zoom()>0);
        double x=camera.x(),y=camera.y(),zoom=camera.zoom();
        assertThrows(IllegalArgumentException.class,()->camera.fit(1,0,-1,1,944,609,.70));
        assertThrows(IllegalArgumentException.class,()->camera.fit(0,0,1,1,944,609,Double.NaN));
        assertEquals(x,camera.x());assertEquals(y,camera.y());assertEquals(zoom,camera.zoom());
    }
}
