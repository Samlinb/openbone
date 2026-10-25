package io.github.samlinb.openbone.core.model;

import java.util.Objects;

/**
 * List order is static draw order. Null activeAttachmentId means empty slot. V1
 * normal blending.
 */
public record Slot(EntityId id, String name, EntityId boneId, EntityId activeAttachmentId, Blend blend, Rgba color) {
    public Slot(EntityId id, String name, EntityId boneId, EntityId activeAttachmentId, Blend blend) {
        this(id, name, boneId, activeAttachmentId, blend, Rgba.WHITE);
    }
    public enum Blend {
        NORMAL, ADDITIVE
    }

    public Slot(EntityId id, String name, EntityId boneId, EntityId activeAttachmentId) {
        this(id, name, boneId, activeAttachmentId, Blend.NORMAL);
    }

    public Slot {
        Objects.requireNonNull(id, "slot.id");
        Objects.requireNonNull(blend);
        Objects.requireNonNull(color);
        Names.require(name);
        Objects.requireNonNull(boneId, "slot.boneId");
    }

    public Slot withActiveAttachment(EntityId value) {
        return new Slot(id, name, boneId, value, blend, color);
    }
    public Slot withColor(Rgba value) { return new Slot(id,name,boneId,activeAttachmentId,blend,value); }
}
