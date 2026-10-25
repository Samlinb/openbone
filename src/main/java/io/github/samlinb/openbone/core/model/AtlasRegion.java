package io.github.samlinb.openbone.core.model;

import io.github.samlinb.openbone.core.math.Vec2;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Logical packed width/height are before 90 degree packing, offset is
 * bottom-left.
 */
public record AtlasRegion(int pageWidth, int pageHeight, int x, int y, int width, int height, int originalWidth,
        int originalHeight, int offsetX, int offsetY, boolean rotated) {
    public AtlasRegion {
        // Widen before adding: untrusted int offsets/sizes may overflow even for a tiny
        // page.
        // Trim containment uses logical dimensions; only page containment swaps axes at
        // 90 degrees.
        if (pageWidth < 1 || pageHeight < 1 || pageWidth > 8192 || pageHeight > 8192 || width < 1 || height < 1
                || originalWidth < 1 || originalHeight < 1 || originalWidth > 8192 || originalHeight > 8192 || x < 0
                || y < 0 || offsetX < 0 || offsetY < 0 || (long) offsetX + width > originalWidth
                || (long) offsetY + height > originalHeight || (long) x + (rotated ? height : width) > pageWidth
                || (long) y + (rotated ? width : height) > pageHeight)
            throw new IllegalArgumentException(m("atlasregion.0001"));
    }

    /** Image-space UV input; meshes may sample the restored transparent margins. */
    public Vec2 uv(double u, double v) {
        double px = u * originalWidth - offsetX, py = v * originalHeight - (originalHeight - offsetY - height);
        return rotated ? new Vec2((x + py) / pageWidth, (y + width - px) / pageHeight)
                : new Vec2((x + px) / pageWidth, (y + py) / pageHeight);
    }

    public Vec2 packedUv(double u, double v) {
        return rotated ? new Vec2((x + v * height) / pageWidth, (y + (1 - u) * width) / pageHeight)
                : new Vec2((x + u * width) / pageWidth, (y + v * height) / pageHeight);
    }
}
