package io.github.samlinb.openbone.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class InactiveConstraintTest {
    private static DocumentSnapshot base() { return ConstraintTimelineTest.base(); }

    private static IkConstraint aim() {
        return new IkConstraint(id(5), "aim", 1, true, List.of(id(2)), id(3), 1, 0,
                true, false, false, false);
    }

    private static TransformConstraint dormant() {
        return new TransformConstraint(id(6), "dormant", 0, true, List.of(id(2)), id(3),
                false, false, Collections.nCopies(6, 0.), Collections.nCopies(6, 0.));
    }

    @Test void zeroMixOverlapIsPreservedWithoutChangingTheSolvedPose() throws Exception {
        var active = base().withConstraints(List.of(aim()));
        var both = active.withConstraints(List.of(aim(), dormant()));
        assertEquals(List.of(dormant(), aim()), both.constraints());
        assertEquals(active.evaluateWorld(), both.evaluateWorld());
        assertEquals(both, new ProjectCodec().decode(new ProjectCodec().encode(both)));
        var zeros = AnimationEdits.putConstraint(both, AnimationClip.DEFAULT_ID, id(6),
                ConstraintChannel.TRANSFORM_MIX, 1, Collections.nCopies(6, 0.));
        assertEquals(active.evaluateWorld(), AnimationSampler.world(zeros, AnimationClip.DEFAULT_ID, .5, false));
    }

    @Test void setupZeroDoesNotHideAnAnimatedWriterAndFailureKeepsRedo() {
        var model = base().withConstraints(List.of(aim(), dormant()));
        var history = new CommandHistory(model);
        history.execute("Move target", Commands.model(d -> ModelEdits.editBone(d, id(3),
                b -> b.withLocal(new Transform(100, 100, 0, 1, 1)))));
        history.undo();
        var before = history.state();
        var error = assertThrows(IllegalArgumentException.class, () -> history.execute("Activate overlap",
                Commands.model(d -> AnimationEdits.putConstraint(d, AnimationClip.DEFAULT_ID, id(6),
                        ConstraintChannel.TRANSFORM_MIX, 1, List.of(1., 0., 0., 0., 0., 0.)))));
        assertTrue(error.getMessage().contains("arm"), error.getMessage());
        assertTrue(error.getMessage().contains("dormant"), error.getMessage());
        assertTrue(error.getMessage().contains("aim"), error.getMessage());
        assertEquals(before, history.state());
        assertEquals(0, history.undoCount());
        assertEquals(1, history.redoCount());
        history.redo();
        assertEquals(100, history.state().model().bone(id(3)).local().x());
    }

    @Test void disabledConstraintWithNonzeroAnimatedMixStillDoesNotWrite() {
        var d = base().withConstraints(List.of(aim(), dormant().withMix(0, 1).withEnabled(false)));
        d = AnimationEdits.putConstraint(d, AnimationClip.DEFAULT_ID, id(6),
                ConstraintChannel.TRANSFORM_MIX, 1, Collections.nCopies(6, 1.));
        assertEquals(base().withConstraints(List.of(aim())).evaluateWorld(),
                AnimationSampler.world(d, AnimationClip.DEFAULT_ID, 1, false));
        var disabled = d;
        assertThrows(IllegalArgumentException.class, () -> disabled.withConstraints(
                List.of(aim(), dormant().withEnabled(true))));
    }

    @Test void inactiveDependencyDoesNotRejectTheActiveChainAndOtherClipsAreChecked() {
        var later = dormant().withBones(List.of(id(3))).withTarget(id(2)).withOrder(2);
        var d = base().withConstraints(List.of(aim(), later));
        assertEquals(base().withConstraints(List.of(aim())).evaluateWorld(), d.evaluateWorld());
        var other = new AnimationClip(id(20), "other", List.of());
        var withOther = d.withAnimations(List.of(d.animations().get(0), other));
        assertThrows(IllegalArgumentException.class, () -> AnimationEdits.putConstraint(withOther,
                other.id(), later.id(), ConstraintChannel.TRANSFORM_MIX, 2,
                List.of(1., 0., 0., 0., 0., 0.)));
    }

    @Test void inactiveIkPathAndPhysicsDoNotClaimAWriter() {
        var slot = new Slot(id(4), "path-slot", id(1), null);
        var d = new DocumentSnapshot(base().bones(), List.of(slot), List.of());
        var path = new PathConstraint(id(7), "path", 2, true, List.of(id(2)), id(4),
                PathConstraint.PositionMode.FIXED, PathConstraint.SpacingMode.FIXED,
                PathConstraint.RotateMode.TANGENT, 0, 0, 0, 0, 0, 0);
        var physics = new PhysicsConstraint(id(8), "physics", 3, true, id(2),
                List.of(1., 0., 0., 0., 0.), 5000, List.of(1., 100., .9, 1., 0., 0., 0.));
        var dormantIk = new IkConstraint(id(9), "zero-ik", 4, true, List.of(id(2)), id(3),
                0, 0, true, false, false, false);
        var both = d.withConstraints(List.of(dormant(), path, physics, dormantIk));
        assertEquals(d.evaluateWorld(), both.evaluateWorld());
        for (var channel : List.of(ConstraintChannel.IK, ConstraintChannel.PATH_MIX,
                ConstraintChannel.PHYSICS_MIX)) {
            var withActive = d.withConstraints(List.of(aim(), switch (channel) {
                case IK -> dormantIk;
                case PATH_MIX -> path;
                default -> physics;
            }));
            var target = channel == ConstraintChannel.IK ? id(9) : channel == ConstraintChannel.PATH_MIX ? id(7) : id(8);
            var values = channel == ConstraintChannel.IK ? List.of(1., 0., 1., 0., 0.)
                    : channel == ConstraintChannel.PATH_MIX ? List.of(1., 0., 0.) : List.of(1.);
            assertThrows(IllegalArgumentException.class, () -> AnimationEdits.putConstraint(withActive,
                    AnimationClip.DEFAULT_ID, target, channel, 1, values));
        }
    }
}
