package io.github.samlinb.openbone.io;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class NoRasterPackageTest {
 @TempDir Path temp;
 @Test void actualNoRasterGenerationImportsAndPublishesAgain()throws Exception{
  var d=new DocumentSnapshot(List.of(new Bone(id(1),"root",null,Transform.IDENTITY,0),new Bone(id(2),"arm",id(1),Transform.IDENTITY,100),new Bone(id(3),"target",id(1),new Transform(100,100,0,1,1),0)),List.of(),List.of()).withConstraints(List.of(new IkConstraint(id(4),"aim",0,true,List.of(id(2)),id(3),.5,0,true,false,false,false)));
  var pack=temp.resolve("无图片中文出口");var json=new ExportPackage().publish(pack,d,temp);assertEquals(0,Files.size(json.resolveSibling("skeleton.atlas")));assertEquals(json,ExportPackage.resolve(pack.resolve("current.json")));
  var doc=new ProjectDocument(DocumentSnapshot.EMPTY);doc.importSkeleton(json);var loaded=doc.history().state().model();assertEquals(3,loaded.bones().size());assertEquals(1,loaded.constraints().size());assertTrue(loaded.attachments().isEmpty());assertEquals(d.evaluateWorld().bones().get(id(2)).matrix(),loaded.evaluateWorld().bones().get(loaded.bones().get(1).id()).matrix());
  var second=new ExportPackage().publish(temp.resolve("再出口"),loaded,temp);assertEquals(1,new SkeletonJsonCodec().read(second).constraints().size());var own=temp.resolve("再导入.json");doc.savePortable(own);assertEquals(loaded,new ProjectCodec().read(own));
 }
 @Test void emptyAtlasCannotHideATexturedAttachmentOrChangeHistory()throws Exception{
  var input=new AtlasSubsetTest();input.temp=temp;var json=input.fixture("control");Files.write(json.resolveSibling("skeleton.atlas"),new byte[0]);var doc=new ProjectDocument(DocumentSnapshot.EMPTY);var before=doc.history().state();
  assertTrue(assertThrows(java.io.IOException.class,()->doc.importSkeleton(json)).getMessage().contains("Missing atlas region"));assertEquals(before,doc.history().state());assertEquals(0,doc.history().undoCount());assertFalse(doc.history().isDirty());assertTrue(doc.path().isEmpty());
 }
 @Test void nonemptyBlankAtlasAndStandaloneEmptySyntaxRemainRefused()throws Exception{
  assertThrows(java.io.IOException.class,()->AtlasSubset.validateSyntax("","empty.atlas"));var file=temp.resolve("skeleton.json");Files.writeString(file.resolveSibling("skeleton.atlas")," \t\r\n");assertTrue(assertThrows(java.io.IOException.class,()->AtlasSubset.read(file)).getMessage().contains("Atlas has no pages"));
 }
}
