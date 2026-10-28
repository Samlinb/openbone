package io.github.samlinb.openbone.desktop;

import java.awt.event.ActionEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.application.CommandHistory;
import io.github.samlinb.openbone.core.P8ModelTest;
import io.github.samlinb.openbone.core.animation.EventSampler;
import io.github.samlinb.openbone.core.model.Bone;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class AnimationPlaybackTest {
    @Test void sequenceCarriesDelayedTicksAcrossActionsAndStopsAtFinalPose() throws Exception {
        onEdt(h -> {
            h.panel.remoteSequence(List.of(id(30), id(31), id(30)), false);
            h.tick(1.25);
            assertEquals(id(31), h.panel.active());
            assertEquals(.25, h.panel.time());
            assertEquals(List.of(0., .5, 1., 0.), h.eventTimes());
            h.tick(2.5);
            assertEquals(id(30), h.panel.active());
            assertEquals(.5, h.panel.time());
            assertEquals(List.of(0., .5, 1., 0., .5, 1., 0., .5), h.eventTimes());
            h.tick(4);
            assertFalse(h.panel.playing());
            assertEquals(1, h.panel.time());
            assertEquals(9, h.events().size());
            h.click("animation.play");
            assertTrue(h.panel.playing());
            assertEquals(0, h.panel.time());
            assertEquals(List.of(0.), h.eventTimes());
        });
    }

    @Test void sequenceLoopsWholeQueueAndPausesResumesWithoutDuplicateEvents() throws Exception {
        onEdt(h -> {
            h.panel.remoteSequence(List.of(id(30), id(31)), true);
            h.tick(2);
            assertEquals(id(30), h.panel.active());
            assertEquals(0, h.panel.time());
            assertEquals(7, h.events().size());
            h.now.set(2_250_000_000L);
            h.click("animation.play");
            assertFalse(h.panel.playing());
            h.tick(10);
            assertEquals(.25, h.panel.time());
            h.click("animation.play");
            h.tick(10.25);
            assertEquals(.5, h.panel.time());
            assertEquals(8, h.events().size());
            h.click("animation.loop");
            h.tick(11.75);
            assertFalse(h.panel.playing());
            assertEquals(id(31), h.panel.active());
            assertEquals(1, h.panel.time());
        });
    }

    @Test void sequenceConfigurationIsAtomicAndSeekingExitsSequence() throws Exception {
        onEdt(h -> {
            h.panel.configureSequence(List.of(id(31), id(30)));
            h.click("animation.loop"); // Changing repeat before first play must retain the zero event.
            h.click("animation.play");
            assertEquals(List.of(0.), h.eventTimes());
            var state = h.panel.sequenceState();
            assertThrows(IllegalArgumentException.class, () -> h.panel.configureSequence(List.of(id(30), id(999))));
            assertEquals(state, h.panel.sequenceState());
            assertTrue(h.panel.playing());
            h.tick(1.25);
            assertEquals(id(30), h.panel.active());
            h.click("animation.start");
            assertEquals(id(31), h.panel.active());
            assertEquals(0, h.panel.time());
            assertFalse(h.panel.playing());
            assertEquals(true, h.panel.sequenceState().get("enabled"));
            h.panel.remoteSeek(id(30), .5);
            assertEquals(false, h.panel.sequenceState().get("enabled"));
            assertFalse(h.panel.playing());
            h.panel.reset();
            assertEquals(List.of(), h.panel.sequenceState().get("animationIds"));
        });
    }

    @Test void desktopDisablingLoopSettlesPendingEventsAndFinishesCurrentCycle() throws Exception {
        onEdt(h -> {
            h.click("animation.loop");
            h.click("animation.play");
            h.tick(3.25);
            assertEquals(10, h.events().size());
            h.now.set(3_750_000_000L); // Toggle between ticks, crossing the .5 event.
            h.click("animation.loop");
            assertEquals(.75, h.panel.time());
            assertTrue(h.panel.playing());
            assertEquals(11, h.events().size());
            assertEquals(.5, h.events().get(10).key().time());
            h.tick(4);
            assertEquals(1, h.panel.time());
            assertFalse(h.panel.playing());
            assertEquals(12, h.events().size());
            assertEquals(1, h.events().get(11).key().time());
        });
    }

    @Test void loopToggleAtBoundaryDoesNotEmitZeroTwice() throws Exception {
        onEdt(h -> {
            h.click("animation.loop");
            h.click("animation.play");
            h.tick(3);
            assertEquals(List.of(0., .5, 1., 0., .5, 1., 0., .5, 1., 0.), h.eventTimes());
            h.click("animation.loop");
            assertEquals(0, h.panel.time());
            h.tick(3.5);
            h.tick(4);
            assertFalse(h.panel.playing());
            assertEquals(List.of(0., .5, 1., 0., .5, 1., 0., .5, 1., 0., .5, 1.), h.eventTimes());
        });
    }

    @Test void remotePlayAtOrBeyondEndRestartsWithSynchronizedEventCursor() throws Exception {
        for (double start : new double[] {1, 1.5}) {
            onEdt(h -> {
                h.panel.remoteControl("play", id(30), start, false);
                assertEquals(0, h.panel.time());
                assertEquals(List.of(0.), h.eventTimes());
                h.tick(.5);
                h.tick(1);
                assertEquals(List.of(0., .5, 1.), h.eventTimes());
                assertFalse(h.panel.playing());
            });
        }
    }

    @Test void remoteLoopChangeWhilePausingSettlesOldIntervalAndResumeDoesNotReplayIt() throws Exception {
        onEdt(h -> {
            h.panel.remoteControl("play", id(30), 0., true);
            h.tick(3.25);
            h.now.set(3_750_000_000L);
            h.panel.remoteControl("pause", null, null, false);
            assertEquals(.75, h.panel.time());
            assertFalse(h.panel.playing());
            assertEquals(11, h.events().size());
            h.click("animation.play");
            h.tick(4);
            assertEquals(12, h.events().size());
            assertFalse(h.panel.playing());
        });
    }

    @Test void seekIsSilentAndPauseResumeDeliversOnlyNewEvents() throws Exception {
        onEdt(h -> {
            h.panel.remoteControl("seek", id(30), .25, false);
            assertTrue(h.events().isEmpty());
            h.panel.remoteControl("play", null, null, null);
            assertTrue(h.events().isEmpty());
            h.now.set(250_000_000L);
            h.panel.remoteControl("pause", null, null, null);
            assertEquals(List.of(.5), h.eventTimes());
            h.click("animation.play");
            h.tick(.5);
            assertEquals(List.of(.5), h.eventTimes());
            h.tick(.75);
            assertEquals(List.of(.5, 1.), h.eventTimes());
            h.panel.remoteControl("seek", id(30), 0., null);
            assertTrue(h.events().isEmpty());
            h.click("animation.play");
            assertEquals(List.of(0.), h.eventTimes());
        });
    }

    private static void onEdt(Consumer<Harness> test) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var h = new Harness();
            try {
                test.accept(h);
                assertSame(h.document, h.history.state().model());
                assertEquals(0, h.history.undoCount());
                assertFalse(h.history.isDirty());
                assertTrue(h.errors.isEmpty(), () -> h.errors.toString());
            } finally {
                h.panel.stop();
            }
        });
    }

    private static final class Harness implements AnimationPanel.Host {
        final DocumentSnapshot document = io.github.samlinb.openbone.application.ExtendedEdits
                .duplicateAnimation(P8ModelTest.sample(), id(30), id(31), "second");
        final CommandHistory history = new CommandHistory(document);
        final Map<String, JComponent> controls = new HashMap<>();
        final List<Exception> errors = new ArrayList<>();
        final AtomicLong now = new AtomicLong();
        final AnimationPanel panel;
        final Timer timer;

        Harness() {
            panel = new AnimationPanel(this, now::get);
            panel.stop(); // Drive the real timer callback deterministically on the EDT.
            try {
                var field = AnimationPanel.class.getDeclaredField("timer");
                field.setAccessible(true);
                timer = (Timer) field.get(panel);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            panel.remoteSeek(id(30), 0);
        }

        void click(String name) { ((AbstractButton) controls.get(name)).doClick(0); }
        void tick(double seconds) {
            now.set(Math.round(seconds * 1_000_000_000L));
            for (var listener : timer.getActionListeners())
                listener.actionPerformed(new ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "tick"));
            assertTrue(errors.isEmpty(), () -> errors.toString());
        }
        @SuppressWarnings("unchecked")
        List<EventSampler.Fired> events() {
            return (List<EventSampler.Fired>) panel.diagnostics().get("recentEvents");
        }
        List<Double> eventTimes() { return events().stream().map(e -> e.key().time()).toList(); }
        public CommandHistory history() { return history; }
        public Bone selectedBone() { return null; }
        public boolean commitFields() { return true; }
        public void changed() { if (panel != null) panel.refresh(); }
        public void error(Exception e) { errors.add(e); }
        public void cancelViewport() { }
        public void poseChanged() { }
        public Map<String, JComponent> controls() { return controls; }
    }
}
