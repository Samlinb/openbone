package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.io.*;
import io.github.samlinb.openbone.viewport.ImageAssets;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Important failure invariants with mesh+weights+deform+clip together, not region-only IO. */
class AdvancedPersistenceTest {
 @TempDir Path temp;
 DocumentSnapshot fixture()throws Exception{
  Path png=temp.resolve("原图.png");var image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,0x8040f060);ImageIO.write(image,"PNG",png.toFile());var d=ClippingTest.fixture(png.toString());var bones=new ArrayList<>(d.bones());bones.add(bone(2,"influence",id(1),new Transform(1,1,5,1,1)));d=new DocumentSnapshot(bones,d.slots(),d.attachments());d=MeshEdits.convert(d,id(22));d=WeightEdits.bind(d,id(22),List.of(new WeightEdits.Binding(id(1),1),new WeightEdits.Binding(id(2),1)));int size=MeshEdits.mesh(d,id(22)).deformSize();var v=new ArrayList<>(Collections.nCopies(size,0.0));var clip=d.animations().get(0).id();d=DeformEdits.put(d,clip,id(22),0,v);v.set(0,.25);return DeformEdits.put(d,clip,id(22),1,v);
 }
 record Receipt(EditorState state,int undo,int redo,boolean dirty,Optional<Path> path){}
 static Receipt receipt(ProjectDocument doc){var h=doc.history();return new Receipt(h.state(),h.undoCount(),h.redoCount(),h.isDirty(),doc.path());}
 static void editHistory(ProjectDocument doc){var h=doc.history();h.select(Selection.of(id(22)));h.selectKeys(Set.of(h.state().model().animations().get(0).deforms().get(0).keys().get(0).id()));h.execute("edit",Commands.model(d->ClippingEdits.vertex(d,id(20),0,new Vec2(-.1,0))));h.execute("redo",Commands.model(d->ClippingEdits.vertex(d,id(20),0,new Vec2(-.2,0))));h.undo();}
 @ParameterizedTest @EnumSource(AtomicProjectFile.Stage.class) void everyProjectFailurePreservesModelSelectionKeySelectionPathAndHistory(AtomicProjectFile.Stage fail)throws Exception{
  var d=fixture();Path target=temp.resolve("existing.json");byte[] source=Files.readAllBytes(temp.resolve("原图.png"));byte[] old=new ProjectCodec().encode(d);Files.write(target,old);var file=new AtomicProjectFile((s,p)->{if(s==fail)throw new IOException("injected "+s);},(s,t)->Files.move(s,t,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));var doc=new ProjectDocument(d,new ProjectCodec(),file);doc.openResolved(target);editHistory(doc);var before=receipt(doc);assertThrows(IOException.class,()->doc.savePortable(target));assertEquals(before,receipt(doc));assertArrayEquals(old,Files.readAllBytes(target));assertArrayEquals(source,Files.readAllBytes(temp.resolve("原图.png")));
 }
 @ParameterizedTest @EnumSource(ExportPackage.Stage.class) void everyExportFailureLeavesOldGenerationAndSourceBytesUntouched(ExportPackage.Stage fail)throws Exception{
  var d=fixture();var doc=new ProjectDocument(d);editHistory(doc);Path root=temp.resolve("package");new ExportPackage().publish(root,d,temp);Map<Path,byte[]> before=new HashMap<>();try(var files=Files.walk(root)){for(var p:files.filter(Files::isRegularFile).toList())before.put(p,Files.readAllBytes(p));}byte[] source=Files.readAllBytes(temp.resolve("原图.png"));var receipt=receipt(doc);assertThrows(IOException.class,()->new ExportPackage((s,p)->{if(s==fail)throw new IOException("injected "+s);},new AtomicProjectFile()).publish(root,doc.history().state().model(),temp));assertEquals(receipt,receipt(doc));for(var e:before.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(e.getKey()));assertArrayEquals(source,Files.readAllBytes(temp.resolve("原图.png")));assertNotNull(new SkeletonJsonCodec().read(ExportPackage.resolve(root.resolve("current.json"))));
 }
 @Test void successivePortableSaveAsMissingImageRelocationAndFailedImportAreAtomic()throws Exception{
  var d=fixture();byte[] png=Files.readAllBytes(temp.resolve("原图.png"));var doc=new ProjectDocument(d);Path first=temp.resolve("一.json"),second=temp.resolve("二.json"),third=temp.resolve("三.json");doc.savePortable(first);doc.save(second);var reopened=new ProjectDocument(DocumentSnapshot.EMPTY);reopened.openResolved(second);reopened.save(third);var fresh=new ProjectDocument(DocumentSnapshot.EMPTY);fresh.openResolved(third);var m=MeshEdits.mesh(fresh.history().state().model(),id(22));assertArrayEquals(png,Files.readAllBytes(Path.of(m.source())));assertEquals(d.evaluateWorld(),fresh.history().state().model().evaluateWorld());
  editHistory(fresh);var before=receipt(fresh);Path bad=temp.resolve("bad.json");Files.writeString(bad,"{\"schemaVersion\":3,\"bones\":[]}");assertThrows(IOException.class,()->fresh.openResolved(bad));assertEquals(before,receipt(fresh));assertThrows(IOException.class,()->fresh.importSkeleton(bad));assertEquals(before,receipt(fresh));assertThrows(IOException.class,()->fresh.save(temp.resolve("原图.png")));assertEquals(before,receipt(fresh));assertArrayEquals(png,Files.readAllBytes(temp.resolve("原图.png")));
  Files.delete(Path.of(m.source()));var assets=new ImageAssets();assertTrue(assets.get(m.source()).missing());Path relocated=temp.resolve("重定位.png");Files.write(relocated,png);fresh.history().execute("relocate",Commands.model(x->{var next=x;for(var a:x.attachments())if(Objects.equals(a.source(),m.source()))next=MeshEdits.replace(next,a.id(),z->MeshEdits.source(z,relocated.toString()));return next;}));fresh.save(temp.resolve("四.json"));var finalDoc=new ProjectDocument(DocumentSnapshot.EMPTY);finalDoc.openResolved(temp.resolve("四.json"));assertFalse(new ImageAssets().get(MeshEdits.mesh(finalDoc.history().state().model(),id(22)).source()).missing());assertArrayEquals(png,Files.readAllBytes(relocated));
 }
}
