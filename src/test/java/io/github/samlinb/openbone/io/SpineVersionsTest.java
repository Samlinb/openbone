package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpineVersionsTest {
    @TempDir Path temp;

    @Test void actualOfficialExportsHaveMatchingJsonAndBinaryPosesInEachVersion()throws Exception {
        assertAll(Arrays.stream(SpineVersion.values()).map(family->(org.junit.jupiter.api.function.Executable)()-> {
            Path fixtures=Path.of("src/test/resources/spine-versions",family.family());
            Path directory=Files.createDirectories(temp.resolve(family.family()));
            Files.copy(fixtures.resolve("spineboy-ess.json"),directory.resolve("spineboy-ess.json"));
            Files.copy(fixtures.resolve("spineboy-ess.skel"),directory.resolve("spineboy-ess.skel"));
            var original=new ObjectMapper().readTree(Files.readAllBytes(directory.resolve("spineboy-ess.json")));
            fixtureImages(original.path("skins"),directory.resolve("images"));
            var jsonCodec=new SkeletonJsonCodec();var binaryCodec=new SkeletonJsonCodec();
            Path json=directory.resolve("spineboy-ess.json"),binary=directory.resolve("spineboy-ess.skel");
            var jsonSelection=selection(json,SpineImportPolicy.Format.JSON);
            var binarySelection=selection(binary,SpineImportPolicy.Format.BINARY);
            var expected=jsonCodec.read(json,directory,jsonSelection.known()?null:jsonSelection.approval());
            var actual=binaryCodec.read(binary,directory,binarySelection.known()?null:binarySelection.approval());
            assertEquals(jsonCodec.sourceVersion(),binaryCodec.sourceVersion(),family.family());
            assertEquals(expected.bones().stream().map(Bone::name).toList(),actual.bones().stream().map(Bone::name).toList(),family.family());
            assertEquals(expected.slots().size(),actual.slots().size(),family.family());
            assertEquals(expected.attachments().size(),actual.attachments().size(),family.family());
            // These separately exported official pairs have rounded JSON controls;
            // their sampled poses differ by fractions of a pixel/degree.
            comparePoses(expected,actual,family.family(),1);
        }));
    }

    @Test void allVersionExportsRoundTripBonesAnimationsMeshesAndEventData()throws Exception {
        var source=new SkeletonJsonCodec().read(Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel"));
        var paths=new LinkedHashMap<EntityId,String>();for(var attachment:source.attachments())if(attachment.source()!=null)paths.put(attachment.id(),attachment.name());
        for(String name:paths.values())javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",temp.resolve(name+".png").toFile());
        for(var family:SpineVersion.values()) {
            if(family.legacy())continue; // Independent per-axis curves may not have a 3.8 equivalent; tested below.
            String version=family.exportVersion();
            byte[] json=new SkeletonJsonCodec().encode(source,paths,"",version);
            var fromJson=new SkeletonJsonCodec().decode(json,temp);
            Path binary=temp.resolve(family.family()+".skel");Files.write(binary,SpineBinaryWriter.encode(source,paths,"",version));
            var fromBinary=new SkeletonJsonCodec().read(binary);
            assertEquals(17,fromBinary.bones().size());assertEquals(16,fromBinary.attachments().size());
            comparePoses(source,fromJson,version+" JSON");comparePoses(source,fromBinary,version+" binary");
            for(var attachment:fromBinary.attachments())if(attachment instanceof MeshAttachment mesh&&mesh.weighted())for(var weights:mesh.weights())assertEquals(1,weights.stream().mapToDouble(Influence::weight).sum(),1e-6);
        }
    }

    @Test void legacy38WriterConvertsRelativeCurvesAndUsesStringHashHeader()throws Exception {
        var model=new SkeletonJsonCodec().read(Path.of("src/test/resources/spine-versions/3.8/spineboy-ess.json"));
        var paths=new LinkedHashMap<EntityId,String>();for(var attachment:model.attachments())if(attachment.source()!=null)paths.put(attachment.id(),attachment.name());
        byte[] bytes=SpineBinaryWriter.encode(model,paths,"","3.8.99");
        assertEquals("3.8.99",SpineImportPolicy.binaryVersion(bytes));
        Path file=temp.resolve("legacy.skel");Files.write(file,bytes);var imported=new SkeletonJsonCodec().read(file);
        comparePoses(model,imported,"3.8 binary roundtrip");
        assertThrows(ProjectFormatException.class,()->SpineBinaryWriter.encode(model,paths,"","3.8.75"));
    }

    @Test void nativeProbeReadsActualCompressedContainerWithoutMistakingItForSkel()throws Exception {
        Path file=Path.of("res/spineboy/spineboy-pro.spine");Assumptions.assumeTrue(Files.isRegularFile(file));
        var header=SpineNativeHeader.read(Files.readAllBytes(file));assertEquals(17,header.format());assertEquals("3.8.75",header.version());
        assertThrows(ProjectFormatException.class,()->SpineNativeHeader.read(Files.readAllBytes(Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel"))));
    }

    @Test void constraintSetupAndSteppedAnimationSurviveEachWireVersion()throws Exception {
        var ik=ConstraintTimelineCodecTest.stepped(ConstraintTimelineCodecTest.ik(),ConstraintChannel.IK,List.of(.25,0.,1.,0.,0.),List.of(.75,0.,0.,0.,0.));
        var transform=ConstraintTimelineCodecTest.base().withConstraints(List.of(new TransformConstraint(io.github.samlinb.openbone.TestData.id(5),"transform",0,true,List.of(io.github.samlinb.openbone.TestData.id(2)),io.github.samlinb.openbone.TestData.id(3),false,false,Collections.nCopies(6,0.),Collections.nCopies(6,.5))));
        transform=ConstraintTimelineCodecTest.stepped(transform,ConstraintChannel.TRANSFORM_MIX,Collections.nCopies(6,.25),Collections.nCopies(6,.75));
        var physics=PhysicsParameterCodecTest.key(PhysicsParameterCodecTest.base(),ConstraintChannel.PHYSICS_WIND,0,.1);
        physics=PhysicsParameterCodecTest.key(physics,ConstraintChannel.PHYSICS_WIND,1,.5);
        for(var family:SpineVersion.values())for(var model:List.of(ik,transform,physics)) {
            if(model==physics&&!family.atLeast(SpineVersion.V42)) {
                assertThrows(ProjectFormatException.class,()->SpineBinaryWriter.encode(model,Map.of(),"",family.exportVersion()));continue;
            }
            Path file=temp.resolve("constraint.skel");Files.write(file,SpineBinaryWriter.encode(model,Map.of(),"",family.exportVersion()));
            var restored=new SkeletonJsonCodec().read(file);
            assertEquals(model.constraints().get(0).getClass(),restored.constraints().get(0).getClass());
            for(double time:new double[]{0,.25,.5,1}) {
                var left=AnimationSampler.constraints(model,model.animations().get(0).id(),time,false).get(0);
                var right=AnimationSampler.constraints(restored,restored.animations().get(0).id(),time,false).get(0);
                for(var channel:ConstraintChannel.values())if(channel.supports(left)) {
                    var a=channel.values(left);var b=channel.values(right);for(int i=0;i<a.size();i++)assertEquals(a.get(i),b.get(i),1e-5,family+" "+channel);
                }
            }
        }
    }

    @Test void binaryPackagePublishesEachVersionAndNativeFailsBeforePublicationWithoutCli()throws Exception {
        var model=ConstraintTimelineCodecTest.base();
        for(var family:SpineVersion.values()) {
            Path directory=temp.resolve("package-"+family.family());
            Path binary=new ExportPackage().publish(directory,model,temp,family.exportVersion(),ExportPackage.Format.BINARY);
            assertTrue(Files.isRegularFile(binary));assertEquals(3,new SkeletonJsonCodec().read(binary).bones().size());
            assertTrue(Files.isRegularFile(ExportPackage.resolve(directory.resolve("current.json"))));
        }
        Assumptions.assumeFalse(SpineCliBridge.configured());Path directory=temp.resolve("native");
        assertThrows(ProjectFormatException.class,()->new ExportPackage().publish(directory,model,temp,"4.2.22",ExportPackage.Format.NATIVE));
        assertFalse(Files.exists(directory));
    }

    private static SpineImportPolicy.Selection selection(Path file,SpineImportPolicy.Format format)throws Exception {
        byte[] bytes=Files.readAllBytes(file);try{return SpineImportPolicy.select(bytes,format,null);}catch(SpineVersionRiskException risk){return risk.selection();}
    }
    private static void fixtureImages(JsonNode node,Path directory)throws Exception {
        if(node.isObject())for(var entries=node.fields();entries.hasNext();) {
            var e=entries.next();var value=e.getValue();
            if(value.isObject()&&(value.has("width")||value.has("uvs"))){
                Path png=directory.resolve(value.path("path").asText(e.getKey())+".png");Files.createDirectories(png.getParent());
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",png.toFile());
            }
            fixtureImages(value,directory);
        }else if(node.isArray())for(var child:node)fixtureImages(child,directory);
    }
    private static void comparePoses(DocumentSnapshot expected,DocumentSnapshot actual,String context) {
        comparePoses(expected,actual,context,.04);
    }
    private static void comparePoses(DocumentSnapshot expected,DocumentSnapshot actual,String context,double tolerance) {
        assertEquals(expected.animations().stream().map(AnimationClip::name).toList(),actual.animations().stream().map(AnimationClip::name).toList(),context);
        for(var clip:expected.animations()) {
            var counterpart=actual.animations().stream().filter(c->c.name().equals(clip.name())).findFirst().orElseThrow();
            for(double time:new double[]{0,.13,.5,.93,1.7}) {
                var left=AnimationSampler.pose(expected,clip.id(),time,false);var right=AnimationSampler.pose(actual,counterpart.id(),time,false);
                for(var bone:expected.bones()) {
                    var match=actual.bones().stream().filter(b->b.name().equals(bone.name())).findFirst().orElseThrow();
                    var a=left.getOrDefault(bone.id(),bone.local());var b=right.getOrDefault(match.id(),match.local());
                    assertEquals(a.x(),b.x(),tolerance,context+" "+clip.name()+" "+bone.name()+" x @"+time);
                    assertEquals(a.y(),b.y(),tolerance,context+" "+clip.name()+" "+bone.name()+" y @"+time);
                    assertEquals(a.rotation(),b.rotation(),tolerance,context+" "+clip.name()+" "+bone.name()+" rotate @"+time);
                    assertEquals(a.scaleX(),b.scaleX(),.001,context+" scaleX");assertEquals(a.scaleY(),b.scaleY(),.001,context+" scaleY");
                }
            }
        }
    }
}
