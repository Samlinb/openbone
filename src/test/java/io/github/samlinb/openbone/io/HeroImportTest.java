package io.github.samlinb.openbone.io;

import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the user's local demonstration assets; their license forbids redistribution. */
@EnabledIfSystemProperty(named="openbone.hero", matches="true")
class HeroImportTest {
    @TempDir Path temp;
    static final Path SOURCE = Path.of("res/hero/hero-pro.spine").toAbsolutePath();
    static SpineImportPolicy.Approval approval() throws Exception {
        var resolved = SpineProjectSource.resolve(SOURCE);
        return assertThrows(SpineVersionRiskException.class, () -> SpineImportPolicy.select(
                Files.readAllBytes(resolved.json()), SpineImportPolicy.Format.JSON, null)).selection().approval();
    }
    @Test void exportsHeroWithSkinsWeightsPathsAndAnimation() throws Exception {
        var document=new ProjectDocument(DocumentSnapshot.EMPTY);document.openResolved(SOURCE,approval());
        var d=document.history().state().model();
        Path evidence=Path.of("target/hero-export-validation");Files.createDirectories(evidence);
        var reference=new ArrayList<Map<String,Object>>();
        for(String skin:List.of("default","weapon/sword","weapon/morningstar")) {
            var selected=ExtendedEdits.skin(d,skin);
            for(var clip:d.animations()) for(int sample=0;sample<=12;sample++) {
                double time=clip.duration()*sample/12;var pose=AnimationSampler.world(selected,clip.id(),time,false);
                var bones=new LinkedHashMap<String,List<Double>>();
                for(var bone:selected.bones()) if(selected.activeBones().contains(bone.id())) {
                    var m=pose.bones().get(bone.id()).matrix();bones.put(bone.name(),List.of(m.a(),m.b(),m.c(),m.d(),m.tx(),m.ty()));
                }
                var geometry=new ArrayList<Map<String,Object>>();
                for(var attachment:selected.attachments()) {
                    var slot=selected.slots().stream().filter(x->x.id().equals(attachment.slotId())).findFirst().orElseThrow();
                    if(!selected.activeBones().contains(slot.boneId()))continue;
                    java.util.List<io.github.samlinb.openbone.core.math.Vec2> points=null;
                    if(attachment instanceof MeshAttachment mesh)points=pose.meshes().get(mesh.id()).vertices();
                    if(attachment instanceof PathAttachment path && path.weights().stream().flatMap(List::stream).allMatch(i->selected.activeBones().contains(i.boneId())))
                        points=pose.paths().get(path.id());
                    if(points!=null)geometry.add(Map.of("slot",slot.name(),"name",attachment.name(),"points",points.stream().flatMap(p->java.util.stream.Stream.of(p.x(),p.y())).toList()));
                }
                reference.add(Map.of("skin",skin,"animation",clip.name(),"time",time,"bones",bones,"geometry",geometry));
            }
        }
        Files.write(evidence.resolve("reference.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(reference));
        for(String version:List.of("4.0.64","4.1.24","4.2.22","4.3.26")) for(var format:List.of(ExportPackage.Format.JSON,ExportPackage.Format.BINARY)) {
            Path json=new ExportPackage().publish(evidence.resolve(version+format),d,SOURCE.getParent(),version,format);
            var restored=new SkeletonJsonCodec().read(json);
            assertEquals(d.bones().size(),restored.bones().size());assertEquals(12,restored.animations().size());
            assertEquals(14,restored.resources().activation().requiredBones().size());
            assertEquals(1,restored.resources().activation().requiredConstraints().size());
            assertTrue(restored.attachments().stream().anyMatch(a->a instanceof PathAttachment p && p.weighted()));
            for(String skin:List.of("default","weapon/sword","weapon/morningstar")) {
                var original=ExtendedEdits.skin(d,skin);var imported=ExtendedEdits.skin(restored,skin);
                for(var clip:original.animations()) {
                    var next=imported.animations().stream().filter(a->a.name().equals(clip.name())).findFirst().orElseThrow();
                    for(int i=0;i<=12;i++) {
                        var a=AnimationSampler.world(original,clip.id(),clip.duration()*i/12,false);
                        var b=AnimationSampler.world(imported,next.id(),clip.duration()*i/12,false);
                        for(var bone:original.bones()) {
                            var other=imported.bones().stream().filter(x->x.name().equals(bone.name())).findFirst().orElseThrow();
                            assertEquals(a.bones().get(bone.id()).origin().x(),b.bones().get(other.id()).origin().x(),.02,clip.name()+"/"+bone.name());
                            assertEquals(a.bones().get(bone.id()).origin().y(),b.bones().get(other.id()).origin().y(),.02,clip.name()+"/"+bone.name());
                        }
                    }
                }
            }
        }
    }
    @Test void localNativeSelectionLoadsOfficialSnapshotSkinsAndAllAnimations() throws Exception {
        byte[] original = Files.readAllBytes(SOURCE);
        var document = new ProjectDocument(DocumentSnapshot.EMPTY);
        document.openResolved(SOURCE, approval());
        var d = document.history().state().model();
        assertEquals(44,d.bones().size()); assertEquals(31,d.slots().size());
        assertEquals(4,d.constraints().size()); assertEquals(12,d.animations().size());
        assertEquals(List.of("default","weapon/morningstar","weapon/sword"), d.skins().stream().map(Skin::name).toList());
        assertTrue(d.attachments().stream().filter(a -> a.source()!=null).allMatch(a -> Files.isRegularFile(Path.of(a.source()))));
        assertTrue(document.importWarnings().get(0).contains("snapshot"));
        assertTrue(document.importWarnings().stream().noneMatch(w -> w.contains("filtered") || w.contains("unavailable")),document.importWarnings().toString());
        assertTrue(d.attachments().stream().filter(a -> a instanceof MeshAttachment).map(a -> (MeshAttachment)a)
                .flatMap(m -> m.weights().stream()).flatMap(List::stream).anyMatch(i -> i.weight()<0));
        for (String skin : List.of("default","weapon/sword","weapon/morningstar")) {
            var selected = ExtendedEdits.skin(d,skin);
            assertEquals(skin.equals("weapon/morningstar") ? 4 : 3, selected.activeConstraints(selected.constraints()).size());
            var draws = io.github.samlinb.openbone.viewport.VisibleGeometry.evaluate(selected,selected.evaluateWorld());
            var sources = draws.stream().map(x -> Path.of(x.source()).getFileName().toString()).toList();
            assertEquals(skin.equals("weapon/sword"),sources.contains("sword.png"));
            assertEquals(skin.equals("weapon/morningstar"),sources.contains("chain-ball.png"));
            for (var clip : selected.animations()) for (int i=0;i<=6;i++)
                assertFalse(AnimationSampler.world(selected,clip.id(),clip.duration()*i/6,false).meshes().isEmpty());
        }
        var selected = ExtendedEdits.skin(d,"weapon/morningstar");
        document.history().execute("Select weapon",Commands.model(ignored -> selected));
        Path saved=temp.resolve("hero.openbone.json"); document.savePortable(saved);
        assertEquals(12,new ProjectCodec().parseTree(Files.readAllBytes(saved)).path("schemaVersion").asInt());
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY); reopened.openResolved(saved);
        assertEquals(selected.resources().activation(),reopened.history().state().model().resources().activation());
        assertEquals(selected.animations(),reopened.history().state().model().animations());
        assertArrayEquals(original,Files.readAllBytes(SOURCE));
        System.out.println("Hero: attachments="+d.attachments().size()+" warnings="+document.importWarnings());
    }
}
