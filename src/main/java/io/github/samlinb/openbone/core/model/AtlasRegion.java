package io.github.samlinb.openbone.core.model;

import io.github.samlinb.openbone.core.math.Vec2;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Logical packed width/height are before quarter-turn packing, offset is
 * bottom-left. rotated means the packed dimensions are swapped (90 or 270).
 */
public record AtlasRegion(int pageWidth, int pageHeight, int x, int y, int width, int height, int originalWidth,
        int originalHeight, int offsetX, int offsetY, boolean rotated, boolean premultiplied, int degrees) {
    /** Existing projects and programmatically created regions use straight alpha. */
    public AtlasRegion(int pageWidth, int pageHeight, int x, int y, int width, int height, int originalWidth,
            int originalHeight, int offsetX, int offsetY, boolean rotated) {
        this(pageWidth, pageHeight, x, y, width, height, originalWidth, originalHeight, offsetX, offsetY, rotated, false, rotated ? 90 : 0);
    }
    public AtlasRegion(int pageWidth, int pageHeight, int x, int y, int width, int height, int originalWidth,
            int originalHeight, int offsetX, int offsetY, boolean rotated, boolean premultiplied) {
        this(pageWidth, pageHeight, x, y, width, height, originalWidth, originalHeight, offsetX, offsetY, rotated, premultiplied, rotated ? 90 : 0);
    }
    public AtlasRegion {
        // Widen before adding: untrusted int offsets/sizes may overflow even for a tiny
        // page.
        // Trim containment uses logical dimensions; only page containment swaps axes at
        // 90 degrees.
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270
                || rotated != (degrees == 90 || degrees == 270)
                || pageWidth < 1 || pageHeight < 1 || pageWidth > 8192 || pageHeight > 8192 || width < 1 || height < 1
                || originalWidth < 1 || originalHeight < 1 || originalWidth > 8192 || originalHeight > 8192 || x < 0
                || y < 0 || offsetX < 0 || offsetY < 0 || (long) offsetX + width > originalWidth
                || (long) offsetY + height > originalHeight || (long) x + (rotated ? height : width) > pageWidth
                || (long) y + (rotated ? width : height) > pageHeight)
            throw new IllegalArgumentException(m("atlasregion.0001"));
    }

    /** Image-space UV input; meshes may sample the restored transparent margins. */
    public Vec2 uv(double u, double v) {
        double px = u * originalWidth - offsetX, py = v * originalHeight - (originalHeight - offsetY - height);
        return switch (degrees) {
            case 90 -> new Vec2((x + py) / pageWidth, (y + width - px) / pageHeight);
            case 180 -> new Vec2((x + width - px) / pageWidth, (y + height - py) / pageHeight);
            case 270 -> new Vec2((x + height - py) / pageWidth, (y + px) / pageHeight);
            default -> new Vec2((x + px) / pageWidth, (y + py) / pageHeight);
        };
    }

    public Vec2 packedUv(double u, double v) {
        return switch (degrees) {
            case 90 -> new Vec2((x + v * height) / pageWidth, (y + (1 - u) * width) / pageHeight);
            case 180 -> new Vec2((x + (1-u)*width) / pageWidth, (y + (1-v)*height) / pageHeight);
            case 270 -> new Vec2((x + (1-v)*height) / pageWidth, (y + u*width) / pageHeight);
            default -> new Vec2((x + u * width) / pageWidth, (y + v * height) / pageHeight);
        };
    }
}
