package io.github.samlinb.openbone.desktop;

import com.fasterxml.jackson.databind.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import io.github.samlinb.openbone.mcp.EditorMcpServer;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual visible desktop + HTTP + production GL capture, independent of OS screenshots. */
@EnabledIfSystemProperty(named = "openbone.verifyGl", matches = "true")
class McpDesktopTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    EditorFrame frame;
    String url, token;
    int sequence;

    @Test void stretchymanOfficialExportLoadsDeformsRendersEditsAndUndoesThroughMcp() throws Exception {
        String oldState=System.getProperty("openbone.userState"),oldPort=System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState",temp.resolve("stretchyman-state").toString());
        System.setProperty("openbone.mcp.port","0");
        McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        Path source=Path.of(System.getProperty("openbone.stretchymanSource",
                "src/test/resources/spine43/stretchyman/stretchyman-pro.json")).toAbsolutePath();
        byte[] original=Files.readAllBytes(source);
        Path output=Path.of("target/stretchyman-validation").toAbsolutePath();Files.createDirectories(output);
        try {
            edt(()->{frame=new EditorFrame(null);frame.setSize(1200,850);frame.showEditor();return null;});
            awaitMcpStarted();
            edt(()->{url=((EditorMcpServer)field("mcpServer")).url();token=(String)field("mcpToken");return null;});
            String before=data(call("openbone_get_context",Map.of())).path("revision").asText();
            var args=new LinkedHashMap<String,Object>(Map.of("path",source.toString(),"expectedRevision",before,
                    "requestId","stretchyman-risk","dirtyPolicy","reject"));
            var risk=rpc("tools/call",Map.of("name","openbone_load_project","arguments",args)).path("result");
            assertEquals("SPINE_VERSION_RISK",risk.path("structuredContent").path("code").asText());
            assertEquals(before,data(call("openbone_get_context",Map.of())).path("revision").asText());
            args.put("versionRiskApproval",json.convertValue(risk.path("structuredContent").path("versionRiskApproval"),Map.class));
            args.put("requestId","stretchyman-approved");
            var loaded=data(call("openbone_load_project",args));
            assertEquals(loaded,data(call("openbone_load_project",args)));
            var model=edt(()->frame.history().state().model());
            assertEquals(55,model.bones().size());assertEquals(10,model.constraints().size());
            var paths=data(call("openbone_query_scene",Map.of("kind","attachments")));
            assertEquals(9,paths.path("total").asInt());
            int weighted=0;
            for(var item:paths.path("items"))if(item.path("weighted").asBoolean()) {
                weighted++;assertEquals(item.path("vertexCount").asInt(),item.path("weights").size());
                assertTrue(item.path("deformSize").asInt()>0);
            }
            assertEquals(4,weighted);
            String revision=edt(()->frame.history().revision());
            var clip=model.animations().stream().filter(a->a.name().equals("sneak")).findFirst().orElseThrow();
            var nonBone=data(call("openbone_query_scene",Map.of("kind","keyframes","animationId",clip.id().value(),
                    "trackType","non_bone","expectedRevision",revision,"limit",200)));
            assertEquals(33,nonBone.path("total").asInt());
            assertEquals(33,nonBone.path("items").size());
            assertTrue(nonBone.path("items").findValuesAsText("trackType").contains("deform"));
            assertEquals(revision,edt(()->frame.history().revision()));
            assertEquals(model,edt(()->frame.history().state().model()));
            var shots=new ArrayList<BufferedImage>();
            for(double time:List.of(0.,.6,1.3)) {
                var capture=call("openbone_capture_preview",Map.of("animationId",clip.id().value(),"time",time,
                        "expectedRevision",revision,"fit",true,"showBones",false,"showGrid",false));
                assertEquals(time,data(capture).path("time").asDouble());
                var pixels=image(capture);shots.add(pixels);
                int foreground=0,background=pixels.getRGB(0,0);
                for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)if(pixels.getRGB(x,y)!=background)foreground++;
                assertTrue(foreground>1000,"Actual character textures must render");
                ImageIO.write(pixels,"PNG",output.resolve("sneak-"+time+".png").toFile());
            }
            int changed=0;
            for(int y=0;y<shots.get(0).getHeight();y++)for(int x=0;x<shots.get(0).getWidth();x++)
                if(shots.get(0).getRGB(x,y)!=shots.get(1).getRGB(x,y))changed++;
            assertTrue(changed>500,"Weighted path animation must change viewport pixels");
            var authoredId=EntityId.create();
            var armIk=model.constraints().stream().filter(c->c.name().equals("front-arm-ik")).findFirst().orElseThrow();
            var legPath=(PathAttachment)model.attachments().stream().filter(a->a.name().equals("front-leg-path")).findFirst().orElseThrow();
            var correction=new ArrayList<Double>(Collections.nCopies(legPath.deformSize(),0.));correction.set(7,12.);
            var authored=data(call("openbone_apply_edits",Map.of("expectedRevision",revision,"requestId","author-non-bone",
                    "edits",List.of(Map.of("op","create_animation","animationId",authoredId.value(),"name","authored-regression"),
                            Map.of("op","put_constraint_keyframe","animationId",authoredId.value(),"constraintId",armIk.id().value(),
                                    "channel","IK","time",0,"values",List.of(0.,0.,1.,0.,0.)),
                            Map.of("op","put_deform_keyframe","animationId",authoredId.value(),"attachmentId",legPath.id().value(),
                                    "time",0,"values",correction)))));
            assertEquals(1,authored.path("undoCount").asInt());
            var authoredPreview=call("openbone_capture_preview",Map.of("animationId",authoredId.value(),"time",0,
                    "expectedRevision",authored.path("revision").asText(),"fit",true,"showBones",false,"showGrid",false));
            assertEquals(authoredId.value(),data(authoredPreview).path("animationId").asText());
            assertTrue(image(authoredPreview).getWidth()>0);
            call("openbone_undo",Map.of("expectedRevision",authored.path("revision").asText(),"requestId","author-non-bone-undo"));
            assertEquals(model,edt(()->frame.history().state().model()));
            revision=edt(()->frame.history().revision());
            var bone=model.bones().get(0);
            var edit=Map.of("expectedRevision",revision,"requestId","stretchyman-edit","edits",List.of(Map.of("op","put_keyframe",
                    "animationId",clip.id().value(),"boneId",bone.id().value(),"channel","ROTATE","time",0,"values",List.of(10))));
            var edited=data(call("openbone_apply_edits",edit));
            call("openbone_undo",Map.of("expectedRevision",edited.path("revision").asText(),"requestId","stretchyman-undo"));
            assertEquals(model,edt(()->frame.history().state().model()));
            Path saved=output.resolve("stretchyman.openbone.json");
            call("openbone_save_project",Map.of("path",saved.toString(),"portable",true,"overwrite",true,
                    "expectedRevision",edt(()->frame.history().revision()),"requestId","stretchyman-save"));
            call("openbone_load_project",Map.of("path",saved.toString(),"dirtyPolicy","reject",
                    "expectedRevision",edt(()->frame.history().revision()),"requestId","stretchyman-reopen"));
            var reopened=edt(()->frame.history().state().model());
            assertEquals(model.animations(),reopened.animations());
            assertEquals(model.attachments().stream().filter(a->a instanceof PathAttachment).toList(),
                    reopened.attachments().stream().filter(a->a instanceof PathAttachment).toList());
            var idle=reopened.animations().stream().filter(a->a.name().equals("idle")).findFirst().orElseThrow();
            var idleCapture=call("openbone_capture_preview",Map.of("animationId",idle.id().value(),"time",0,
                    "expectedRevision",edt(()->frame.history().revision()),"fit",true,"showBones",false,"showGrid",false));
            ImageIO.write(image(idleCapture),"PNG",output.resolve("idle.png").toFile());
            assertArrayEquals(original,Files.readAllBytes(source));
            System.out.println("Stretchyman live source: "+source);
        } finally {
            if(frame!=null)edt(()->{if(frame.isDisplayable()){frame.history().markSaved();invoke("closeRequested");}return null;});
            restore("openbone.userState",oldState);restore("openbone.mcp.port",oldPort);
        }
    }

    @Test void xiaoheiBinaryLoadsRendersAnimatesEditsAndUndoesThroughMcp() throws Exception {
        String oldState=System.getProperty("openbone.userState"), oldPort=System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState",temp.resolve("xiaohei-state").toString());
        System.setProperty("openbone.mcp.port","0");
        McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        Path source=Path.of("src/test/resources/spine40/Ninjahouse_xiaohei.skel.bytes").toAbsolutePath();
        byte[] original=Files.readAllBytes(source);
        try {
            edt(()->{frame=new EditorFrame(null);frame.setSize(1200,850);frame.showEditor();return null;});
            awaitMcpStarted();
            edt(()->{url=((EditorMcpServer)field("mcpServer")).url();token=(String)field("mcpToken");return null;});
            String before=data(call("openbone_get_context",Map.of())).path("revision").asText();
            var load=Map.of("path",source.toString(),"expectedRevision",before,"requestId","load-xiaohei","dirtyPolicy","discard");
            var loaded=data(call("openbone_load_project",load));
            assertTrue(loaded.path("dirty").asBoolean());
            assertEquals(loaded,data(call("openbone_load_project",load)));
            // The source intentionally keeps body parts in skins 1 and 3; use the same command as the skin UI.
            var model=edt(()->{
                frame.history().execute("Select skin 1",io.github.samlinb.openbone.application.Commands.model(
                        d->io.github.samlinb.openbone.application.ExtendedEdits.skin(d,"1")));
                invoke("changed"); return frame.history().state().model();
            });
            assertEquals(17,model.bones().size()); assertEquals(4,model.animations().size());
            call("openbone_control_view",Map.of("action","configure","showBones",false,"showGrid",false,
                    "expectedRevision",edt(()->frame.history().revision()),"requestId","clean-xiaohei-preview"));
            call("openbone_control_view",Map.of("action","frame","expectedRevision",edt(()->frame.history().revision()),"requestId","frame-xiaohei"));
            String revision=edt(()->frame.history().revision());
            var shots=new ArrayList<BufferedImage>();
            for(var clip:model.animations()) for(double time:new double[]{0,clip.duration()*.5}) {
                var capture=call("openbone_capture_preview",Map.of("animationId",clip.id().value(),"time",time,"expectedRevision",revision,"fit",true));
                assertEquals(revision,data(capture).path("revision").asText()); shots.add(image(capture));
            }
            BufferedImage walk0=shots.get(6),walk1=shots.get(7); int changed=0;
            for(int y=0;y<walk0.getHeight();y++) for(int x=0;x<walk0.getWidth();x++)
                if(walk0.getRGB(x,y)!=walk1.getRGB(x,y)) changed++;
            assertTrue(changed>500,"Walk must animate actual rendered texture pixels");
            var clip=model.animations().get(3); var bone=model.bones().get(1);
            var edit=Map.of("expectedRevision",revision,"requestId","edit-xiaohei","edits",List.of(Map.of("op","put_keyframe",
                    "animationId",clip.id().value(),"boneId",bone.id().value(),"channel","ROTATE","time",0,"values",List.of(35))));
            var edited=data(call("openbone_apply_edits",edit));
            call("openbone_undo",Map.of("expectedRevision",edited.path("revision").asText(),"requestId","undo-xiaohei"));
            assertEquals(model,edt(()->frame.history().state().model()));
            Path output=Path.of("target/xiaohei-validation").toAbsolutePath();Files.createDirectories(output);
            int w=shots.get(0).getWidth(),h=shots.get(0).getHeight();
            var sheet=new BufferedImage(w*2,h*4,BufferedImage.TYPE_INT_RGB);var graphics=sheet.createGraphics();
            for(int i=0;i<shots.size();i++) {
                graphics.drawImage(shots.get(i),(i%2)*w,(i/2)*h,null);graphics.setColor(java.awt.Color.WHITE);
                graphics.drawString(model.animations().get(i/2).name()+(i%2==0?" t=0":" midpoint"),(i%2)*w+12,(i/2)*h+22);
            }
            graphics.dispose();ImageIO.write(sheet,"PNG",output.resolve("preview.png").toFile());
            call("openbone_save_project",Map.of("path",output.resolve("xiaohei.json").toString(),"portable",true,"overwrite",true,
                    "expectedRevision",edt(()->frame.history().revision()),"requestId","save-xiaohei"));
            assertArrayEquals(original,Files.readAllBytes(source));
            var exportedFiles=new ArrayList<Path>();
            for(String version:List.of("4.0.64","4.1.24","4.2.22","4.3.26"))for(String format:List.of("JSON","BINARY")) {
                var exported=data(call("openbone_export_spine",Map.of("directory",output.resolve(version+format).toString(),"version",version,"format",format,
                        "expectedRevision",edt(()->frame.history().revision()),"requestId","export-"+version+format)));
                exportedFiles.add(Path.of(exported.path("path").asText()));
            }
            for(Path exported:exportedFiles) {
                call("openbone_load_project",Map.of("path",exported.toString(),"expectedRevision",edt(()->frame.history().revision()),"requestId",UUID.randomUUID().toString(),"dirtyPolicy","discard"));
                var roundtrip=edt(()->{frame.history().execute("Select skin 1",io.github.samlinb.openbone.application.Commands.model(d->io.github.samlinb.openbone.application.ExtendedEdits.skin(d,"1")));invoke("changed");return frame.history().state().model();});
                var walk=roundtrip.animations().stream().filter(c->c.name().equals("walk")).findFirst().orElseThrow();
                var capture=call("openbone_capture_preview",Map.of("animationId",walk.id().value(),"time",walk.duration()*.5,"expectedRevision",edt(()->frame.history().revision()),"fit",true,"showBones",false,"showGrid",false));
                var pixels=image(capture);int foreground=0,background=pixels.getRGB(0,0);
                for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)if(pixels.getRGB(x,y)!=background)foreground++;
                assertTrue(foreground>1000,"Exported package must render actual character pixels: "+exported);
                ImageIO.write(pixels,"PNG",output.resolve(exported.getParent().getParent().getFileName()+"-roundtrip.png").toFile());
            }
        } finally {
            if(frame!=null) edt(()->{if(frame.isDisplayable()){frame.history().markSaved();invoke("closeRequested");}return null;});
            restore("openbone.userState",oldState);restore("openbone.mcp.port",oldPort);
        }
    }

    @Test
    @EnabledIfSystemProperty(named="openbone.mcp.sdkPython",matches=".+")
    void officialSdkBuildsSixLegRigFromBlankAndSavesAndLoads() throws Exception {
        String oldState=System.getProperty("openbone.userState"),oldPort=System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState",temp.resolve("scene-state").toString());System.setProperty("openbone.mcp.port","0"); McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        Path output=Path.of("target/mcp-ant-validation").toAbsolutePath(), assets=output.resolve("fixtures");Files.createDirectories(assets);
        createFixture(assets.resolve("body.png"),new java.awt.Color(75,38,22));
        createFixture(assets.resolve("head.png"),new java.awt.Color(105,52,28));
        createFixture(assets.resolve("segment.png"),new java.awt.Color(62,29,18));
        try {
            edt(()->{frame=new EditorFrame(null);frame.setSize(1200,850);frame.showEditor();return null;}); awaitMcpStarted(); edt(()->{url=((EditorMcpServer)field("mcpServer")).url();token=(String)field("mcpToken");return null;});
            var process=new ProcessBuilder(System.getProperty("openbone.mcp.sdkPython"),"scripts/mcp_scene_smoke.py").redirectErrorStream(true);
            process.environment().put("OPENBONE_MCP_TEST_URL",url);process.environment().put("OPENBONE_MCP_TEST_TOKEN",token);
            process.environment().put("OPENBONE_MCP_TEST_ALLOW_EDITS","disposable-fixture");process.environment().put("OPENBONE_MCP_TEST_OUTPUT",output.toString());process.environment().put("OPENBONE_MCP_TEST_ASSETS",assets.toString());
            process.environment().put("OPENBONE_MCP_SDK_PATH",Path.of(System.getProperty("openbone.mcp.sdkPath","target/mcp-client-deps")).toAbsolutePath().toString());
            Path log=output.resolve("sdk-scene.log");process.redirectOutput(log.toFile());var running=process.start();
            if(!running.waitFor(55,TimeUnit.SECONDS)){running.destroyForcibly();fail("Scene MCP test timed out");}
            assertEquals(0,running.exitValue(),Files.readString(log));
            assertTrue(Files.readString(log).contains("Official MCP scene smoke passed"));
            assertEquals(18,edt(()->frame.history().state().model().bones().size()));
            assertFalse(edt(()->frame.history().isDirty()));
        } finally {
            if(frame!=null)edt(()->{if(frame.isDisplayable()){frame.history().markSaved();invoke("closeRequested");}return null;});
            restore("openbone.userState",oldState);restore("openbone.mcp.port",oldPort);
        }
    }

    private static void createFixture(Path path,java.awt.Color color) throws Exception {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(color);g.fillOval(1,1,62,62);
        g.setColor(color.brighter());g.fillOval(15,7,15,28);g.dispose();ImageIO.write(image,"PNG",path.toFile());
    }

    @Test void currentProjectKeyframesGlPreviewUndoAndShutdown() throws Exception {
        String oldState = System.getProperty("openbone.userState"), oldPort = System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState", temp.resolve("state").toString()); System.setProperty("openbone.mcp.port", "0"); McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        EntityId bone = EntityId.create(), slot = EntityId.create(), attachment = EntityId.create(), clip = EntityId.create();
        var texture = new BufferedImage(48, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 48; x++) texture.setRGB(x, y, y < 12 ? 0xff22dd44 : 0xffdd2244);
        Path png = temp.resolve("texture.png"); ImageIO.write(texture, "PNG", png.toFile());
        var model = new DocumentSnapshot(List.of(new Bone(bone, "root", null, Transform.IDENTITY, 80)),
                List.of(new Slot(slot, "body", bone, attachment)),
                List.of(new RegionAttachment(attachment, "body", slot, png.toString(), new Transform(45, 0, 0, 1, 1), 90, 48)),
                List.of(new AnimationClip(clip, "idle", List.of())));
        Path project = temp.resolve("project.json"); Files.write(project, new ProjectCodec().encode(model));
        byte[] original = Files.readAllBytes(project);
        try {
            edt(() -> {
                frame = new EditorFrame(project); frame.setSize(1200, 850); frame.showEditor();
                return null;
            });
            awaitMcpStarted();
            edt(() -> {
                url = ((EditorMcpServer) field("mcpServer")).url(); token = (String) field("mcpToken"); return null;
            });
            var initialized = rpc("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "desktop-test", "version", "1")));
            assertFalse(initialized.has("error"), initialized.toString());
            var context = call("openbone_get_context", Map.of());
            String before = data(context).path("revision").asText();
            assertEquals(project.toAbsolutePath().toString(), data(context).path("view").path("projectPath").asText());
            var edits = List.of(Map.of("op", "put_keyframe", "animationId", clip.value(), "boneId", bone.value(), "channel", "ROTATE", "time", 0, "values", List.of(0)),
                    Map.of("op", "put_keyframe", "animationId", clip.value(), "boneId", bone.value(), "channel", "ROTATE", "time", 1, "values", List.of(90)));
            var edited = data(call("openbone_apply_edits", Map.of("expectedRevision", before, "requestId", "desktop-edit", "edits", edits)));
            String revision = edited.path("revision").asText(); assertEquals(1, edited.path("undoCount").asInt());
            assertTrue(edited.path("dirty").asBoolean());
            var atZero = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", 0, "expectedRevision", revision));
            var atHalf = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", .5, "expectedRevision", revision));
            var atEnd = call("openbone_capture_preview", Map.of("animationId", clip.value(), "time", 1, "expectedRevision", revision));
            var start = image(atZero); var end = image(atEnd);
            assertEquals(revision, data(atHalf).path("revision").asText());
            assertEquals(.5, data(atHalf).path("time").asDouble());
            assertEquals(1, data(call("openbone_get_context", Map.of())).path("view").path("time").asDouble());
            int changed = 0, green = 0, red = 0;
            for (int y = 0; y < start.getHeight(); y++) for (int x = 0; x < start.getWidth(); x++) {
                if (start.getRGB(x, y) != end.getRGB(x, y)) changed++;
                int c = start.getRGB(x, y);
                if (((c >>> 8) & 255) > 150 && ((c >>> 16) & 255) < 80) green++;
                if (((c >>> 16) & 255) > 150 && ((c >>> 8) & 255) < 80) red++;
            }
            assertTrue(changed > 500, "Rendered motion must change the actual viewport pixels");
            assertTrue(green > 100 && red > 100, "Actual attachment texture must be present, not just bones or a blank image");
            var tip = data(atHalf).path("bones").get(0).path("tip");
            assertEquals(80 / Math.sqrt(2), tip.path("x").asDouble(), 1e-6);
            assertEquals(80 / Math.sqrt(2), tip.path("y").asDouble(), 1e-6);
            Path evidence = Path.of("target/mcp-desktop"); Files.createDirectories(evidence);
            ImageIO.write(start, "PNG", evidence.resolve("t0.png").toFile()); ImageIO.write(image(atHalf), "PNG", evidence.resolve("t05.png").toFile()); ImageIO.write(end, "PNG", evidence.resolve("t1.png").toFile());
            if (System.getProperty("openbone.mcp.sdkPython") != null) {
                var process = new ProcessBuilder(System.getProperty("openbone.mcp.sdkPython"), "scripts/mcp_sdk_smoke.py").redirectErrorStream(true);
                process.environment().put("OPENBONE_MCP_TEST_URL", url);
                process.environment().put("OPENBONE_MCP_TEST_TOKEN", token);
                process.environment().put("OPENBONE_MCP_TEST_ALLOW_EDITS", "disposable-fixture");
                process.environment().put("OPENBONE_MCP_SDK_PATH", Path.of(System.getProperty("openbone.mcp.sdkPath", "target/mcp-client-deps")).toAbsolutePath().toString());
                var log = evidence.resolve("sdk-smoke.log"); process.redirectOutput(log.toFile());
                var running = process.start();
                if (!running.waitFor(45, TimeUnit.SECONDS)) { running.destroyForcibly(); fail("Official SDK smoke timed out"); }
                assertEquals(0, running.exitValue(), Files.readString(log));
                assertTrue(Files.readString(log).contains("Official Python MCP SDK smoke passed"));
                revision = data(call("openbone_get_context", Map.of())).path("revision").asText();
            }
            var undone = data(call("openbone_undo", Map.of("expectedRevision", revision, "requestId", "desktop-undo")));
            assertFalse(undone.path("dirty").asBoolean()); assertEquals(0, undone.path("undoCount").asInt());
            var keys = data(call("openbone_query_scene", Map.of("kind", "keyframes", "animationId", clip.value())));
            assertEquals(0, keys.path("total").asInt());
            assertArrayEquals(original, Files.readAllBytes(project), "MCP edits must not silently save the original project");
            assertEquals(model, edt(() -> frame.history().state().model()));
            edt(() -> { invoke("closeRequested"); return null; });
            assertThrows(java.io.IOException.class, () -> rpc("ping", Map.of()), "Closing the editor must stop its HTTP service");
        } finally {
            if (frame != null) edt(() -> {
                if (frame.isDisplayable()) { frame.history().markSaved(); invoke("closeRequested"); }
                return null;
            });
            restore("openbone.userState", oldState); restore("openbone.mcp.port", oldPort);
        }
    }
    @Test @EnabledIfSystemProperty(named="openbone.spineboy",matches="true")
    void spineboyColorMetadataImagesReachTheLiveGlPreview() throws Exception {
        var imported = new io.github.samlinb.openbone.application.ProjectDocument(DocumentSnapshot.EMPTY);
        imported.openResolved(Path.of("res/spineboy/spineboy-pro.spine"));
        assertTrue(imported.importWarnings().stream().noneMatch(w -> w.contains("image unavailable")),
                () -> imported.importWarnings().toString());
        assertTrue(imported.importWarnings().stream().noneMatch(w -> w.contains("invalid configuration")
                || w.contains("3.8 recovery")), () -> imported.importWarnings().toString());
        Path project = temp.resolve("spineboy.json");
        imported.savePortable(project);
        String oldState = System.getProperty("openbone.userState"), oldPort = System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState", temp.resolve("state").toString());
        System.setProperty("openbone.mcp.port", "0");
        McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        try {
            edt(() -> { frame = new EditorFrame(project); frame.setSize(1200,850); frame.showEditor(); return null; });
            awaitMcpStarted();
            edt(() -> { url = ((EditorMcpServer)field("mcpServer")).url(); token = (String)field("mcpToken"); return null; });
            var model = edt(() -> frame.history().state().model());
            assertEquals(14, model.constraints().size());
            var constraints = data(call("openbone_query_scene", Map.of("kind", "constraints")));
            assertEquals(14, constraints.path("total").asInt());
            String revision = data(call("openbone_get_context", Map.of())).path("revision").asText();
            Path evidence = Path.of("target/spineboy-image-preview"); Files.createDirectories(evidence);
            for (String name : List.of("hoverglow-small", "portal-flare1", "portal-flare2")) {
                var attachment = model.attachments().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
                var clip = model.animations().stream().filter(c -> c.name().equals(name.startsWith("hover") ? "hoverboard" : "portal"))
                        .findFirst().orElseThrow();
                double time = -1;
                for (int sample = 0; sample <= 120; sample++) {
                    double t = clip.duration()*sample/120;
                    var draws = io.github.samlinb.openbone.viewport.VisibleGeometry.evaluate(model,AnimationSampler.world(model,clip.id(),t,false));
                    if (draws.stream().anyMatch(d -> attachment.source().equals(d.source()) && d.color().a() > .05)) { time = t; break; }
                }
                assertTrue(time >= 0, "Animation must actually show " + name);
                var capture = call("openbone_capture_preview", Map.of("animationId",clip.id().value(),"time",time,
                        "expectedRevision",revision,"fit",true,"showBones",false,"showGrid",false));
                assertEquals(revision,data(capture).path("revision").asText());
                assertEquals(time,data(capture).path("time").asDouble());
                ImageIO.write(image(capture),"PNG",evidence.resolve(name+".png").toFile());
                var pixels = edt(() -> ((io.github.samlinb.openbone.viewport.ImageAssets)field("assets")).get(attachment.source()));
                assertFalse(pixels.missing(),pixels.error());
            }
        } finally {
            if (frame != null) edt(() -> { if (frame.isDisplayable()) { frame.history().markSaved(); invoke("closeRequested"); } return null; });
            restore("openbone.userState",oldState); restore("openbone.mcp.port",oldPort);
        }
    }

    void awaitMcpStarted() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (edt(() -> field("mcpServer") != null)) return;
            Thread.sleep(40);
        }
        fail("MCP automatic startup timed out");
    }
    void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    Object field(String name) throws Exception { var f = EditorFrame.class.getDeclaredField(name); f.setAccessible(true); return f.get(frame); }
    void invoke(String name) throws Exception { var m = EditorFrame.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(frame); }
    static <T> T edt(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    JsonNode rpc(String method, Object params) throws Exception {
        var body = json.createObjectNode().put("jsonrpc", "2.0").put("id", ++sequence).put("method", method); body.set("params", json.valueToTree(params));
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        var message = request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = http.send(message, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode()); return json.readTree(response.body());
    }
    JsonNode call(String tool, Object args) throws Exception {
        var response = rpc("tools/call", Map.of("name", tool, "arguments", args)); assertFalse(response.has("error"), response.toString());
        var result = response.path("result"); assertFalse(result.path("isError").asBoolean(), result.toString()); return result;
    }
    JsonNode data(JsonNode result) { return result.path("structuredContent").path("data"); }
    BufferedImage image(JsonNode result) throws Exception {
        var content = result.path("content").get(1); assertEquals("image/png", content.path("mimeType").asText());
        return ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(content.path("data").asText())));
    }
}
