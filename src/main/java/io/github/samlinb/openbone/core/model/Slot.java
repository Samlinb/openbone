package io.github.samlinb.openbone.core.model;

import java.util.Objects;

/**
 * List order is static draw order. Null activeAttachmentId means empty slot. V1
 * normal blending.
 */
public record Slot(EntityId id, String name, EntityId boneId, EntityId activeAttachmentId, Blend blend, Rgba color,
        String setupAttachmentName) {
    public Slot(EntityId id,String name,EntityId boneId,EntityId activeAttachmentId,Blend blend,Rgba color) {
        this(id,name,boneId,activeAttachmentId,blend,color,null);
    }
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
        if(setupAttachmentName!=null && (setupAttachmentName.isEmpty() || setupAttachmentName.length()>4096))
            throw new IllegalArgumentException("Invalid Setup attachment name");
    }

    public Slot withActiveAttachment(EntityId value) {
        return new Slot(id, name, boneId, value, blend, color);
    }
    public Slot withColor(Rgba value) { return new Slot(id,name,boneId,activeAttachmentId,blend,value,setupAttachmentName); }
    public Slot withSetupAttachmentName(String value) { return new Slot(id,name,boneId,activeAttachmentId,blend,color,value); }
}
