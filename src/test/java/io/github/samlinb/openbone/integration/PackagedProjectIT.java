package io.github.samlinb.openbone.integration;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.*;
import static io.github.samlinb.openbone.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedProjectIT {
    private static final String VERSION=System.getProperty("project.version","0.1.0-SNAPSHOT");
    private final Path output=Path.of("target/p2-integration").toAbsolutePath();
    private final Path jar=Path.of("target/openbone-"+VERSION+".jar").toAbsolutePath();
    private final ObjectMapper mapper=new ObjectMapper();
    private final ArrayNode commands=mapper.createArrayNode();
    private String run(String name,int expectedCode,String... arguments) throws Exception {
        Files.createDirectories(output.resolve("中文 空目录"));
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-jar",jar.toString()));
        command.addAll(List.of(arguments)); Path stdout=output.resolve(name+".stdout.log"), stderr=output.resolve(name+".stderr.log");
        long started=System.nanoTime();
        Process process=new ProcessBuilder(command).directory(output.resolve("中文 空目录").toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean finished=process.waitFor(30,TimeUnit.SECONDS);
        if(!finished) { process.destroyForcibly().waitFor(); fail("Packaged JVM timed out"); }
        ObjectNode entry=commands.addObject(); entry.put("name",name); entry.set("arguments",mapper.valueToTree(command)); entry.put("cwd",output.resolve("中文 空目录").toString());
        entry.put("exitCode",process.exitValue()); entry.put("durationMs",(System.nanoTime()-started)/1_000_000.0);
        Files.writeString(output.resolve(name+".exitcode"),process.exitValue()+"\n");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("commands.json").toFile(),commands);
        assertEquals(expectedCode,process.exitValue(),Files.readString(stderr));
        return Files.readString(stdout);
    }
    @Test void jarReadsSavedModelsInFreshJvmsWithoutReferenceProjectOrWorkingDirectoryDependencies() throws Exception {
        List<DocumentSnapshot> models=new ArrayList<>(List.of(DocumentSnapshot.EMPTY,hierarchy(),
                AnimationEdits.put(hierarchy(),io.github.samlinb.openbone.core.animation.AnimationClip.DEFAULT_ID,id(3),io.github.samlinb.openbone.core.animation.Channel.ROTATE,.123456789,List.of(720.)),
                ModelEdits.editBone(hierarchy(),id(1),b->b.withLocal(new Transform(5,-10,720,0,-2))),
                ModelEdits.editBone(hierarchy(),id(1),b->b.withLocal(new Transform(5,-10,-720,1e-14,-2)))));
        var physical=hierarchy().withConstraints(List.of(new PhysicsConstraint(id(99),"spring",0,true,id(2),List.of(1.,0.,0.,0.,0.),60,List.of(0.,100.,.9,1.,0.,0.,1.))));models.add(physical);
        models.add(AnimationEdits.putConstraint(physical,io.github.samlinb.openbone.core.animation.AnimationClip.DEFAULT_ID,id(99),io.github.samlinb.openbone.core.animation.ConstraintChannel.PHYSICS_WIND,1,List.of(.1)));
        var extended=ExtendedEdits.addSkin(hierarchy(),"red");
        extended=ExtendedEdits.bind(extended,"red",new RegionAttachment(id(25),"图片",id(10),"red.png",Transform.IDENTITY,64,48),null);
        extended=ExtendedEdits.skin(extended,"red");var clip=extended.animations().get(0).id();
        extended=ExtendedEdits.attachment(extended,clip,id(10),.5,null);
        extended=ExtendedEdits.drawOrder(extended,clip,1,List.of(id(11),id(10)));
        extended=ExtendedEdits.eventDefinition(extended,new EventDefinition(id(100),"事件",7,.25,"中文","",1,0));
        extended=ExtendedEdits.event(extended,clip,new io.github.samlinb.openbone.core.animation.DiscreteTracks.EventKey(id(101),1,id(100),42,.5,"键",1,0));models.add(extended);
        Files.createDirectories(output); var codec=new ProjectCodec(); int i=0;
        for(var model:models) {
            Path project=output.resolve("中文 工程 "+i+".json");
            var document=new ProjectDocument(model); document.history().select(model.bones().isEmpty()?Selection.EMPTY:Selection.of(id(3)));
            document.save(project); String canonical=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codec.encode(model)));
            String result=run("read-"+i,0,"--validate-project",project.toString());
            assertEquals("schemaVersion="+mapper.readTree(codec.encode(model)).path("schemaVersion").asInt()+" bones="+model.bones().size()+" slots="+model.slots().size()+" attachments="+model.attachments().size()+" canonicalSHA256="+canonical,result.trim());
            i++;
        }
        assertEquals("Openbone "+VERSION,run("version",0,"--version").trim());
        Path bad=output.resolve("未知版本.json"); Files.writeString(bad,"{\"schemaVersion\":99,\"bones\":[],\"slots\":[],\"attachments\":[]}");
        run("bad-project",2,"--validate-project",bad.toString()); assertTrue(Files.readString(output.resolve("bad-project.stderr.log")).contains("schemaVersion"));
        run("missing-project",2,"--validate-project",output.resolve("不存在.json").toString());
        run("bad-arguments",2,"--invalid");
        try(JarFile archive=new JarFile(jar.toFile())) {
            var names=archive.stream().map(e->e.getName()).toList();
            assertTrue(names.contains("io/github/samlinb/openbone/core/model/DocumentSnapshot.class"));
            assertTrue(names.contains("com/fasterxml/jackson/databind/ObjectMapper.class"));
            // P3 deliberately adds the renderer to the desktop distribution. Core stays independent;
            // official runtime, probes and external oracle samples still must never enter the product.
            assertTrue(names.contains("com/badlogic/gdx/backends/lwjgl/LwjglAWTCanvas.class"));
            assertTrue(names.contains("gdx64.dll"));assertTrue(names.contains("lwjgl64.dll"));
            assertFalse(names.stream().anyMatch(n->n.startsWith("com/esotericsoftware/")||n.startsWith("probe/")||n.contains("oracle/")));
            for(String name:names)if(name.startsWith("io/github/samlinb/openbone/core/")&&name.endsWith(".class")){
                String binary=new String(archive.getInputStream(archive.getJarEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                for(String forbidden:List.of("java/awt/","javax/swing/","com/badlogic/","com/esotericsoftware/","com/fasterxml/","/viewport/","/desktop/"))assertFalse(binary.contains(forbidden),name+" references "+forbidden);
            }
            String notices=new String(archive.getInputStream(archive.getJarEntry("META-INF/NOTICE")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notices.contains("FastDoubleParser")); assertTrue(notices.contains("MIT"));
        }
    }
}
