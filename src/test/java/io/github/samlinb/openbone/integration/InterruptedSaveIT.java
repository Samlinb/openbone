package io.github.samlinb.openbone.integration;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class InterruptedSaveIT {
    @Test void abruptProcessTerminationBeforeReplaceLeavesOldFileIntactAndDiagnosticTemporary() throws Exception {
        Path output=Path.of("target/p2-interrupted-save").toAbsolutePath(); Files.createDirectories(output);
        Path target=output.resolve("原文件.json"),marker=output.resolve("temporary-path.txt");
        byte[] original="{\"oldDocument\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(target,original);
        List<String> command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),CrashSaveWorker.class.getName(),target.toString(),marker.toString());
        Process process=new ProcessBuilder(command).redirectOutput(output.resolve("stdout.log").toFile()).redirectError(output.resolve("stderr.log").toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Crash worker timed out"); }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("command.json").toFile(),Map.of("arguments",command,"exitCode",process.exitValue(),"expectedExitCode",73));
        assertEquals(73,process.exitValue()); assertArrayEquals(original,Files.readAllBytes(target));
        Path orphan=Path.of(Files.readString(marker)); assertTrue(Files.exists(orphan));
        assertEquals("{\"completeTemporary\":true}",Files.readString(orphan));
        // A crash cannot execute finally cleanup. Preserve this orphan as evidence, not as a project.
    }
}
