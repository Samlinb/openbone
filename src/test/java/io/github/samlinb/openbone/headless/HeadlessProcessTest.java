package io.github.samlinb.openbone.headless;

import io.github.samlinb.openbone.EditorApplication;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessProcessTest {
    @TempDir Path temp;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void commandLineStartsWithoutLoadingDesktopOrGlAndStopsCleanly(boolean isolated) throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {port=socket.getLocalPort();}
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        Path log=temp.resolve("service.log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Djava.awt.headless=true","-Dopenbone.lang=en","-Xlog:class+load=info:file=classes.log",
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                EditorApplication.class.getName(),"--headless","--port",Integer.toString(port))
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("OPENBONE_MCP_TOKEN");builder.environment().remove("OPENBONE_PREVIEW_JAR");
        if (isolated) {
            Path root=Files.createDirectory(temp.resolve("workspace"));
            Path config=temp.resolve("projects.json");
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(config.toFile(),java.util.Map.of("projects",java.util.List.of(
                    java.util.Map.of("id","alice","root",root.toString(),"tokenEnv","TEST_OPENBONE_ALICE"))));
            builder.command().addAll(java.util.List.of("--projects",config.toString()));
            builder.environment().put("TEST_OPENBONE_ALICE",ProjectIsolationTest.A);
        }
        Process process=builder.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.readString(log).contains("Headless MCP started:")) {
                assertTrue(process.isAlive(),Files.readString(log));
                assertTrue(System.nanoTime()<deadline,Files.readString(log));Thread.sleep(25);
            }
            var requestBuilder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp"+(isolated?"/alice":"")));
            if (isolated) requestBuilder.header("Authorization","Bearer "+ProjectIsolationTest.A);
            var request=requestBuilder
                    .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"openbone_get_context\",\"arguments\":{}}}"))
                    .timeout(java.time.Duration.ofSeconds(5)).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("headless"));
            String classes=Files.readString(temp.resolve("classes.log"));
            assertFalse(classes.contains("io.github.samlinb.openbone.desktop.EditorFrame"));
            assertFalse(classes.contains("com.badlogic.gdx.backends.lwjgl"));
            assertFalse(classes.contains("org.lwjgl.opengl"));
        } finally {
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();
        }
        assertFalse(process.isAlive());
        // Windows may report process exit just before redirected file handles are released.
        for(int i=0;i<40;i++) {
            try { Files.deleteIfExists(temp.resolve("classes.log"));Files.deleteIfExists(log);break; }
            catch(java.io.IOException releasingHandles) {Thread.sleep(50);}
        }
    }
}
