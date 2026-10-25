package io.github.samlinb.openbone.core.model;

import java.util.Objects;
import io.github.samlinb.openbone.core.math.*;

/**
 * Untrimmed region dimensions in local model units; source is a portable
 * resource reference, not loaded here.
 */
public record RegionAttachment(EntityId id, String name, EntityId slotId, String source,
        Transform local, double width, double height) implements Attachment {
    public RegionAttachment {
        Objects.requireNonNull(id, "attachment.id");
        Names.require(name);
        Objects.requireNonNull(slotId, "attachment.slotId");
        if (source == null || source.isBlank() || source.length() > 4096)
            throw new IllegalArgumentException("Region source must contain 1–4096 characters");
        Objects.requireNonNull(local, "attachment.local");
        Finite.require(width, "width");
        Finite.require(height, "height");
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Region dimensions must be positive");
    }
}
