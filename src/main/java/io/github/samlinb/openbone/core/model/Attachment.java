package io.github.samlinb.openbone.core.model;

/** Extension boundary: new kinds must add explicit validation, codec and evaluation together. */
public sealed interface Attachment permits RegionAttachment, MeshAttachment, ClippingAttachment,PathAttachment {
    EntityId id();
    String name();
    EntityId slotId();
    /** Non-textured attachment kinds have no resource source. */
    default String source(){return null;}
}
