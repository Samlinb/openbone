package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryStoreTest {
    @TempDir Path root;
    @Test void activeSessionsExcludedAndOriginalUntouched() throws Exception {
        Path original=root.resolve("原工程.json");Files.writeString(original,"saved bytes");
        Path file;
        try(var store=new RecoveryStore(root.resolve("recovery"))){store.snapshot(DocumentSnapshot.EMPTY,original,root);file=store.file();assertTrue(RecoveryStore.candidates(root.resolve("recovery")).isEmpty());assertEquals("saved bytes",Files.readString(original));}
        var candidates=RecoveryStore.candidates(root.resolve("recovery"));assertEquals(1,candidates.size());
        try(var c=candidates.get(0)){assertEquals(DocumentSnapshot.EMPTY,c.model);assertEquals(original.toString(),c.originalPath);assertTrue(RecoveryStore.candidates(root.resolve("recovery")).isEmpty());c.discard();}
        assertFalse(Files.exists(file));assertEquals("saved bytes",Files.readString(original));
    }
    @Test void concurrentDocumentsHaveIndependentIdentity() throws Exception {
        try(var a=new RecoveryStore(root);var b=new RecoveryStore(root)){a.snapshot(DocumentSnapshot.EMPTY,root.resolve("甲.json"),root);b.snapshot(DocumentSnapshot.EMPTY,root.resolve("乙.json"),root);assertNotEquals(a.file(),b.file());}
        var cs=RecoveryStore.candidates(root);try{assertEquals(2,cs.size());assertEquals(Set.of(root.resolve("甲.json").toString(),root.resolve("乙.json").toString()),new HashSet<>(cs.stream().map(c->c.originalPath).toList()));}finally{for(var c:cs)c.close();}
    }
    @Test void failedAtomicReplacePreservesLastCompleteSnapshotAndCleansTemp() throws Exception {
        var fail=new boolean[]{false};
        var atomic=new AtomicProjectFile((stage,temp)->{if(fail[0]&&stage==AtomicProjectFile.Stage.BEFORE_REPLACE)throw new java.io.IOException("forced IO failure");},(a,b)->Files.move(a,b,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
        try(var store=new RecoveryStore(root,atomic)){store.snapshot(DocumentSnapshot.EMPTY,null,root);byte[] before=Files.readAllBytes(store.file());fail[0]=true;assertThrows(java.io.IOException.class,()->store.snapshot(DocumentSnapshot.EMPTY,root.resolve("new.json"),root));assertArrayEquals(before,Files.readAllBytes(store.file()));try(var files=Files.list(store.file().getParent())){assertEquals(2,files.count());}}
    }
    @Test void truncatedUnknownAndTamperedRemainDiagnosable() throws Exception {
        var mapper=new ObjectMapper();for(String type:List.of("truncated","unknown","tamper","extra","duplicate","trailing")){
            Path file;try(var s=new RecoveryStore(root)){s.snapshot(DocumentSnapshot.EMPTY,null,root);file=s.file();}
            var n=(ObjectNode)mapper.readTree(Files.readAllBytes(file));String text;
            switch(type){case "truncated"->text="{\"recoveryVersion\":";case "unknown"->{n.put("recoveryVersion",99);text=n.toString();}case "tamper"->{n.put("projectSHA256","bad");text=n.toString();}case "extra"->{n.put("future",1);text=n.toString();}case "duplicate"->text=n.toString().replace("\"recoveryVersion\":1","\"recoveryVersion\":1,\"recoveryVersion\":1");default->text=n+" {}";}
            Files.writeString(file,text);var cs=RecoveryStore.candidates(root);try{var c=cs.stream().filter(x->x.file.equals(file)).findFirst().orElseThrow();assertNull(c.model,type);assertNotNull(c.error,type);assertTrue(Files.exists(file));c.discard();}finally{for(var c:cs)c.close();}
        }
    }
    @Test void legacySchemaCanRecoverAndOrphanTempsAreBounded() throws Exception {
        Path file;try(var s=new RecoveryStore(root)){s.snapshot(DocumentSnapshot.EMPTY,null,root);file=s.file();}
        var mapper=new ObjectMapper();var n=(ObjectNode)mapper.readTree(Files.readAllBytes(file));n.set("project",mapper.readTree("{\"schemaVersion\":1,\"bones\":[],\"slots\":[],\"attachments\":[]}"));Files.writeString(file,n.toString());Files.writeString(file.getParent().resolve(".openbone-abandoned.tmp"),"partial");
        var cs=RecoveryStore.candidates(root);try{assertEquals(DocumentSnapshot.EMPTY,cs.get(0).model);assertFalse(Files.exists(file.getParent().resolve(".openbone-abandoned.tmp")));}finally{for(var c:cs)c.close();}
    }
    @Test void normalExitClearCannotDeleteAnotherSession() throws Exception {
        Path retained;try(var a=new RecoveryStore(root);var b=new RecoveryStore(root)){a.snapshot(DocumentSnapshot.EMPTY,null,root);b.snapshot(DocumentSnapshot.EMPTY,null,root);retained=b.file();a.clear();assertTrue(Files.exists(retained));}
        var cs=RecoveryStore.candidates(root);try{assertEquals(1,cs.size());}finally{for(var c:cs)c.close();}
    }
}
