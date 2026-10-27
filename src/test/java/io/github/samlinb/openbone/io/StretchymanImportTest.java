package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StretchymanImportTest {
    static final Path SOURCE=Path.of("src/test/resources/spine43/stretchyman/stretchyman-pro.json");
    @TempDir Path temp;

    static SpineImportPolicy.Approval approval() throws Exception {
        var risk=assertThrows(SpineVersionRiskException.class,()->SpineImportPolicy.select(Files.readAllBytes(SOURCE),SpineImportPolicy.Format.JSON,null));
        return risk.selection().approval();
    }

    @Test void officialExampleRetainsWeightedPathsDeformsAndPortableRoundtrip() throws Exception {
        byte[] original=Files.readAllBytes(SOURCE);
        var document=new ProjectDocument(DocumentSnapshot.EMPTY);
        document.openResolved(SOURCE,approval());
        var model=document.history().state().model();
        assertEquals(List.of("idle","sneak"),model.animations().stream().map(AnimationClip::name).toList());
        var paths=model.attachments().stream().filter(a->a instanceof PathAttachment).map(a->(PathAttachment)a).toList();
        assertEquals(4,paths.size());
        assertTrue(paths.stream().allMatch(PathAttachment::weighted));
        assertEquals(10,model.constraints().size());
        assertEquals(2,model.animations().get(1).deforms().size());
        assertTrue(document.importWarnings().stream().noneMatch(w->w.contains("filtered")||w.contains("unavailable")),document.importWarnings().toString());
        var clip=model.animations().get(1);
        for(double time:List.of(0.,.3,.6,1.3,1.6,clip.duration())) {
            var pose=AnimationSampler.world(model,clip.id(),time,false);
            assertEquals(4,pose.paths().size());
            assertFalse(pose.meshes().isEmpty());
        }
        Path saved=temp.resolve("stretchyman.openbone.json");
        document.savePortable(saved);
        var codec=new ProjectCodec();
        assertEquals(13,codec.parseTree(Files.readAllBytes(saved)).path("schemaVersion").asInt());
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(saved);
        assertEquals(model.attachments().stream().filter(a->a instanceof PathAttachment).toList(),
                reopened.history().state().model().attachments().stream().filter(a->a instanceof PathAttachment).toList());
        assertEquals(model.animations(),reopened.history().state().model().animations());
        assertArrayEquals(original,Files.readAllBytes(SOURCE));
        assertThrows(ProjectFormatException.class,()->RuntimeRange.validate(model));
    }

    @Test void weightedPathSkinsEachDeformInItsOwnBoneSpace() {
        var root=EntityId.create();var child=EntityId.create();
        var rows=Collections.nCopies(6,List.of(new Influence(root,new Vec2(2,3),.25),new Influence(child,new Vec2(4,5),.75)));
        var path=new PathAttachment(EntityId.create(),"path",EntityId.create(),Collections.nCopies(6,new Vec2(0,0)),false,true,List.of(10.,20.),rows);
        var world=Map.of(root,new Transform(10,0,0,1,1).matrix(),child,new Transform(0,20,90,1,1).matrix());
        var deform=new ArrayList<Double>();for(int i=0;i<6;i++)deform.addAll(List.of(1.,2.,3.,4.));
        // .25*(10+2+1,3+2) + .75*(-5-4,20+4+3)
        for(var point:path.worldVertices(world,Transform.IDENTITY.matrix(),deform)) {
            assertEquals(-3.5,point.x(),1e-9);assertEquals(21.5,point.y(),1e-9);
        }
        assertThrows(IllegalArgumentException.class,()->path.withVertices(path.vertices()));
        assertEquals(rows,path.withName("renamed").withLengths(List.of(11.,22.)).weights());
    }

    @Test void unclampedTransformMaxDoesNotChangeTheMapping() throws Exception {
        var codec=new ProjectCodec();var root=(com.fasterxml.jackson.databind.node.ObjectNode)codec.parseTree(Files.readAllBytes(SOURCE));
        var normalized=SpineVersionJson.normalize(root.deepCopy(),SpineVersion.V43);
        for(var item:root.path("constraints"))if(item.path("type").asText().equals("transform"))
            for(var property:item.path("properties"))for(var to:property.path("to"))((com.fasterxml.jackson.databind.node.ObjectNode)to).put("max",-999);
        assertEquals(normalized,SpineVersionJson.normalize(root.deepCopy(),SpineVersion.V43));
        for(var item:root.path("constraints"))if(item.path("type").asText().equals("transform"))((com.fasterxml.jackson.databind.node.ObjectNode)item).put("clamp",true);
        assertThrows(ProjectFormatException.class,()->SpineVersionJson.normalize(root,SpineVersion.V43));
    }

    @Test void pathDeformsAffectTheConstraintBeforeFollowingBonesAreSolved() {
        var root=EntityId.create();var follower=EntityId.create();var slot=EntityId.create();var attachment=EntityId.create();
        var points=List.of(new Vec2(-10,0),new Vec2(0,0),new Vec2(100./3,0),new Vec2(200./3,0),new Vec2(100,0),new Vec2(110,0));
        var weights=points.stream().map(point->List.of(new Influence(root,point,1))).toList();
        var path=new PathAttachment(attachment,"path",slot,Collections.nCopies(6,new Vec2(0,0)),false,true,List.of(100.,0.),weights);
        var clip=EntityId.create();var end=new ArrayList<Double>();for(int i=0;i<6;i++)end.addAll(List.of(0.,10.));
        var keys=List.of(new Keyframe(EntityId.create(),0,Collections.nCopies(12,0.),Collections.nCopies(12,Curve.LINEAR)),
                new Keyframe(EntityId.create(),1,end,Collections.nCopies(12,Curve.LINEAR)));
        var model=new DocumentSnapshot(List.of(new Bone(root,"root",null,Transform.IDENTITY,0),new Bone(follower,"follow",root,Transform.IDENTITY,10)),
                List.of(new Slot(slot,"slot",root,attachment)),List.of(path),
                List.of(new AnimationClip(clip,"bend",List.of(),List.of(new DeformTimeline(attachment,keys)))),
                List.of(new PathConstraint(EntityId.create(),"follow-path",0,true,List.of(follower),slot,PathConstraint.PositionMode.PERCENT,
                        PathConstraint.SpacingMode.FIXED,PathConstraint.RotateMode.TANGENT,.25,0,0,1,1,1)));
        var pose=AnimationSampler.world(model,clip,.5,false);
        assertEquals(25,pose.bones().get(follower).origin().x(),1e-9);
        assertEquals(5,pose.bones().get(follower).origin().y(),1e-9);
        assertEquals(5,pose.paths().get(attachment).get(1).y(),1e-9);
    }

    @Test void flatBezierExpansionPreservesOvershootOnBothAxes() throws Exception {
        String text="""
            {"skeleton":{"spine":"4.2.22"},"bones":[{"name":"root"}],"slots":[],"skins":[{"name":"default","attachments":{}}],
             "animations":{"a":{"bones":{"root":{"translate":[
             {"curve":[0.2,2,0.7,-1,0.4,5,0.8,-3]},{"time":1,"y":4}]}}}}}
            """;
        var codec=new SkeletonJsonCodec();var model=codec.decode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),temp);
        var track=model.animations().get(0).timelines().get(0);
        assertTrue(track.keys().size()>10);
        for(int sample=0;sample<=100;sample++) {
            double time=sample/100.;var actual=track.sample(time).orElseThrow();
            assertEquals(chord(time,0,.2,2,.7,-1,0),actual.get(0),1e-12);
            assertEquals(chord(time,0,.4,5,.8,-3,4),actual.get(1),1e-12);
        }
        assertTrue(codec.warnings().contains(io.github.samlinb.openbone.i18n.M.m("spineImport.flatBezier")));
    }

    private static double chord(double time,double start,double x1,double y1,double x2,double y2,double end) {
        double px=0,py=start;
        for(int i=1;i<=10;i++) {
            double u=i/10.,v=1-u;
            double x=i==10?1:3*v*v*u*x1+3*v*u*u*x2+u*u*u;
            double y=i==10?end:v*v*v*start+3*v*v*u*y1+3*v*u*u*y2+u*u*u*end;
            if(time<=x)return py+(y-py)*(time-px)/(x-px);
            px=x;py=y;
        }
        return end;
    }

    @Test void malformedWeightedPathPreservesTheDocumentRevisionAndRedo() throws Exception {
        var document=new ProjectDocument(DocumentSnapshot.EMPTY);document.openResolved(SOURCE,approval());
        var model=document.history().state().model();
        document.history().execute("change root",Commands.model(d->ModelEdits.editBone(d,d.bones().get(0).id(),b->b.withName("renamed-root"))));
        document.history().undo();String revision=document.history().revision();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)new ProjectCodec().parseTree(Files.readAllBytes(SOURCE));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("skeleton")).put("spine","4.3.26");
        var vertices=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("skins").get(0).path("attachments").path("back-leg-path").path("back-leg-path").path("vertices");
        vertices.set(0,com.fasterxml.jackson.databind.node.DoubleNode.valueOf(1.5));
        Path invalid=temp.resolve("invalid.json");Files.writeString(invalid,tree.toString());
        assertThrows(ProjectFormatException.class,()->document.openResolved(invalid));
        assertEquals(model,document.history().state().model());assertEquals(revision,document.history().revision());
        assertEquals(1,document.history().redoCount());
    }
}
