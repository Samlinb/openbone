package io.github.samlinb.openbone.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import io.github.samlinb.openbone.core.model.DocumentSnapshot;
import static org.junit.jupiter.api.Assertions.*;

/** P9-IND-01: real NIO locks, no GC or retry sleeps; faults only at filesystem boundaries. */
class RecoveryIsolationTest {
    @TempDir Path root;
    private Path saved(int n) throws Exception {
        Path created;try(var store=new RecoveryStore(root)){store.snapshot(DocumentSnapshot.EMPTY,root.resolve("original-"+n+".json"),root);created=store.file().getParent();}
        return Files.move(created,root.resolve(String.format("%08d-0000-0000-0000-000000000001",n)));
    }
    private static void close(List<RecoveryStore.Candidate> candidates) throws Exception {for(var c:candidates)c.close();}
    private static void unlocked(Path dir) throws Exception {try(var channel=FileChannel.open(dir.resolve("session.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()){assertNotNull(lock);}}
    private static String bytes(Path dir) throws Exception {return RecoveryStore.hash(Files.readAllBytes(dir.resolve("recovery.json")));}
    private void repeat(int expected) throws Exception {for(int i=0;i<3;i++){var candidates=RecoveryStore.candidates(root,issue->{});try{assertEquals(expected,candidates.size());}finally{close(candidates);}}}

    @Test void badLockDoesNotHideValidCandidateOrRequireGc() throws Exception {
        Path good=saved(1),bad=root.resolve("ffffffff-ffff-ffff-ffff-ffffffffffff");Files.createDirectories(bad.resolve("session.lock"));
        String before=bytes(good);var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var first=RecoveryStore.candidates(root,issues::add);try{assertEquals(1,first.size());assertEquals(1,issues.size());assertEquals(bad,issues.get(0).path());assertTrue(RecoveryStore.candidates(root,issue->{}).isEmpty());}finally{close(first);}
        repeat(1);assertEquals(before,bytes(good));assertTrue(Files.isDirectory(bad.resolve("session.lock")));
        Files.delete(bad.resolve("session.lock"));Files.delete(bad);repeat(1);
    }
    @Test void badTempDirectoryIsRetainedAndItsSnapshotStillOffered() throws Exception {
        Path good=saved(1),other=saved(2),temp=Files.createDirectory(other.resolve(".openbone-bad.tmp"));Files.writeString(temp.resolve("user-data"),"keep");
        var issues=new ArrayList<RecoveryStore.ScanIssue>();var cs=RecoveryStore.candidates(root,issues::add);try{assertEquals(2,cs.size());assertEquals(temp,issues.get(0).path());}finally{close(cs);}
        repeat(2);assertEquals("keep",Files.readString(temp.resolve("user-data")));unlocked(good);unlocked(other);
    }
    @Test void deniedTempDeletionDoesNotSkipSameSessionSnapshot() throws Exception {
        saved(1);Path dir=saved(2),temp=Files.writeString(dir.resolve(".openbone-denied.tmp"),"partial");String before=bytes(dir);
        var io=new RecoveryStore.ScanIo(){@Override public void delete(Path p)throws IOException{if(p.equals(temp))throw new AccessDeniedException(p.toString());RecoveryStore.ScanIo.super.delete(p);}};
        for(int n=0;n<3;n++){var issues=new ArrayList<RecoveryStore.ScanIssue>();var cs=RecoveryStore.candidates(root,issues::add,io);try{assertEquals(2,cs.size());assertEquals(temp,issues.get(0).path());}finally{close(cs);}}
        assertTrue(Files.exists(temp));assertEquals(before,bytes(dir));unlocked(dir);
    }
    @Test void deniedTempEnumerationDoesNotSkipSameSessionSnapshot() throws Exception {
        saved(1);Path dir=saved(2);var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){@Override public DirectoryStream<Path> temps(Path p)throws IOException{if(p.equals(dir))throw new AccessDeniedException(p.toString());return RecoveryStore.ScanIo.super.temps(p);}});
        try{assertEquals(2,cs.size());assertEquals(dir,issues.get(0).path());}finally{close(cs);}repeat(2);
    }
    @Test void deniedLockWriteIsPerSessionAndPreservesValidBytes() throws Exception {
        Path good=saved(1),denied=saved(2);String a=bytes(good),b=bytes(denied);var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){@Override public FileChannel openLock(Path p)throws IOException{if(p.getParent().equals(denied))throw new AccessDeniedException(p.toString());return RecoveryStore.ScanIo.super.openLock(p);}});
        try{assertEquals(1,cs.size());assertEquals(denied,issues.get(0).path());}finally{close(cs);}repeat(2);assertEquals(a,bytes(good));assertEquals(b,bytes(denied));
    }
    @Test void tryLockFailureClosesJustOpenedChannel() throws Exception {
        Path good=saved(1),bad=saved(2);var channels=new ArrayList<FileChannel>();var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){
            @Override public FileChannel openLock(Path p)throws IOException{var c=RecoveryStore.ScanIo.super.openLock(p);channels.add(c);return c;}
            @Override public FileLock tryLock(FileChannel c)throws IOException{if(channels.size()==2)throw new IOException("forced lock I/O");return c.tryLock();}
        });
        try{assertEquals(1,cs.size());assertFalse(channels.get(1).isOpen());unlocked(bad);}finally{close(cs);}assertFalse(channels.get(0).isOpen());repeat(2);
    }
    @Test void deniedSnapshotAttributesReleasesCurrentSession() throws Exception {
        saved(1);Path denied=saved(2);var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){@Override public BasicFileAttributes attributes(Path p)throws IOException{if(p.equals(denied.resolve("recovery.json")))throw new AccessDeniedException(p.toString());return RecoveryStore.ScanIo.super.attributes(p);}});
        try{assertEquals(1,cs.size());assertEquals(denied,issues.get(0).path());unlocked(denied);}finally{close(cs);}repeat(2);
    }
    @Test void deniedSessionAttributesDoesNotHideEarlierValidSession() throws Exception {
        saved(1);Path denied=saved(2);var issues=new ArrayList<RecoveryStore.ScanIssue>();
        var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){@Override public BasicFileAttributes attributes(Path p)throws IOException{if(p.equals(denied))throw new AccessDeniedException(p.toString());return RecoveryStore.ScanIo.super.attributes(p);}});
        try{assertEquals(1,cs.size());assertEquals(denied,issues.get(0).path());}finally{close(cs);}repeat(2);
    }
    @Test void emptySessionIteratorFailureIsIsolatedAndClosesCurrentLock() throws Exception {
        saved(1);Path empty;try(var store=new RecoveryStore(root)){empty=store.file().getParent();}
        var issues=new ArrayList<RecoveryStore.ScanIssue>();var cs=RecoveryStore.candidates(root,issues::add,new RecoveryStore.ScanIo(){
            @Override public DirectoryStream<Path> contents(Path p){return new DirectoryStream<>(){public Iterator<Path> iterator(){throw new DirectoryIteratorException(new AccessDeniedException(p.toString()));}public void close(){}};}
        });
        try{assertEquals(1,cs.size());assertEquals(empty,issues.get(0).path());unlocked(empty);}finally{close(cs);}repeat(1);
    }
    @Test void globalStreamCloseFailureReleasesAllPublishedCandidates() throws Exception {
        Path a=saved(1),b=saved(2);
        assertThrows(UncheckedIOException.class,()->RecoveryStore.candidates(root,issue->{},new RecoveryStore.ScanIo(){@Override public Stream<Path> sessions(Path p)throws IOException{return RecoveryStore.ScanIo.super.sessions(p).onClose(()->{throw new UncheckedIOException(new IOException("forced stream close"));});}}));
        unlocked(a);unlocked(b);repeat(2);
    }
    @Test void diagnosticCallbackFailureReleasesEarlierAndCurrentSession() throws Exception {
        Path a=saved(1),b=saved(2);Files.createDirectory(b.resolve(".openbone-bad.tmp"));
        assertThrows(IllegalStateException.class,()->RecoveryStore.candidates(root,issue->{throw new IllegalStateException("forced diagnostic failure");}));
        unlocked(a);unlocked(b);repeat(2);
    }
    @Test void globalErrorReleasesEarlierAndCurrentSession() throws Exception {
        Path a=saved(1),b=saved(2);
        assertThrows(AssertionError.class,()->RecoveryStore.candidates(root,issue->{},new RecoveryStore.ScanIo(){@Override public DirectoryStream<Path> temps(Path p)throws IOException{if(p.equals(b))throw new AssertionError("forced global error");return RecoveryStore.ScanIo.super.temps(p);}}));
        unlocked(a);unlocked(b);repeat(2);
    }
    @Test void globalFailureAttemptsAllClosesEvenWhenLockReleaseFails() throws Exception {
        saved(1);saved(2);var channels=new ArrayList<FileChannel>();
        var failure=assertThrows(UncheckedIOException.class,()->RecoveryStore.candidates(root,issue->{},new RecoveryStore.ScanIo(){
            @Override public Stream<Path> sessions(Path p)throws IOException{return RecoveryStore.ScanIo.super.sessions(p).onClose(()->{throw new UncheckedIOException(new IOException("scan close"));});}
            @Override public FileChannel openLock(Path p)throws IOException{var c=RecoveryStore.ScanIo.super.openLock(p);channels.add(c);return c;}
            @Override public FileLock tryLock(FileChannel c)throws IOException{var actual=c.tryLock();return new FileLock(c,0,Long.MAX_VALUE,false){public boolean isValid(){return actual.isValid();}public void release()throws IOException{throw new IOException("forced release failure");}};}
        }));
        assertEquals(2,failure.getSuppressed().length);assertTrue(channels.stream().noneMatch(FileChannel::isOpen));repeat(2);
    }
    @Test void missingRootIsEmptyWithoutCreatingIt() throws Exception {Path absent=root.resolve("missing");assertTrue(RecoveryStore.candidates(absent).isEmpty());assertFalse(Files.exists(absent));}
    @Test void activeJvmAndCompetingClaimantStayExcluded() throws Exception {
        saved(1);String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        var peer=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),LockPeer.class.getName(),root.toString()).redirectErrorStream(true).start();
        try{
            var line=CompletableFuture.supplyAsync(()->{try(var br=new BufferedReader(new InputStreamReader(peer.getInputStream()))){String s;while((s=br.readLine())!=null){if("READY".equals(s))return s;}}catch(IOException e){throw new UncheckedIOException(e);}return null;}).get(5,TimeUnit.SECONDS);assertEquals("READY",line);
            var first=RecoveryStore.candidates(root);try{assertEquals(1,first.size());assertTrue(RecoveryStore.candidates(root).isEmpty());}finally{close(first);}
            peer.getOutputStream().write(1);peer.getOutputStream().flush();assertTrue(peer.waitFor(5,TimeUnit.SECONDS));assertEquals(0,peer.exitValue());repeat(2);
            System.out.println("Recovery lock peer pid="+peer.pid()+" exit="+peer.exitValue());
        }finally{if(peer.isAlive()){peer.destroyForcibly();peer.waitFor(5,TimeUnit.SECONDS);}}
    }
    public static class LockPeer {
        public static void main(String[] args)throws Exception{Path root=Path.of(args[0]);try(var store=new RecoveryStore(root)){store.snapshot(DocumentSnapshot.EMPTY,null,root);System.out.println("READY");System.out.flush();System.in.read();}}
    }
}
