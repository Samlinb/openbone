package io.github.samlinb.openbone.core;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.samlinb.openbone.core.model.AtlasRegion;
import static org.junit.jupiter.api.Assertions.*;

class AtlasRegionBoundsTest {
    static Stream<Arguments> offsets() {
        var cases=Stream.<Arguments>builder();
        for(boolean rotated:new boolean[]{false,true})
            for(boolean horizontal:new boolean[]{false,true})
                for(int offset:new int[]{-1,0,1,2,3,4,Integer.MAX_VALUE-3,Integer.MAX_VALUE-2,Integer.MAX_VALUE-1,Integer.MAX_VALUE})
                    cases.add(Arguments.of(rotated,horizontal,offset));
        return cases.build();
    }
    @ParameterizedTest @MethodSource("offsets")
    void trimContainmentCannotWrapOnEitherAxis(boolean rotated,boolean horizontal,int offset) {
        var constructor=(java.util.function.Supplier<AtlasRegion>)()->new AtlasRegion(4,4,0,0,2,2,4,4,horizontal?offset:0,horizontal?0:offset,rotated);
        if(offset>=0&&offset<=2)assertEquals(offset,horizontal?constructor.get().offsetX():constructor.get().offsetY());
        else assertThrows(IllegalArgumentException.class,constructor::get);
    }
    @Test void logicalTrimDimensionsAndRotatedPageDimensionsHaveIndependentEdges() {
        for(boolean rotated:new boolean[]{false,true}) {
            int pageWidth=rotated?6:7,pageHeight=rotated?7:6;
            assertDoesNotThrow(()->new AtlasRegion(pageWidth,pageHeight,4,4,3,2,5,4,2,2,rotated));
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,4,4,3,2,5,4,3,2,rotated));
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,4,4,3,2,5,4,2,3,rotated));
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,5,4,3,2,5,4,2,2,rotated));
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,4,5,3,2,5,4,2,2,rotated));
            for(int maximum:new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE-1}) {
                assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,maximum,4,3,2,5,4,2,2,rotated));
                assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,4,maximum,3,2,5,4,2,2,rotated));
                assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,0,0,maximum,2,5,4,2,2,rotated));
                assertThrows(IllegalArgumentException.class,()->new AtlasRegion(pageWidth,pageHeight,0,0,3,maximum,5,4,2,2,rotated));
            }
        }
    }
    @Test void maximumSupportedOriginalSizeStillAcceptsExactEdges() {
        for(boolean rotated:new boolean[]{false,true}) {
            var edge=new AtlasRegion(8192,8192,8190,8190,2,2,8192,8192,8190,8190,rotated);
            assertEquals(8190,edge.offsetX());assertEquals(8190,edge.offsetY());
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(8192,8192,0,0,2,2,8192,8192,8191,8190,rotated));
            assertThrows(IllegalArgumentException.class,()->new AtlasRegion(8192,8192,0,0,2,2,8192,8192,8190,8191,rotated));
        }
    }
}
