package io.github.samlinb.openbone.integration;

import java.nio.file.*;
import io.github.samlinb.openbone.io.AtomicProjectFile;

/** Test subprocess: halt after a real forced temp write, before rename; never run inside test JVM. */
public final class CrashSaveWorker {
    public static void main(String[] args) throws Exception {
        var writer=new AtomicProjectFile((stage,temp)-> {
            if(stage==AtomicProjectFile.Stage.AFTER_FORCE) {
                Files.writeString(Path.of(args[1]),temp.toString());
                Runtime.getRuntime().halt(73);
            }
        },(source,target)->Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
        writer.write(Path.of(args[0]),"{\"completeTemporary\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        throw new AssertionError("Crash checkpoint was not reached");
    }
}
