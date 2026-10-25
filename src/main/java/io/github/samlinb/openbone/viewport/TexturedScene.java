package io.github.samlinb.openbone.viewport;

import java.util.*;
import java.util.function.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import io.github.samlinb.openbone.core.math.Vec2;
import io.github.samlinb.openbone.core.model.Slot;

/**
 * Shared textured draw path for the editor and render verification. Caller owns
 * GL/EDT
 * and the batch projection/lifetime. PNG texels stay straight-alpha sRGB bytes.
 */
public final class TexturedScene {
    private TexturedScene() {
    }

    public static void draw(PolygonSpriteBatch batch, List<VisibleGeometry.Draw> draws,
            Function<String, Texture> texture, UnaryOperator<Vec2> screen) {
        batch.enableBlending();
        batch.begin();
        try {
            for (var draw : draws) {
                int destination = draw.blend() == Slot.Blend.ADDITIVE ? GL20.GL_ONE : GL20.GL_ONE_MINUS_SRC_ALPHA;
                batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, destination, GL20.GL_ONE, destination);
                var image = texture.apply(draw.source());
                var geometry = draw.geometry();
                for (int start = 0; start < geometry.triangles().size(); start += 1800) {
                    int count = Math.min(1800, geometry.triangles().size() - start);
                    float[] data = new float[count * 5];
                    short[] indices = new short[count];
                    for (int i = 0; i < count; i++) {
                        int vertex = geometry.triangles().get(start + i);
                        var p = screen.apply(geometry.vertices().get(vertex));
                        int k = i * 5;
                        data[k] = bounded(p.x());
                        data[k + 1] = bounded(p.y());
                        data[k + 2] = Color.WHITE_FLOAT_BITS;
                        data[k + 3] = (float) geometry.uvs().get(vertex).x();
                        data[k + 4] = (float) geometry.uvs().get(vertex).y();
                        indices[i] = (short) i;
                    }
                    batch.draw(image, data, 0, data.length, indices, 0, count);
                }
            }
        } finally {
            batch.end();
        }
    }

    private static float bounded(double value) {
        return (float) Math.max(-1e7, Math.min(1e7, value));
    }
}
