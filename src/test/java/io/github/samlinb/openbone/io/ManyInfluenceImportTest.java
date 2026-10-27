package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ManyInfluenceImportTest {
    @TempDir Path temp;

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="openbone.verifyNinja",matches="true")
    void actualNinjaSamplesImportWithoutTrimmingWeightsOrHullVertices() throws Exception {
        Path root=Path.of(System.getProperty("openbone.ninjaDirectory","E:/resource/ninja3-assets-047097ea/骨骼动画分类"));
        for(String relative:List.of("02_Boss/ZhenYu__6ad15f3add/ZhenYu.skel", "03_敌人与怪物/Botany__712ce8e2da/Botany.skel")) {
            Path source=root.resolve(relative);
            var model=new SkeletonJsonCodec().read(source);
            assertFalse(model.animations().isEmpty());
            if(relative.contains("ZhenYu"))assertTrue(model.attachments().stream().filter(a->a instanceof MeshAttachment)
                    .map(a->(MeshAttachment)a).flatMap(a->a.weights().stream()).anyMatch(row->row.size()>4));
            var codec=new ProjectCodec();
            assertEquals(model,codec.decode(codec.encode(model)));
            for(var clip:model.animations())assertDoesNotThrow(()->io.github.samlinb.openbone.core.animation.AnimationSampler.world(model,clip.id(),clip.duration()*.5,false));
        }
    }

    private DocumentSnapshot mesh(int count) {
        var bones = new ArrayList<Bone>();
        for (int i=1;i<=count;i++) bones.add(bone(i,"bone"+i,i==1?null:id(1),new Transform(i,0,0,1,1)));
        var points=List.of(new Vec2(0,0),new Vec2(4,0),new Vec2(0,4));
        var uv=List.of(new Vec2(0,0),new Vec2(1,0),new Vec2(0,1));
        var rows=new ArrayList<List<Influence>>();
        for(var point:points) {
            var row=new ArrayList<Influence>();
            for(int i=1;i<=count;i++)row.add(new Influence(id(i),point,1.0/count));
            rows.add(row);
        }
        var mesh=new MeshAttachment(id(100),"mesh",id(90),temp.resolve("image.png").toString(),points,uv,3,List.of(0,1,2),rows);
        return new DocumentSnapshot(bones,List.of(new Slot(id(90),"slot",id(1),mesh.id())),List.of(mesh));
    }

    @Test void eightInfluencesArePreservedThroughJsonBinaryPortableSaveAndSkinning() throws Exception {
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"PNG",temp.resolve("image.png").toFile());
        var source=mesh(8);
        var own=new ProjectCodec();
        assertEquals(source,own.decode(own.encode(source)));
        var json=new SkeletonJsonCodec();
        var imported=json.decode(json.encode(source,Map.of(id(100),"image"),""),temp);
        assertMany(source,imported);
        byte[] binary=SpineBinaryWriter.encode(source,Map.of(id(100),"image"),"","4.0.64");
        Path skel=temp.resolve("actor.skel");Files.write(skel,binary);
        var binaryImported=new SkeletonJsonCodec().read(skel);
        assertMany(source,binaryImported);
        var doc=new ProjectDocument(imported);
        Path project=temp.resolve("portable/actor.openbone.json");Files.createDirectories(project.getParent());
        doc.savePortable(project);
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(project);
        assertMany(source,reopened.history().state().model());
    }

    private void assertMany(DocumentSnapshot source,DocumentSnapshot actual) {
        var mesh=(MeshAttachment)actual.attachments().get(0);
        assertEquals(8,mesh.weights().get(0).size());
        for(var row:mesh.weights()) for(int i=0;i<8;i++) {
            assertEquals("bone"+(i+1),actual.bone(row.get(i).boneId()).name());
            assertEquals(.125,row.get(i).weight());
        }
        var expected=source.evaluateWorld().meshes().values().iterator().next().vertices();
        var world=actual.evaluateWorld().meshes().values().iterator().next().vertices();
        for(int i=0;i<world.size();i++) {
            assertEquals(expected.get(i).x(),world.get(i).x(),1e-10);
            assertEquals(expected.get(i).y(),world.get(i).y(),1e-10);
        }
    }

    @Test void largeDeclaredCountsFailWithoutOverflowOrPartialImport() throws Exception {
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"PNG",temp.resolve("image.png").toFile());
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new SkeletonJsonCodec().encode(mesh(8),Map.of(id(100),"image"),""));
        var vertices=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("skins").get(0).path("attachments").path("slot").path("mesh").path("vertices");
        vertices.set(0,com.fasterxml.jackson.databind.node.IntNode.valueOf(Integer.MAX_VALUE));
        assertThrows(ProjectFormatException.class,()->new SkeletonJsonCodec().decode(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(tree),temp));
        assertThrows(IllegalArgumentException.class,()->Influence.normalize(List.of()));
        assertThrows(IllegalArgumentException.class,()->Influence.validate(List.of()));
        var duplicate=List.of(new Influence(id(1),new Vec2(0,0),.5),new Influence(id(1),new Vec2(0,0),.5));
        assertThrows(IllegalArgumentException.class,()->Influence.validate(duplicate));
    }

    @Test void suppliedTriangulationPreservesStraightBoundaryVertexButStillRejectsOverlap() {
        var points=List.of(new Vec2(0,0),new Vec2(2,0),new Vec2(4,0),new Vec2(4,4),new Vec2(0,4));
        var triangles=List.of(0,1,4,1,3,4,1,2,3);
        assertDoesNotThrow(()->MeshTopology.validate(points,5,triangles));
        var attachment=new MeshAttachment(id(100),"mesh",id(90),"image.png",points,
                List.of(new Vec2(0,0),new Vec2(.5,0),new Vec2(1,0),new Vec2(1,1),new Vec2(0,1)),5,triangles);
        assertEquals(points,attachment.vertices());
        assertThrows(IllegalArgumentException.class,()->MeshTopology.triangulate(points,5));
        assertThrows(IllegalArgumentException.class,()->MeshTopology.validate(points,5,List.of(0,2,4,2,3,4,0,1,4)));
    }

    @Test void runtimeTriangleIslandsAndTintRemainLosslessAndTopologyEditingIsExplicit() throws Exception {
        var vertices=List.of(new Vec2(0,0),new Vec2(2,0),new Vec2(0,2),new Vec2(4,0),new Vec2(6,0),new Vec2(4,2));
        var uv=List.of(new Vec2(0,0),new Vec2(.5,0),new Vec2(0,.5),new Vec2(.5,0),new Vec2(1,0),new Vec2(.5,.5));
        var triangles=List.of(0,1,2,3,4,5);
        var tint=Rgba.parse("aabbcc80");
        var mesh=new MeshAttachment(id(100),"islands",id(90),"image.png",vertices,uv,6,triangles,List.of(),true,tint);
        var model=new DocumentSnapshot(List.of(bone(1,"root",null,Transform.IDENTITY)),List.of(new Slot(id(90),"slot",id(1),mesh.id())),List.of(mesh));
        var codec=new ProjectCodec();
        var encoded=codec.encode(model);
        assertEquals(13,new com.fasterxml.jackson.databind.ObjectMapper().readTree(encoded).path("schemaVersion").asInt());
        assertEquals(model,codec.decode(encoded));
        assertEquals(triangles,model.evaluateWorld().meshes().get(mesh.id()).triangles());
        assertThrows(IllegalArgumentException.class,()->MeshEdits.geometry(model,mesh.id(),vertices,uv,6,List.of(0,2,1,3,4,5)));
        var moved=MeshEdits.vertex(model,mesh.id(),0,new Vec2(-1,0),uv.get(0));
        var after=(MeshAttachment)moved.attachments().get(0);
        assertTrue(after.runtimeTopology());assertEquals(tint,after.color());assertEquals(triangles,after.triangles());
        assertThrows(IllegalArgumentException.class,()->new MeshAttachment(id(100),"bad",id(90),"image.png",vertices,uv,6,List.of(0,1,6),List.of(),true,tint));
        assertDoesNotThrow(()->RuntimeRange.validate(model));
    }

    @Test void missingSetupAttachmentNameIsRetainedAndVersionlessJsonNeedsApproval() throws Exception {
        Path source=temp.resolve("actor.json");
        String json="""
            {"skeleton":{"spine":"4.0.64"},"bones":[{"name":"root"}],
             "slots":[{"name":"slot","bone":"root","attachment":"not-in-this-skin"}],
             "skins":[{"name":"default","attachments":{}}],"animations":{"idle":{}}}
            """;
        Files.writeString(source,json);
        var model=new SkeletonJsonCodec().read(source);
        assertEquals("not-in-this-skin",model.slots().get(0).setupAttachmentName());
        assertNull(model.resolvedSlots(null,0,false).get(0).activeAttachmentId());
        var codec=new ProjectCodec();assertEquals(model,codec.decode(codec.encode(model)));
        var exported=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new SkeletonJsonCodec().encode(model,Map.of(),""));
        assertEquals("not-in-this-skin",exported.path("slots").get(0).path("attachment").asText());
        Files.writeString(source,json.replace("\"spine\":\"4.0.64\"",""));
        var document=new ProjectDocument(DocumentSnapshot.EMPTY);
        var risk=assertThrows(SpineVersionRiskException.class,()->document.openResolved(source));
        assertEquals("unspecified",risk.selection().sourceVersion());
        assertEquals(SpineImportPolicy.Parser.JSON_38,risk.selection().parser());
        assertEquals(DocumentSnapshot.EMPTY,document.history().state().model());
    }

    @Test void weightedClippingAndDeformRoundtripAndEvaluateWithOriginalInfluences() throws Exception {
        var vertices=List.of(new Vec2(0,0),new Vec2(4,0),new Vec2(4,4),new Vec2(0,4));
        var weights=vertices.stream().map(p->List.of(new Influence(id(1),p,.5),new Influence(id(2),p,.5))).toList();
        var clip=new ClippingAttachment(id(100),"clip",id(90),vertices,id(91),ClippingAttachment.DEFAULT_COLOR,weights,true);
        var model=new DocumentSnapshot(List.of(bone(1,"root",null,Transform.IDENTITY),bone(2,"other",id(1),new Transform(2,0,0,1,1))),
                List.of(new Slot(id(90),"clip-slot",id(1),clip.id()),new Slot(id(91),"end",id(1),null)),List.of(clip));
        assertEquals(new Vec2(1,0),model.evaluateWorld().clips().get(clip.id()).vertices().get(0));
        var animated=DeformEdits.put(model,model.animations().get(0).id(),clip.id(),0,Collections.nCopies(clip.deformSize(),2.));
        var posed=io.github.samlinb.openbone.core.animation.AnimationSampler.world(animated,animated.animations().get(0).id(),0,false);
        assertEquals(new Vec2(3,2),posed.clips().get(clip.id()).vertices().get(0));
        var codec=new ProjectCodec();assertEquals(animated,codec.decode(codec.encode(animated)));
        Path source=temp.resolve("weighted-clip.json");
        Files.write(source,new SkeletonJsonCodec().encode(animated,Map.of(),"","4.0.64"));
        var imported=new SkeletonJsonCodec().read(source);
        var c=(ClippingAttachment)imported.attachments().get(0);
        assertTrue(c.weighted());assertEquals(2,c.weights().get(0).size());assertEquals(16,c.deformSize());
        var world=io.github.samlinb.openbone.core.animation.AnimationSampler.world(imported,imported.animations().get(0).id(),0,false);
        assertEquals(new Vec2(3,2),world.clips().get(c.id()).vertices().get(0));
    }

    @Test void projectSizeOverrideIsExplicitAndBounded() {
        String old=System.getProperty("openbone.project.maxBytes");
        try {
            System.clearProperty("openbone.project.maxBytes");assertEquals(16*1024*1024,ProjectCodec.configuredMaxBytes());
            System.setProperty("openbone.project.maxBytes","268435456");assertEquals(256*1024*1024,ProjectCodec.configuredMaxBytes());
            for(String invalid:List.of("0","-1","536870913","not-a-size")) {
                System.setProperty("openbone.project.maxBytes",invalid);assertThrows(IllegalArgumentException.class,ProjectCodec::configuredMaxBytes);
            }
        } finally {if(old==null)System.clearProperty("openbone.project.maxBytes");else System.setProperty("openbone.project.maxBytes",old);}
    }
}
