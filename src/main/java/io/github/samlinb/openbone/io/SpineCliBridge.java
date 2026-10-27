package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.github.samlinb.openbone.i18n.M.m;

/** Optional native-project conversion through a separately installed, licensed Spine CLI. */
public final class SpineCliBridge {
    private SpineCliBridge() {}
    public static boolean configured(){return !System.getProperty("openbone.spine.command","").isBlank()||!System.getenv().getOrDefault("OPENBONE_SPINE_COMMAND","").isBlank();}
    public static Path requireAvailable()throws IOException {
        String value=System.getProperty("openbone.spine.command",System.getenv().getOrDefault("OPENBONE_SPINE_COMMAND",""));
        if(value.isBlank())throw new ProjectFormatException(m("spineNative.cliRequired"));
        Path command=Path.of(value).toAbsolutePath().normalize();
        if(!Files.isRegularFile(command)||command.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bat")||command.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cmd"))
            throw new ProjectFormatException(m("spineNative.invalidCommand",command));
        return command;
    }
    public static Path exportProject(Path source,Path output,String version)throws IOException {
        Files.createDirectories(output);run(List.of("-u",version,"-i",source.toAbsolutePath().toString(),"-o",output.toAbsolutePath().toString(),"--export","json"));
        List<Path> files;try(var entries=Files.list(output)){files=entries.filter(p->p.getFileName().toString().endsWith(".json")).toList();}
        if(files.size()!=1)throw new ProjectFormatException(m("spineNative.multipleSkeletons",files.size()));
        byte[] bytes=ResourcePaths.bytes(files.get(0),ProjectCodec.MAX_BYTES);
        if(!SpineImportPolicy.isJsonExport(bytes))throw new ProjectFormatException(m("spineNative.invalidOutput"));
        String actual=new ProjectCodec().parseTree(bytes).path("skeleton").path("spine").asText();
        if(!actual.equals(version))throw new ProjectFormatException(m("spineNative.outputVersion",version,actual));
        return files.get(0);
    }
    public static void importProject(Path source,Path target,String version)throws IOException {
        if(Files.exists(target))throw new IOException(m("spineNative.targetExists",target));
        run(List.of("-u",version,"-i",source.toAbsolutePath().toString(),"-o",target.toAbsolutePath().toString(),"--import"));
        if(!Files.isRegularFile(target))throw new ProjectFormatException(m("spineNative.invalidOutput"));
        var header=SpineNativeHeader.read(ResourcePaths.bytes(target,64*1024*1024));
        if(!header.version().equals(version))throw new ProjectFormatException(m("spineNative.outputVersion",version,header.version()));
    }
    private static void run(List<String> arguments)throws IOException {
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Spine CLI must run outside EDT");
        Path command=requireAvailable(),log=Files.createTempFile("openbone-spine-cli-",".log");
        var invocation=new ArrayList<String>();invocation.add(command.toString());invocation.addAll(arguments);
        Process process=null;
        try {
            process=new ProcessBuilder(invocation).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException(m("spineNative.timeout"));}
            String output;try(var input=Files.newInputStream(log)){output=new String(input.readNBytes(16384),java.nio.charset.StandardCharsets.UTF_8);}
            if(process.exitValue()!=0||output.contains(" Trial")||output.contains("SpineTrial"))throw new ProjectFormatException(m("spineNative.cliFailed",process.exitValue(),output));
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException(m("spineNative.cancelled"),cancelled);}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();try{Files.deleteIfExists(log);}catch(IOException cleanup){log.toFile().deleteOnExit();}}
    }
}
