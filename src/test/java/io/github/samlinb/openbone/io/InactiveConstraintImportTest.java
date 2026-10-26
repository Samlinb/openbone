package io.github.samlinb.openbone.io;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.application.AnimationEdits;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class InactiveConstraintImportTest {
    private static final String SAMPLE = """
        {"skeleton":{"spine":"3.8.55"},
         "bones":[{"name":"root"},{"name":"arm","parent":"root","length":100},
                  {"name":"target","parent":"root","y":100}],
         "ik":[{"name":"aim","order":12,"bones":["arm"],"target":"target"}],
         "transform":[{"name":"dormant","order":11,"bones":["arm"],"target":"target",
                       "rotateMix":0,"translateMix":0,"scaleMix":0,"shearMix":0}],
         "slots":[],"skins":[{"name":"default","attachments":{}}],"animations":{"idle":{}}}
        """;

    @Test void spine38PreservesZeroMixConstraintWithNoRecoveryOrFiltering() throws Exception {
        var codec = new SkeletonJsonCodec();
        var model = codec.decode(SAMPLE.getBytes(StandardCharsets.UTF_8), Path.of("."));
        assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
        assertEquals(List.of("dormant", "aim"), model.constraints().stream().map(Constraint::name).toList());
        var dormant = (TransformConstraint) model.constraints().get(0);
        assertEquals(11, dormant.order());
        assertEquals(model.evaluateWorld(), model.withConstraints(List.of(model.constraints().get(1))).evaluateWorld());
        assertEquals(model, new ProjectCodec().decode(new ProjectCodec().encode(model)));

        var animated = AnimationEdits.putConstraint(model, model.animations().get(0).id(), dormant.id(),
                ConstraintChannel.TRANSFORM_MIX, 1, List.of(0., 0., 0., 0., 0., 0.));
        assertEquals(animated, new ProjectCodec().decode(new ProjectCodec().encode(animated)));
        assertEquals(model.evaluateWorld(), AnimationSampler.world(animated, animated.animations().get(0).id(), .5, false));
    }

    @Test void strict42AlsoPreservesTheSameInactiveOverlap() throws Exception {
        String strict = SAMPLE.replace("3.8.55", SkeletonJsonCodec.VERSION)
                .replace("rotateMix", "mixRotate").replace("translateMix", "mixX")
                .replace("scaleMix", "mixScaleX").replace("shearMix", "mixShearY")
                .replace("\"mixX\":0", "\"mixX\":0,\"mixY\":0")
                .replace("\"mixScaleX\":0", "\"mixScaleX\":0,\"mixScaleY\":0");
        var codec = new SkeletonJsonCodec();
        var model = codec.decode(strict.getBytes(StandardCharsets.UTF_8), Path.of("."));
        assertEquals(2, model.constraints().size());
        assertTrue(codec.warnings().isEmpty(), () -> codec.warnings().toString());
    }
}
