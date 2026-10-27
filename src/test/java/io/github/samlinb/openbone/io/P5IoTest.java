package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class P5IoTest {
    @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
    Path png(String folder,int color) throws Exception {
        Path p=temp.resolve(folder).resolve("同名.png");Files.createDirectories(p.getParent());var image=new BufferedImage(8,6,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<6;y++)for(int x=0;x<8;x++)image.setRGB(x,y,x<2?0:color);ImageIO.write(image,"PNG",p.toFile());return p;
    }
    DocumentSnapshot model() throws Exception {
        var bones=List.of(new Bone(id(1),"根/骨",null,new Transform(12,-7,15,-1.2,.7),80),new Bone(id(2),"child",id(1),new Transform(70,6,-20,.8,1.4),60),new Bone(id(3),"tip",id(2),new Transform(50,-8,25,1,.5),40));
        var slots=List.of(new Slot(id(10),"槽/~",id(3),id(20)),new Slot(id(11),"back",id(2),id(21)));
        var regions=List.<Attachment>of(new RegionAttachment(id(20),"同名",id(10),png("原图一",0x8000ff00).toString(),new Transform(3,4,12,-1,1),64,48),new RegionAttachment(id(21),"同名",id(11),png("原图二",0xffff0000).toString(),Transform.IDENTITY,32,20));
        var curves=List.of(new Curve(Curve.Kind.BEZIER,.2,1.4,.7,-.2),Curve.STEPPED);List<AnimationClip> animations=new ArrayList<>();int next=100;
        for(double[] rotation:List.of(new double[]{170,190},new double[]{170,-170},new double[]{0,720})){
            var tracks=List.of(new BoneTimeline(id(3),Channel.ROTATE,List.of(new Keyframe(id(next++),.2,List.of(rotation[0]),List.of(Curve.LINEAR)),new Keyframe(id(next++),1,List.of(rotation[1]),List.of(Curve.LINEAR)))),
                new BoneTimeline(id(2),Channel.TRANSLATE,List.of(new Keyframe(id(next++),.2,List.of(10.0,-5.0),curves),new Keyframe(id(next++),1,List.of(-20.0,15.0),List.of(Curve.LINEAR,Curve.LINEAR)))),
                new BoneTimeline(id(1),Channel.SCALE,List.of(new Keyframe(id(next++),.2,List.of(-1.0,0.0),List.of(Curve.STEPPED,Curve.LINEAR)),new Keyframe(id(next++),1,List.of(0.0,-2.0),List.of(Curve.LINEAR,Curve.LINEAR)))));
            animations.add(new AnimationClip(id(next++),"动画"+rotation[1],tracks));
        }return new DocumentSnapshot(bones,slots,regions,animations);
    }
    Path export(DocumentSnapshot d,Path dir) throws Exception {return new ExportPackage().publish(dir,d,temp);}
    ObjectNode tree(DocumentSnapshot d) throws Exception {
        var paths=new LinkedHashMap<EntityId,String>();int i=0;for(var a:d.attachments())paths.put(a.id(),"part"+i++);
        for(var e:paths.entrySet())Files.copy(Path.of(((RegionAttachment)d.attachments().stream().filter(a->a.id().equals(e.getKey())).findFirst().orElseThrow()).source()),temp.resolve(e.getValue()+".png"),StandardCopyOption.REPLACE_EXISTING);
        return (ObjectNode)json.readTree(new SkeletonJsonCodec().encode(d,paths,"./"));
    }
    void compare(DocumentSnapshot expected,DocumentSnapshot actual) {
        assertEquals(expected.bones().stream().map(Bone::name).toList(),actual.bones().stream().map(Bone::name).toList());
        assertEquals(expected.animations().size(),actual.animations().size());
        for(int a=0;a<expected.animations().size();a++)for(double t:new double[]{0,.1,.2,.25,.5,.999,1,1.3,2}){
            var x=expected.evaluateWorld(AnimationSampler.pose(expected,expected.animations().get(a).id(),t,false));
            var y=actual.evaluateWorld(AnimationSampler.pose(actual,actual.animations().get(a).id(),t,false));
            for(int i=0;i<3;i++){var xm=x.bones().get(expected.bones().get(i).id()).matrix();var ym=y.bones().get(actual.bones().get(i).id()).matrix();
                assertEquals(xm.a(),ym.a(),1e-8);assertEquals(xm.b(),ym.b(),1e-8);assertEquals(xm.c(),ym.c(),1e-8);assertEquals(xm.d(),ym.d(),1e-8);assertEquals(xm.tx(),ym.tx(),1e-8);assertEquals(xm.ty(),ym.ty(),1e-8);}
        }
    }
    @Test void actualPackageRoundTripsNamesSplitCurvesRotationMatricesResourcesAndAlpha() throws Exception {
        var d=model();Path output=temp.resolve("中文 导出");Path path=export(d,output);assertEquals(path,ExportPackage.resolve(output.resolve("current.json")));
        var loaded=new SkeletonJsonCodec().read(path);compare(d,loaded);
        var node=json.readTree(path.toFile()).path("animations").elements().next().path("bones").path("child");
        assertTrue(node.has("translatex")&&node.has("translatey"));assertTrue(node.path("translatex").get(0).path("curve").isArray());assertEquals("stepped",node.path("translatey").get(0).path("curve").asText());
        for(int i=0;i<2;i++)assertArrayEquals(Files.readAllBytes(Path.of(((RegionAttachment)d.attachments().get(i)).source())),Files.readAllBytes(Path.of(((RegionAttachment)loaded.attachments().get(i)).source())));
        assertNotEquals(((RegionAttachment)loaded.attachments().get(0)).source(),((RegionAttachment)loaded.attachments().get(1)).source());
    }
    @ParameterizedTest @EnumSource(ExportPackage.Stage.class)
    void everyPublicationFailurePreservesOldPointerAndAllOldBytes(ExportPackage.Stage stage) throws Exception {
        var d=model();Path out=temp.resolve("旧包");Path first=export(d,out);Map<Path,byte[]> before=new LinkedHashMap<>();try(var walk=Files.walk(out)){walk.filter(Files::isRegularFile).forEach(p->{try{before.put(p,Files.readAllBytes(p));}catch(IOException e){throw new UncheckedIOException(e);}});}
        var failing=new ExportPackage((s,p)->{if(s==stage)throw new IOException("injected "+s);},new AtomicProjectFile());
        assertThrows(IOException.class,()->failing.publish(out,d,temp));for(var e:before.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(e.getKey()));assertEquals(first,ExportPackage.resolve(out.resolve("current.json")));
    }
    @Test void unsupportedAtomicPointerReplaceCannotPublishNewGeneration() throws Exception {
        var d=model();Path dir=temp.resolve("包");Path first=export(d,dir);
        var exporter=new ExportPackage((s,p)->{},new AtomicProjectFile((s,p)->{},(s,t)->{throw new AtomicMoveNotSupportedException(s.toString(),t.toString(),"test");}));
        assertThrows(IOException.class,()->exporter.publish(dir,d,temp));assertEquals(first,ExportPackage.resolve(dir.resolve("current.json")));
    }
    @Test void unrelatedDirectoryEntryAndTamperedGenerationAreRejectedWithoutOverwriting() throws Exception {
        var d=model();Path dir=temp.resolve("existing");Files.createDirectory(dir);Files.writeString(dir.resolve("current.json"),"important");
        assertThrows(IOException.class,()->export(d,dir));assertEquals("important",Files.readString(dir.resolve("current.json")));
        Path good=temp.resolve("good");Path path=export(d,good);Files.writeString(path,"corrupt");assertThrows(IOException.class,()->ExportPackage.resolve(good.resolve("current.json")));
    }
    @Test void portableSaveAsIsSelfContainedAndRetainsAnimationHistorySelectionAndOriginals() throws Exception {
        var d=model();var doc=new ProjectDocument(d);doc.history().select(Selection.of(id(3)));doc.history().execute("rename",Commands.model(m->ModelEdits.editBone(m,id(3),b->b.withName("新名"))));
        var state=doc.history().state();Path target=temp.resolve("中文工程.json");doc.savePortable(target);assertEquals(state,doc.history().state());assertFalse(doc.history().isDirty());assertEquals(1,doc.history().undoCount());
        var raw=new ProjectCodec().read(target);assertTrue(((RegionAttachment)raw.attachments().get(0)).source().startsWith("中文工程.json.assets/"));assertEquals(d.animations(),raw.animations());
        Path copy=temp.resolve("搬家");Files.createDirectory(copy);Path other=copy.resolve("另存.json");doc.save(other);var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(other);
        compare(doc.history().state().model(),reopened.history().state().model());
        for(var a:d.attachments())assertTrue(Files.exists(Path.of(((RegionAttachment)a).source())));
        // Move away the originals only as test setup; relocated saved resources must remain readable.
        for(var a:d.attachments()){Path p=Path.of(((RegionAttachment)a).source());Files.move(p,p.resolveSibling("original-preserved.png"));}
        reopened.save(other);for(var a:reopened.history().state().model().attachments())ResourcePaths.png(Path.of(((RegionAttachment)a).source()));
        doc.history().undo();assertTrue(doc.history().isDirty());doc.history().redo();assertFalse(doc.history().isDirty());
    }
    @ParameterizedTest @EnumSource(AtomicProjectFile.Stage.class)
    void portableJsonFailuresLeaveCurrentStateAndOldProjectValid(AtomicProjectFile.Stage stage) throws Exception {
        var d=model();Path target=temp.resolve("old.json");new ProjectDocument(d).savePortable(target);byte[] old=Files.readAllBytes(target);
        var writer=new AtomicProjectFile((s,p)->{if(s==stage)throw new IOException("inject");},(s,t)->Files.move(s,t,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
        var doc=new ProjectDocument(d,new ProjectCodec(),writer);doc.history().select(Selection.of(id(3)));doc.history().execute("edit",Commands.model(m->ModelEdits.editBone(m,id(3),b->b.withName("changed"))));var before=doc.history().state();
        assertThrows(IOException.class,()->doc.savePortable(target));assertEquals(before,doc.history().state());assertTrue(doc.history().isDirty());assertTrue(doc.path().isEmpty());assertArrayEquals(old,Files.readAllBytes(target));new ProjectDocument(DocumentSnapshot.EMPTY).openResolved(target);
    }
    @Test void missingAndCorruptImagesRefuseSaveAndExportWithOldBytesPreserved() throws Exception {
        var d=model();Path target=temp.resolve("old.json");var doc=new ProjectDocument(d);doc.savePortable(target);Path pkg=temp.resolve("pkg");Path previous=export(d,pkg);byte[] old=Files.readAllBytes(target);
        Files.writeString(Path.of(((RegionAttachment)d.attachments().get(0)).source()),"broken PNG");
        assertThrows(IOException.class,()->doc.savePortable(target));assertArrayEquals(old,Files.readAllBytes(target));assertThrows(IOException.class,()->export(d,pkg));assertEquals(previous,ExportPackage.resolve(pkg.resolve("current.json")));
    }
    @Test void importedModelIsUnsavedAndInvalidImportPreservesEntireOldDocument() throws Exception {
        var d=model();Path path=export(d,temp.resolve("pkg"));var doc=new ProjectDocument(d);doc.save(temp.resolve("original.json"));doc.history().select(Selection.of(id(3)));
        doc.history().execute("a",Commands.model(m->ModelEdits.editBone(m,id(3),b->b.withName("edit"))));doc.history().execute("b",Commands.model(m->ModelEdits.editBone(m,id(2),b->b.withName("edit2"))));doc.history().undo();var before=doc.history().state();var saved=doc.path();
        Path bad=temp.resolve("bad.json");Files.writeString(bad,"{\"skeleton\":");assertThrows(IOException.class,()->doc.importSkeleton(bad));assertEquals(before,doc.history().state());assertEquals(saved,doc.path());assertTrue(doc.history().isDirty());assertEquals(1,doc.history().redoCount());
        doc.importSkeleton(path);assertTrue(doc.history().isDirty());assertTrue(doc.path().isEmpty());assertEquals(0,doc.history().undoCount());assertEquals(Selection.EMPTY,doc.history().state().selection());
    }
    @Test void unknownFieldsAtEverySupportedLevelAndUnsupportedFeaturesAreReportedTogether() throws Exception {
        var n=tree(model());n.put("events",1);((ObjectNode)n.get("skeleton")).put("future",0);((ObjectNode)n.get("bones").get(0)).put("futureBoneField",12);((ObjectNode)n.get("slots").get(0)).put("blend","multiply");
        var skin=(ObjectNode)n.get("skins").get(0);skin.put("future",0);var region=(ObjectNode)skin.path("attachments").path("槽/~").path("同名");region.put("type","linkedmesh").put("sequence",1);
        var anim=(ObjectNode)n.get("animations").elements().next();anim.put("drawOrder",1);var bone=(ObjectNode)anim.path("bones").path("tip");bone.put("futureTimeline",1);((ObjectNode)bone.path("rotate").get(0)).put("future",2);
        String message=assertThrows(IOException.class,()->new SkeletonJsonCodec().decode(json.writeValueAsBytes(n),temp)).getMessage();
        for(String field:List.of("events","skeleton.future","futureBoneField","blend","sequence","type","drawOrder","futureTimeline","rotate[0].future"))assertTrue(message.contains(field),message);
    }
    @Test void malformedVersionCycleReferenceNonfiniteAndPathEscapeRefuse() throws Exception {
        var d=model();var baseline=tree(d);
        List<java.util.function.Consumer<ObjectNode>> mutations=List.of(
            n->((ObjectNode)n.get("skeleton")).put("spine","4.4.26"),n->((ObjectNode)n.get("bones").get(0)).put("parent","tip"),
            n->((ObjectNode)n.get("slots").get(0)).put("bone","absent"),n->((ObjectNode)n.get("slots").get(0)).put("attachment","absent"),
            n->((ObjectNode)n.get("bones").get(0)).put("x",Double.POSITIVE_INFINITY),
            n->((ObjectNode)n.path("skins").get(0).path("attachments").path("槽/~").path("同名")).put("path","../escape"),
            n->((ObjectNode)n.get("skeleton")).put("images","C:/outside"));
        for(var mutation:mutations){var n=baseline.deepCopy();mutation.accept(n);assertThrows(IOException.class,()->new SkeletonJsonCodec().decode(json.writeValueAsBytes(n),temp));}
        assertThrows(IOException.class,()->new SkeletonJsonCodec().decode("{\"skeleton\":{},\"skeleton\":{}}".getBytes(),temp));
        Files.delete(temp.resolve("part0.png"));assertThrows(IOException.class,()->new SkeletonJsonCodec().decode(json.writeValueAsBytes(baseline),temp));
    }
    @Test void atlasRotationTrimPmaAndCustomFieldsRefuseInsteadOfBeingDropped() throws Exception {
        var d=model();Path path=export(d,temp.resolve("pkg")),atlas=path.resolveSibling("skeleton.atlas");String baseline=Files.readString(atlas);
        for(String text:List.of(baseline.replace("rotate: false","rotate: true"),baseline.replace("offset: 0,0","offset: 1,0"),baseline.replace("pma: false","pma: true"),baseline.replace("RGBA8888","RGB888"),baseline.replace("  index: -1","  mystery: 1\n  index: -1"))){Files.writeString(atlas,text);assertThrows(IOException.class,()->new SkeletonJsonCodec().read(path));}
    }
    @Test void float32OverflowAndTinyMatrixMaskingAreRejectedWhileDoubleProjectsRemainValid() throws Exception {
        for(double[] values:List.of(new double[]{1e308,1,1},new double[]{1e20,1e20,1e-20},new double[]{Float.MIN_VALUE,1e30,1},new double[]{1e19,1e19,1e-20})){
            var b=new Bone(id(1),"b",null,new Transform(0,0,45,values[0],values[0]),0);var s=new Slot(id(10),"s",id(1),null);var r=new RegionAttachment(id(20),"r",id(10),"image",new Transform(0,0,0,values[1],values[1]),values[2],values[2]);
            DocumentSnapshot d=new DocumentSnapshot(List.of(b),List.of(s),List.of(r));
            assertThrows(IOException.class,()->RuntimeRange.validate(d));assertDoesNotThrow(()->new ProjectCodec().encode(d));
        }
    }
    @Test void float32AnimationWorldOverflowControlsTimesAndExtremeAnglesReject() throws Exception {
        var b=new Bone(id(1),"b",null,new Transform(0,0,0,1e20,1e20),0);var child=new Bone(id(2),"child",id(1),Transform.IDENTITY,0);
        var key=new Keyframe(id(30),1,List.of(1e20,1e20),List.of(Curve.LINEAR,Curve.LINEAR));var clip=new AnimationClip(id(40),"overflow",List.of(new BoneTimeline(id(2),Channel.SCALE,List.of(key))));
        var d=new DocumentSnapshot(List.of(b,child),List.of(),List.of(),List.of(clip));assertThrows(IOException.class,()->RuntimeRange.validate(d));
        var normal=List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0));
        for(var track:List.of(new BoneTimeline(id(1),Channel.ROTATE,List.of(new Keyframe(id(30),1,List.of(1e7),List.of(Curve.LINEAR)))),new BoneTimeline(id(1),Channel.ROTATE,List.of(new Keyframe(id(30),1,List.of(0.0),List.of(Curve.LINEAR)),new Keyframe(id(31),1+1e-12,List.of(1.0),List.of(Curve.LINEAR)))),new BoneTimeline(id(1),Channel.TRANSLATE,List.of(new Keyframe(id(30),0,List.of(0.0,0.0),List.of(new Curve(Curve.Kind.BEZIER,.1,1e39,.9,1e39),Curve.LINEAR)),new Keyframe(id(31),1,List.of(1.0,1.0),List.of(Curve.LINEAR,Curve.LINEAR)))))){
            var x=new DocumentSnapshot(normal,List.of(),List.of(),List.of(new AnimationClip(id(40),"bad",List.of(track))));assertThrows(IOException.class,()->RuntimeRange.validate(x));
        }
    }
    @Test void tinyParentCannotHideOverflowInRuntimeRegionLocalQuadConstruction() {
        var b=new Bone(id(1),"root",null,new Transform(0,0,0,1e-20,1e-20),0);
        var s=new Slot(id(10),"s",id(1),id(20));var r=new RegionAttachment(id(20),"r",s.id(),"x.png",new Transform(0,0,0,1e20,1e20),1e20,1e20);
        var d=new DocumentSnapshot(List.of(b),List.of(s),List.of(r));assertThrows(IOException.class,()->RuntimeRange.validate(d));
    }
    @Test void saveTargetsCannotOverwriteAnOriginalPngEvenWithAnUnusualExtension() throws Exception {
        var d=model();var doc=new ProjectDocument(d);Path png=Path.of(((RegionAttachment)d.attachments().get(0)).source());byte[] original=Files.readAllBytes(png);
        assertThrows(IOException.class,()->doc.save(png));assertThrows(IOException.class,()->doc.savePortable(png));assertArrayEquals(original,Files.readAllBytes(png));
        Path unusual=temp.resolve("source.json");Files.copy(png,unusual);var r=(RegionAttachment)d.attachments().get(0);var changed=ModelEdits.editRegion(d,r.id(),a->new RegionAttachment(a.id(),a.name(),a.slotId(),unusual.toString(),a.local(),a.width(),a.height()));
        assertThrows(IOException.class,()->new ProjectDocument(changed).save(unusual));assertArrayEquals(original,Files.readAllBytes(unusual));
    }
    @Test void rawRelativeLinksKeepOriginalBaseAcrossRepeatedPortableSaveAs() throws Exception {
        Path original=temp.resolve("原位置"),copy=temp.resolve("新位置");Files.createDirectories(original);Files.createDirectory(copy);
        var d=model();var r=(RegionAttachment)d.attachments().get(0);Files.copy(Path.of(r.source()),original.resolve("relative.png"));
        d=ModelEdits.editRegion(d,r.id(),a->new RegionAttachment(a.id(),a.name(),a.slotId(),"relative.png",a.local(),a.width(),a.height()));
        Path file=original.resolve("source.json");Files.write(file,new ProjectCodec().encode(d));var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.open(file);var before=doc.history().state();
        Path target=copy.resolve("角色.json");doc.savePortable(target);doc.save(target);doc.savePortable(copy.resolve("再另存.json"));
        assertEquals(before,doc.history().state());assertFalse(doc.history().isDirty());assertEquals(d.animations(),new ProjectCodec().read(target).animations());
        var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(target);for(var a:reopened.history().state().model().attachments())ResourcePaths.png(Path.of(((RegionAttachment)a).source()));
    }
    @Test void separateFloatBasisRoundingCannotAccumulateIntoAnAcceptedInfiniteRuntimeMatrix() {
        List<Bone> bones=new ArrayList<>();EntityId parent=null;
        for(int i=0;i<24000;i++){EntityId next=id(1000+i);bones.add(new Bone(next,"b"+i,parent,new Transform(0,0,i%2==0?1_000_000:-1_000_000,i==0?1e35:1,i==0?1e35:1),0));parent=next;}
        var d=new DocumentSnapshot(bones,List.of(),List.of());assertTrue(Double.isFinite(d.evaluateWorld().bones().get(parent).matrix().a()));
        assertThrows(IOException.class,()->RuntimeRange.validate(d));
        var safe=new DocumentSnapshot(bones.subList(0,2),List.of(),List.of());assertDoesNotThrow(()->RuntimeRange.validate(safe));
    }
    @Test void rawDoubleLoopClockAndFloatCallerClockAreExplicitlyDifferentAtNonrepresentableFirstKeyBoundary() throws Exception {
        var d=model();var clip=d.animations().get(0);double raw=1.2,wrapped=raw%clip.duration(),floatClock=(float)raw%(float)clip.duration();
        assertTrue(wrapped<.2);assertTrue(floatClock>=.2);assertTrue(floatClock-wrapped<=Math.ulp((float)raw));
        assertEquals(d.bone(id(1)).local().scaleX(),AnimationSampler.pose(d,clip.id(),raw,true).getOrDefault(id(1),d.bone(id(1)).local()).scaleX());
        assertEquals(-d.bone(id(1)).local().scaleX(),AnimationSampler.pose(d,clip.id(),floatClock,false).get(id(1)).scaleX(),1e-5);
    }
    @Test void distinctFloatKeysWithCollapsedBezierKnotsRejectButLinearNeighborRemainsSupported() {
        var b=new Bone(id(1),"root",null,Transform.IDENTITY,0);var first=new Keyframe(id(30),1,List.of(0.0),List.of(new Curve(Curve.Kind.BEZIER,0,0,0,1)));var last=new Keyframe(id(31),Math.nextUp(1.0f),List.of(90.0),List.of(Curve.LINEAR));
        assertTrue((float)first.time()<(float)last.time());var clip=new AnimationClip(id(40),"close",List.of(new BoneTimeline(b.id(),Channel.ROTATE,List.of(first,last))));
        var d=new DocumentSnapshot(List.of(b),List.of(),List.of(),List.of(clip));assertThrows(IOException.class,()->RuntimeRange.validate(d));
        var linear=new AnimationClip(clip.id(),clip.name(),List.of(new BoneTimeline(b.id(),Channel.ROTATE,List.of(first.withCurves(List.of(Curve.LINEAR)),last))));
        assertDoesNotThrow(()->RuntimeRange.validate(d.withAnimations(List.of(linear))));
        var ordinary=last.at(2);assertDoesNotThrow(()->RuntimeRange.validate(d.withAnimations(List.of(new AnimationClip(clip.id(),clip.name(),List.of(new BoneTimeline(b.id(),Channel.ROTATE,List.of(first,ordinary))))))));
    }
    @Test void runtimeRejectsGrayscalePaletteAnd16BitWhilePortableProjectKeepsOriginalBytes() throws Exception {
        var d=model();var doc=new ProjectDocument(d);Path pkg=temp.resolve("old-package");Path old=export(d,pkg);byte[] pointer=Files.readAllBytes(pkg.resolve("current.json"));Path source=Path.of(((RegionAttachment)d.attachments().get(0)).source());
        for(int type:new int[]{BufferedImage.TYPE_BYTE_GRAY,BufferedImage.TYPE_BYTE_INDEXED,BufferedImage.TYPE_USHORT_GRAY}){
            var image=new BufferedImage(8,6,type);ImageIO.write(image,"PNG",source.toFile());byte[] original=Files.readAllBytes(source);
            assertThrows(IOException.class,()->RuntimePng.read(source));assertThrows(IOException.class,()->export(d,pkg));assertArrayEquals(pointer,Files.readAllBytes(pkg.resolve("current.json")));assertEquals(old,ExportPackage.resolve(pkg.resolve("current.json")));
            Path project=temp.resolve("portable-"+type+".json");doc.savePortable(project);var copy=new ProjectCodec().read(project);Path copied=ResourcePaths.resolve(project.getParent(),((RegionAttachment)copy.attachments().get(0)).source());assertArrayEquals(original,Files.readAllBytes(copied));assertArrayEquals(original,Files.readAllBytes(source));
        }
    }
    @Test void gammaOrProfileChunksAreExplicitlyRejectedBeforeSilentColorConversion() throws Exception {
        byte[] original=Files.readAllBytes(png("plain",0x8000ff00));RuntimePng.require(original);
        for(String type:List.of("gAMA","iCCP","cHRM","sBIT","acTL")){
            var output=new ByteArrayOutputStream();output.write(original,0,33);var stream=new DataOutputStream(output);byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII),value={0,0,(byte)177,(byte)143};stream.writeInt(value.length);stream.write(name);stream.write(value);var crc=new java.util.zip.CRC32();crc.update(name);crc.update(value);stream.writeInt((int)crc.getValue());output.write(original,33,original.length-33);
            String message=assertThrows(IOException.class,()->RuntimePng.require(output.toByteArray())).getMessage();assertTrue(message.contains(type));
        }
    }
}
