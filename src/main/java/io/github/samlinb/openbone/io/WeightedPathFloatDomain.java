package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;

/** Whole-animation scale bounds for weighted/deformed paths with rotation-only constraints. */
final class WeightedPathFloatDomain {
    private WeightedPathFloatDomain() {}
    static boolean needed(DocumentSnapshot d) {
        return d.attachments().stream().anyMatch(a -> a instanceof PathAttachment p && (p.weighted()
                || d.animations().stream().flatMap(c->c.deforms().stream()).anyMatch(t->t.attachmentId().equals(p.id()))));
    }
    static void validate(DocumentSnapshot d) throws ProjectFormatException {
        for(var c:d.constraints()) {
            if(c instanceof IkConstraint ik && !ik.stretch() && !ik.compress()) continue;
            if(c instanceof PathConstraint p && p.rotateMode()!=PathConstraint.RotateMode.CHAIN_SCALE) continue;
            throw unsupported("constraintScale");
        }
        if(d.animations().stream().anyMatch(a->!a.constraintTracks().isEmpty())) throw unsupported("animatedConstraint");
        for(var b:d.bones()) {
            if(b.local().shearX()!=0 || b.local().shearY()!=0) throw unsupported("shear");
            for(var a:d.animations()) for(var t:a.timelines())
                if(t.boneId().equals(b.id()) && t.channel()==Channel.SHEAR) throw unsupported("animatedShear");
        }
        for(var c:d.constraints()) if(c instanceof PathConstraint p) for(var id:p.boneIds()) inverse(d,d.bone(id).parentId());
    }
    static double inverse(DocumentSnapshot d,EntityId id) throws ProjectFormatException {
        double bound=1;
        for(Bone b=id==null?null:d.bone(id);b!=null;b=b.parentId()==null?null:d.bone(b.parentId())) {
            if(b.inherit()!=Bone.Inherit.normal && b.inherit()!=Bone.Inherit.onlyTranslation) throw unsupported("inheritance");
            double min=Math.min(Math.abs(b.local().scaleX()),Math.abs(b.local().scaleY()));
            for(var clip:d.animations()) for(var t:clip.timelines()) if(t.boneId().equals(b.id()) && t.channel()==Channel.SCALE)
                for(int axis=0;axis<2;axis++) for(int i=0;i<t.keys().size();i++) {
                    double lo=t.keys().get(i).values().get(axis),hi=lo;
                    if(i+1<t.keys().size()) {
                        double end=t.keys().get(i+1).values().get(axis); lo=Math.min(lo,end);hi=Math.max(hi,end);
                        var curve=t.keys().get(i).curves().get(axis);
                        if(curve.kind()==Curve.Kind.BEZIER) {
                            double start=t.keys().get(i).values().get(axis),delta=end-start;
                            lo=Math.min(lo,Math.min(start+delta*curve.y1(),start+delta*curve.y2()));
                            hi=Math.max(hi,Math.max(start+delta*curve.y1(),start+delta*curve.y2()));
                        }
                    }
                    double factor=lo<=0 && hi>=0?0:Math.min(Math.abs(lo),Math.abs(hi));
                    min=Math.min(min,factor*Math.abs(axis==0?b.local().scaleX():b.local().scaleY()));
                }
            // Singular and near-singular ancestry needs a different applied-transform proof.
            if(min<.001) throw unsupported("zeroScale");
            bound=RuntimeRange.number(bound*Math.max(1,2/min)*1.01,"weighted path inverse bound");
            if(b.inherit()==Bone.Inherit.onlyTranslation) break;
        }
        return bound;
    }
    private static ProjectFormatException unsupported(String detail) {
        return ProjectCodec.bad("path.float-certificate",io.github.samlinb.openbone.i18n.M.m("pathExport.domain",io.github.samlinb.openbone.i18n.M.m("pathExport."+detail)));
    }
}
