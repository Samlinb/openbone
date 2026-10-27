package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.animation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WeightedPathExportTest {
    @TempDir Path temp;
    @Test void weightedPathAndDeformOffsetsSurviveJsonAndBinary() throws Exception {
        var root=EntityId.create();var slot=EntityId.create();var id=EntityId.create();var clip=EntityId.create();
        var points=List.of(new Vec2(-10,0),new Vec2(0,0),new Vec2(10,0),new Vec2(20,0),new Vec2(30,0),new Vec2(40,0));
        var weights=points.stream().map(p->List.of(new Influence(root,p,1))).toList();
        var path=new PathAttachment(id,"path",slot,Collections.nCopies(6,new Vec2(0,0)),false,true,List.of(30.,0.),weights);
        var keys=List.of(new Keyframe(EntityId.create(),0,Collections.nCopies(12,0.),Collections.nCopies(12,Curve.LINEAR)),
                new Keyframe(EntityId.create(),1,Collections.nCopies(12,5.),Collections.nCopies(12,Curve.LINEAR)));
        var d=new DocumentSnapshot(List.of(new Bone(root,"root",null,Transform.IDENTITY,0)),List.of(new Slot(slot,"slot",root,id)),
                List.of(path),List.of(new AnimationClip(clip,"bend",List.of(),List.of(new DeformTimeline(id,keys)))));
        for(String version:List.of("4.0.64","4.1.24","4.2.22","4.3.26"))for(var format:List.of(ExportPackage.Format.JSON,ExportPackage.Format.BINARY)) {
            Path exported=new ExportPackage().publish(temp.resolve(version+format),d,temp,version,format);
            var imported=new SkeletonJsonCodec().read(exported);var p=(PathAttachment)imported.attachments().get(0);
            assertTrue(p.weighted());assertEquals(1,imported.animations().get(0).deforms().size());
            assertEquals(AnimationSampler.world(d,clip,.5,false).paths().get(id),
                    AnimationSampler.world(imported,imported.animations().get(0).id(),.5,false).paths().get(p.id()));
        }
    }
    @Test void singularPathAncestryFailsBeforeCreatingAnExportDirectory() throws Exception {
        var root=EntityId.create();var follower=EntityId.create();var slot=EntityId.create();var id=EntityId.create();
        var points=List.of(new Vec2(-10,0),new Vec2(0,0),new Vec2(10,0),new Vec2(20,0),new Vec2(30,0),new Vec2(40,0));
        var path=new PathAttachment(id,"path",slot,Collections.nCopies(6,new Vec2(0,0)),false,true,List.of(30.,0.),
                points.stream().map(p->List.of(new Influence(root,p,1))).toList());
        var constraint=new PathConstraint(EntityId.create(),"follow",0,true,List.of(follower),slot,
                PathConstraint.PositionMode.PERCENT,PathConstraint.SpacingMode.FIXED,PathConstraint.RotateMode.TANGENT,0,0,0,1,1,1);
        var d=new DocumentSnapshot(List.of(new Bone(root,"root",null,new Transform(0,0,0,0,1),0),
                new Bone(follower,"follow",root,Transform.IDENTITY,5)),List.of(new Slot(slot,"slot",root,id)),List.of(path),List.of(),List.of(constraint));
        Path directory=temp.resolve("rejected");
        assertThrows(ProjectFormatException.class,()->new ExportPackage().publish(directory,d,temp,"4.2.22"));
        assertFalse(Files.exists(directory));
    }
}
