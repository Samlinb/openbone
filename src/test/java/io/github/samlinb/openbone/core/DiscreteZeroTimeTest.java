package io.github.samlinb.openbone.core;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class DiscreteZeroTimeTest {
    static Stream<Arguments> zeros() {return Stream.of(Arguments.of(0.0,0.0),Arguments.of(0.0,-0.0),Arguments.of(-0.0,0.0),Arguments.of(-0.0,-0.0));}
    private DiscreteTracks.EventKey event(int n,double time,int value){return new DiscreteTracks.EventKey(id(n),time,id(40),value,0,"event"+value,1,0);}
    private void positiveZero(double time){assertEquals(0L,Double.doubleToRawLongBits(time));}
    @ParameterizedTest @ValueSource(doubles={0.0,-0.0})
    void everyDiscreteRecordStoresCanonicalPositiveZero(double time) {
        positiveZero(new DiscreteTracks.AttachmentKey(id(1),time,id(10),null).time());
        positiveZero(new DiscreteTracks.OrderKey(id(2),time,List.of(id(10))).time());positiveZero(event(3,time,1).time());
    }
    @ParameterizedTest @MethodSource("zeros")
    void conflictingAttachmentAndOrderTimesRejectAllZeroSignPairs(double first,double second) {
        var a=new DiscreteTracks.AttachmentKey(id(1),first,id(10),"a");var b=new DiscreteTracks.AttachmentKey(id(2),second,id(10),null);
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks(List.of(a,b),List.of(),List.of()));
        var x=new DiscreteTracks.OrderKey(id(3),first,List.of(id(10),id(11)));var y=new DiscreteTracks.OrderKey(id(4),second,List.of(id(11),id(10)));
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks(List.of(),List.of(x,y),List.of()));
    }
    @ParameterizedTest @MethodSource("zeros")
    void equalTimeEventsStayStableAndDifferentSlotKeysStayLegal(double first,double second) {
        var a=event(1,first,11);var b=event(2,second,22);
        var tracks=new DiscreteTracks(List.of(new DiscreteTracks.AttachmentKey(id(3),first,id(10),null),new DiscreteTracks.AttachmentKey(id(4),second,id(11),null)),List.of(),List.of(a,b));
        assertEquals(List.of(a,b),tracks.events());tracks.keys().forEach(k->positiveZero(k.time()));
        var clip=new AnimationClip(id(30),"events",List.of(),List.of(),List.of(),tracks);
        assertEquals(List.of(11,22),EventSampler.between(clip,0,0,false,true).stream().map(f->f.key().intValue()).toList());
        assertEquals(List.of(11,22),tracks.duplicate().events().stream().map(DiscreteTracks.EventKey::intValue).toList());
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks(List.of(),List.of(),List.of(a,a)));
    }
    @ParameterizedTest @ValueSource(doubles={-1.0,-4.9E-324,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
    void allDiscreteRecordsKeepInvalidTimeRejection(double time) {
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks.AttachmentKey(id(1),time,id(10),null));
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks.OrderKey(id(2),time,List.of(id(10))));
        assertThrows(IllegalArgumentException.class,()->event(3,time,1));
    }
    @Test void distinctPositiveTimesAndOrdinaryDuplicatesKeepTheirMeaning() {
        var first=new DiscreteTracks.AttachmentKey(id(1),0,id(10),null);var next=new DiscreteTracks.AttachmentKey(id(2),Double.MIN_VALUE,id(10),"a");
        assertEquals(2,new DiscreteTracks(List.of(next,first),List.of(),List.of()).attachments().size());assertEquals(Double.MIN_VALUE,next.time());
        assertThrows(IllegalArgumentException.class,()->new DiscreteTracks(List.of(new DiscreteTracks.AttachmentKey(id(3),.5,id(10),"a"),new DiscreteTracks.AttachmentKey(id(4),.5,id(10),null)),List.of(),List.of()));
    }
    @Test void commandUpsertsAtNegativeThenPositiveZeroPreserveKeyIdAndHistory() {
        var d=P8ModelTest.sample();var history=new CommandHistory(d);
        history.execute("negative zero",Commands.model(m->ExtendedEdits.attachment(m,id(30),id(10),-0.0,null)));
        var negative=history.state().model();var key=negative.animation(id(30)).discrete().attachments().get(0);positiveZero(key.time());
        history.execute("positive zero",Commands.model(m->ExtendedEdits.attachment(m,id(30),id(10),0,"image")));
        var keys=history.state().model().animation(id(30)).discrete().attachments();assertEquals(3,keys.size());assertEquals(key.id(),keys.get(0).id());
        history.undo();assertEquals(negative,history.state().model());history.redo();assertEquals("image",history.state().model().animation(id(30)).discrete().attachments().get(0).name());
        var first=ExtendedEdits.drawOrder(d,id(30),-0.0,List.of(id(12),id(10),id(11)));var order=first.animation(id(30)).discrete().drawOrder().get(0);
        var second=ExtendedEdits.drawOrder(first,id(30),0,List.of(id(10),id(11),id(12)));var orders=second.animation(id(30)).discrete().drawOrder();assertEquals(3,orders.size());assertEquals(order.id(),orders.get(0).id());positiveZero(orders.get(0).time());
    }
}
