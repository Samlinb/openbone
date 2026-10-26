package io.github.samlinb.openbone.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelTest {
    @Test void idsAreCanonicalStableAcrossRenameAndRecreate() {
        DocumentSnapshot d=hierarchy();
        var renamed=ModelEdits.editBone(d,id(1),b -> b.withName("新根"));
        assertEquals(id(1),renamed.bones().get(0).id()); assertEquals(id(1),renamed.bone(id(2)).parentId());
        var removed=ModelEdits.delete(d,id(1));
        var fresh=new Bone(EntityId.create(),"根骨",null,Transform.IDENTITY,0);
        assertNotEquals(id(1),fresh.id()); assertEquals(fresh,ModelEdits.addBone(removed,fresh).bone(fresh.id()));
        assertThrows(IllegalArgumentException.class,()->new EntityId("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,()->new EntityId("INVALID"));
        assertNotEquals(EntityId.create(),EntityId.create());
    }
    @Test void duplicateIdsAcrossKindsAndNamesAreRejected() {
        var d=hierarchy();
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addBone(d,bone(1,"new",null,Transform.IDENTITY)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addSlot(d,new Slot(id(1),"new",id(1),null)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addAttachment(d,new RegionAttachment(id(1),"new",id(10),"a",Transform.IDENTITY,1,1),false));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addBone(d,bone(99,"根骨",null,Transform.IDENTITY)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addSlot(d,new Slot(id(99),"前景",id(1),null)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addAttachment(d,new RegionAttachment(id(99),"图片",id(10),"a",Transform.IDENTITY,1,1),false));
        assertEquals(3,d.attachments().size()); // Same attachment name in different slots is permitted.
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editBone(d,id(1),b -> new Bone(id(99),b.name(),null,b.local(),b.length())));
    }
    @ParameterizedTest @ValueSource(strings={"", " "," name","name ","\n"})
    void invalidNames(String name) { assertThrows(IllegalArgumentException.class,()->bone(99,name,null,Transform.IDENTITY)); }
    @Test void allReferenceAndCycleFailures() {
        var d=hierarchy();
        assertThrows(IllegalArgumentException.class,()->ModelEdits.reparent(d,id(1),id(3),ModelEdits.ReparentPolicy.KEEP_LOCAL));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.reparent(d,id(2),id(2),ModelEdits.ReparentPolicy.KEEP_LOCAL));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.reparent(d,id(2),id(99),ModelEdits.ReparentPolicy.KEEP_LOCAL));
        assertThrows(IllegalArgumentException.class,()->new DocumentSnapshot(List.of(bone(1,"a",id(2),Transform.IDENTITY),bone(2,"b",id(1),Transform.IDENTITY)),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addBone(d,bone(99,"new",id(98),Transform.IDENTITY)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addSlot(d,new Slot(id(99),"new",id(98),null)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.addAttachment(d,new RegionAttachment(id(99),"new",id(98),"a",Transform.IDENTITY,1,1),false));
        assertThrows(IllegalArgumentException.class,()->new DocumentSnapshot(d.bones(),List.of(new Slot(id(10),"s",id(1),id(99))),List.of()));
        assertThrows(IllegalArgumentException.class,()->new DocumentSnapshot(d.bones(),List.of(new Slot(id(10),"s",id(1),id(22)),d.slots().get(1)),d.attachments()));
    }
    @Test void topologicalOrderIsDeterministicAndDeepTreesAreIterative() {
        List<Bone> bones=new ArrayList<>();
        for(int i=3000;i>0;i--) bones.add(bone(i,"b"+i,i==1?null:id(i-1),Transform.IDENTITY));
        var d=new DocumentSnapshot(bones,List.of(),List.of());
        assertEquals(id(1),d.bones().get(0).id()); assertEquals(id(3000),d.bones().get(2999).id());
        assertEquals(3000,d.evaluateWorld().bones().size());
        assertEquals(DocumentSnapshot.EMPTY,ModelEdits.delete(d,id(1)));
    }
    @Test void immutableCollectionsAndKindBoundary() {
        var list=new ArrayList<Bone>(); list.add(bone(1,"a",null,Transform.IDENTITY));
        var d=new DocumentSnapshot(list,List.of(),List.of()); list.clear(); assertEquals(1,d.bones().size());
        assertThrows(UnsupportedOperationException.class,()->d.bones().clear());
        var world=hierarchy().evaluateWorld(); assertThrows(UnsupportedOperationException.class,()->world.bones().clear());
        assertThrows(UnsupportedOperationException.class,()->world.regions().get(id(20)).vertices().clear());
        assertTrue(Attachment.class.isSealed());
    }
    @Test void deletesCascadeOnlyOwnedReferencesAndClearActive() {
        var d=hierarchy(); var deleted=ModelEdits.delete(d,id(2));
        assertEquals(Set.of(id(1),id(4)),new HashSet<>(deleted.bones().stream().map(Bone::id).toList()));
        assertEquals(List.of(d.slots().get(1)),deleted.slots()); assertEquals(List.of(d.attachments().get(2)),deleted.attachments());
        var attachment=ModelEdits.delete(d,id(20)); assertNull(attachment.slots().get(0).activeAttachmentId()); assertEquals(2,attachment.attachments().size());
        var inactive=ModelEdits.delete(d,id(21)); assertEquals(id(20),inactive.slots().get(0).activeAttachmentId());
        var slot=ModelEdits.delete(d,id(10)); assertEquals(1,slot.attachments().size()); assertEquals(4,slot.bones().size());
        assertThrows(IllegalArgumentException.class,()->ModelEdits.delete(d,id(99)));
    }
    @Test void regionGeometryAndPoseOverrideNeverChangeSetup() {
        var d=hierarchy(); var baseline=d.evaluateWorld();
        var posed=d.evaluateWorld(Map.of(id(1),Transform.IDENTITY));
        assertNotEquals(baseline.bones().get(id(3)),posed.bones().get(id(3)));
        assertEquals(baseline,d.evaluateWorld());
        var region=baseline.regions().get(id(20)); var expected=baseline.bones().get(id(3)).matrix().multiply(((RegionAttachment)d.attachments().get(0)).local().matrix());
        assertEquals(expected,region.matrix()); assertEquals(expected.apply(-32,-24),region.vertices().get(0));
        assertThrows(IllegalArgumentException.class,()->d.evaluateWorld(Map.of(id(99),Transform.IDENTITY)));
    }
    @ParameterizedTest @ValueSource(doubles={Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
    void nonfiniteValuesNeverEnterModel(double bad) {
        assertThrows(IllegalArgumentException.class,()->new Transform(bad,0,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new Transform(0,bad,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new Transform(0,0,bad,1,1));
        assertThrows(IllegalArgumentException.class,()->new Transform(0,0,0,bad,1));
        assertThrows(IllegalArgumentException.class,()->new Transform(0,0,0,1,bad));
        assertThrows(IllegalArgumentException.class,()->new Bone(id(1),"a",null,Transform.IDENTITY,bad));
        assertThrows(IllegalArgumentException.class,()->new RegionAttachment(id(20),"a",id(10),"a",Transform.IDENTITY,bad,1));
        assertThrows(IllegalArgumentException.class,()->new RegionAttachment(id(20),"a",id(10),"a",Transform.IDENTITY,1,bad));
    }
    @Test void dimensionsLengthAndWorldOverflowRejected() {
        assertThrows(IllegalArgumentException.class,()->new Bone(id(1),"a",null,Transform.IDENTITY,-1));
        assertThrows(IllegalArgumentException.class,()->new RegionAttachment(id(20),"a",id(10),"a",Transform.IDENTITY,0,1));
        assertThrows(IllegalArgumentException.class,()->new RegionAttachment(id(20),"a",id(10)," ",Transform.IDENTITY,1,1));
        assertThrows(IllegalArgumentException.class,()->new DocumentSnapshot(List.of(bone(1,"a",null,new Transform(0,0,0,1e200,1)),bone(2,"b",id(1),new Transform(0,0,0,1e200,1))),List.of(),List.of()));
        assertDoesNotThrow(()->new Transform(0,0,Double.MAX_VALUE,1,1).matrix());
    }
    @Test void slotAndRegionRenameKeepIdsAndReferencesAndValidateNamePolicies() {
        var original=hierarchy();
        var renamedSlot=ModelEdits.editSlot(original,id(10),s->new Slot(s.id(),"改名插槽",s.boneId(),s.activeAttachmentId()));
        var renamed=ModelEdits.editRegion(renamedSlot,id(20),r->new RegionAttachment(r.id(),"改名附件",r.slotId(),r.source(),r.local(),r.width(),r.height()));
        assertEquals(id(20),renamed.slots().get(0).activeAttachmentId()); assertEquals(id(10),renamed.attachments().get(0).slotId());
        assertEquals(original.evaluateWorld(),renamed.evaluateWorld());
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editSlot(original,id(10),s->new Slot(id(99),s.name(),s.boneId(),null)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editRegion(original,id(20),r->new RegionAttachment(id(99),r.name(),r.slotId(),r.source(),r.local(),r.width(),r.height())));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editSlot(original,id(10),s->new Slot(s.id(),"other",s.boneId(),null)));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editRegion(original,id(20),r->new RegionAttachment(r.id(),"inactive",r.slotId(),r.source(),r.local(),r.width(),r.height())));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editSlot(original,id(99),s->s));
        assertThrows(IllegalArgumentException.class,()->ModelEdits.editRegion(original,id(99),r->r));
        var deleted=ModelEdits.delete(original,id(20));
        var recreated=ModelEdits.addAttachment(deleted,new RegionAttachment(EntityId.create(),"图片",id(10),"new.png",Transform.IDENTITY,1,1),false);
        assertNull(recreated.slots().get(0).activeAttachmentId()); // No automatic rebinding by reused name.
    }
}
