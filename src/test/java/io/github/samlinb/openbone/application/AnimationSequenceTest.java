package io.github.samlinb.openbone.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.core.P8ModelTest;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class AnimationSequenceTest {
    @Test void orderedRepeatedActionsSkipEmptyIntervalsAndRetainFinalPose() {
        var document = ExtendedEdits.createAnimation(P8ModelTest.sample(), id(31), "empty");
        var sequence = new AnimationSequence(document, List.of(id(31), id(30), id(31), id(30), id(31)));
        assertEquals(2, sequence.duration());
        assertEquals(new AnimationSequence.Position(id(30), 0, 1), sequence.position(0));
        assertEquals(new AnimationSequence.Position(id(30), 0, 3), sequence.position(1));
        assertEquals(new AnimationSequence.Position(id(30), 1, 3), sequence.position(2));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSequence(document, List.of(id(31))));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSequence(document, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSequence(document, Collections.nCopies(129, id(30))));
    }

    @Test void eventsKeepTailBeforeNextZeroAndDoNotDuplicateAcrossTicksOrLoops() {
        var document = P8ModelTest.sample();
        var sequence = new AnimationSequence(document, List.of(id(30), id(30)));
        var first = sequence.events(document, 0, 1, true, true);
        assertEquals(List.of(0., .5, 1., 0.), first.stream().map(e -> e.key().time()).toList());
        var second = sequence.events(document, 1, 2, true, false);
        assertEquals(List.of(.5, 1., 0.), second.stream().map(e -> e.key().time()).toList());
        assertEquals(List.of(1.5, 2., 2.), second.stream().map(e -> e.absoluteTime()).toList());
        assertEquals(List.of(0L, 0L, 1L), second.stream().map(e -> e.cycle()).toList());
        assertThrows(IllegalArgumentException.class, () -> sequence.events(document, 0, 1e100, true, false));
    }
}
