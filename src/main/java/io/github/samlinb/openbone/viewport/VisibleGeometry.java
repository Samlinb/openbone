package io.github.samlinb.openbone.viewport;

import java.util.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;

/**
 * Single evaluated, clipped textured geometry for both GL and CPU picking.
 * State is local
 * to this evaluation; end slots are processed after drawing, never carried to a
 * new frame.
 */
public final class VisibleGeometry {
    private VisibleGeometry() {
    }

    public record Draw(EntityId attachmentId, EntityId slotId, String source, PolygonClipper.Geometry geometry,
            Slot.Blend blend, Rgba color) {
        public Draw(EntityId attachmentId, EntityId slotId, String source, PolygonClipper.Geometry geometry, Slot.Blend blend) {
            this(attachmentId,slotId,source,geometry,blend,Rgba.WHITE);
        }
    }

    public static List<Draw> evaluate(DocumentSnapshot d, WorldPose pose) {
        Map<EntityId, Attachment> attachments = new HashMap<>();
        d.attachments().forEach(a -> attachments.put(a.id(), a));
        List<Draw> result = new ArrayList<>();
        ClippingAttachment clip = null;
        WorldPose.PolygonWorld polygon = null;
        for (var slot : pose.drawSlots().isEmpty() ? d.resolvedSlots(null, 0, false) : pose.drawSlots()) {
            var a = attachments.get(slot.activeAttachmentId());
            if (a instanceof ClippingAttachment c) {
                if (clip == null) {
                    clip = c;
                    polygon = pose.clips().get(c.id());
                }
                continue;
            }
            if (a != null && !(a instanceof PathAttachment)) {
                PolygonClipper.Geometry geometry;
                if (a instanceof MeshAttachment m) {
                    var world = pose.meshes().get(m.id());
                    geometry = new PolygonClipper.Geometry(world.vertices(), world.uvs(), world.triangles());
                } else {
                    var world = pose.regions().get(a.id());
                    geometry = new PolygonClipper.Geometry(world.vertices(),
                            List.of(new Vec2(0, 1), new Vec2(0, 0), new Vec2(1, 0), new Vec2(1, 1)),
                            List.of(0, 1, 2, 2, 3, 0));
                }
                var region = d.resources().atlas().get(a.id());
                if (region != null) {
                    var mapped = geometry.uvs().stream()
                            .map(uv -> a instanceof MeshAttachment ? region.uv(uv.x(), uv.y())
                                    : region.packedUv(uv.x(), uv.y()))
                            .toList();
                    geometry = new PolygonClipper.Geometry(geometry.vertices(), mapped, geometry.triangles());
                }
                if (clip != null)
                    geometry = PolygonClipper.clip(geometry, polygon.vertices(), polygon.triangles());
                result.add(new Draw(a.id(), slot.id(), a.source(), geometry, slot.blend(),slot.color()));
            }
            if (clip != null && slot.id().equals(clip.endSlotId())) {
                clip = null;
                polygon = null;
            }
        }
        return List.copyOf(result);
    }
}
