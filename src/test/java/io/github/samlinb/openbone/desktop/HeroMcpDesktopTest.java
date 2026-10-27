package io.github.samlinb.openbone.desktop;

import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.mcp.EditorMcpServer;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static io.github.samlinb.openbone.desktop.McpDesktopTest.edt;
import static org.junit.jupiter.api.Assertions.*;

/** Real local Hero .spine selection, HTTP MCP, GL preview, edit/undo, and portable reopen. */
@EnabledIfSystemProperty(named="openbone.verifyGl", matches="true")
@EnabledIfSystemProperty(named="openbone.hero", matches="true")
class HeroMcpDesktopTest {
    @TempDir Path temp;
    @Test void nativeSelectionLoadsSwitchesWeaponsRendersAndUndoesThroughMcp() throws Exception {
        var h=new McpDesktopTest(); h.temp=temp;
        String oldState=System.getProperty("openbone.userState"),oldPort=System.getProperty("openbone.mcp.port");
        System.setProperty("openbone.userState",temp.resolve("hero-state").toString());
        System.setProperty("openbone.mcp.port","0");
        McpConnectionSettings.save(McpConnectionSettings.path(),new McpConnectionSettings.State(39871,true,""));
        Path source=Path.of("res/hero/hero-pro.spine").toAbsolutePath(); byte[] original=Files.readAllBytes(source);
        Path output=Path.of("target/hero-validation").toAbsolutePath(); Files.createDirectories(output);
        try {
            edt(()->{h.frame=new EditorFrame(null);h.frame.setSize(1200,850);h.frame.showEditor();return null;});
            h.awaitMcpStarted();
            edt(()->{h.url=((EditorMcpServer)h.field("mcpServer")).url();h.token=(String)h.field("mcpToken");return null;});
            String before=h.data(h.call("openbone_get_context",Map.of())).path("revision").asText();
            var args=new LinkedHashMap<String,Object>(Map.of("path",source.toString(),"expectedRevision",before,
                    "requestId","hero-risk","dirtyPolicy","reject"));
            var risk=h.rpc("tools/call",Map.of("name","openbone_load_project","arguments",args)).path("result");
            assertEquals("SPINE_VERSION_RISK",risk.path("structuredContent").path("code").asText());
            assertEquals(before,h.data(h.call("openbone_get_context",Map.of())).path("revision").asText());
            args.put("versionRiskApproval",h.json.convertValue(risk.path("structuredContent").path("versionRiskApproval"),Map.class));
            args.put("requestId","hero-approved");
            var loaded=h.data(h.call("openbone_load_project",args));
            assertEquals(loaded,h.data(h.call("openbone_load_project",args)));
            var model=edt(()->h.frame.history().state().model());
            Files.writeString(output.resolve("import-report.txt"),String.join("\n",edt(()->h.frame.document().importWarnings())));
            assertEquals(44,model.bones().size()); assertEquals(12,model.animations().size());
            var skins=h.data(h.call("openbone_query_scene",Map.of("kind","skins","limit",200)));
            assertEquals(3,skins.path("total").asInt());
            assertEquals(13,skins.path("items").get(1).path("boneIds").size());
            assertEquals(1,skins.path("items").get(1).path("constraintIds").size());
            var clip=model.animations().stream().filter(a->a.name().equals("run")).findFirst().orElseThrow();
            for(String skin:List.of("weapon/sword","weapon/morningstar")) {
                String revision=h.data(h.call("openbone_get_context",Map.of())).path("revision").asText();
                var edit=Map.of("expectedRevision",revision,"requestId",skin,"edits",List.of(Map.of("op","set_skin","name",skin)));
                var result=h.data(h.call("openbone_apply_edits",edit));
                assertEquals(result,h.data(h.call("openbone_apply_edits",edit)));
                revision=result.path("revision").asText();
                assertEquals(skin,result.path("activeSkin").asText());
                var shots=new ArrayList<BufferedImage>();
                for(double time:List.of(0.,.2,.4)) {
                    var capture=h.call("openbone_capture_preview",Map.of("animationId",clip.id().value(),"time",time,
                            "expectedRevision",revision,"fit",true,"showBones",false,"showGrid",false));
                    assertEquals(revision,h.data(capture).path("revision").asText());
                    assertEquals(time,h.data(capture).path("time").asDouble());
                    var pixels=h.image(capture); shots.add(pixels);
                    int foreground=0,background=pixels.getRGB(0,0);
                    for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)if(pixels.getRGB(x,y)!=background)foreground++;
                    assertTrue(foreground>2000,"Character and weapon textures must render");
                    ImageIO.write(pixels,"PNG",output.resolve(skin.substring(7)+"-run-"+time+".png").toFile());
                }
                int changed=0;
                for(int y=0;y<shots.get(0).getHeight();y++)for(int x=0;x<shots.get(0).getWidth();x++)
                    if(shots.get(0).getRGB(x,y)!=shots.get(1).getRGB(x,y))changed++;
                assertTrue(changed>500,"Animation must change actual viewport pixels");
                var undone=h.data(h.call("openbone_undo",Map.of("expectedRevision",revision,"requestId","undo-"+skin)));
                assertEquals(undone,h.data(h.call("openbone_undo",Map.of("expectedRevision",revision,"requestId","undo-"+skin))));
                assertEquals(model,edt(()->h.frame.history().state().model()));
                String undoRevision=undone.path("revision").asText();
                var invalid=h.rpc("tools/call",Map.of("name","openbone_apply_edits","arguments",Map.of(
                        "expectedRevision",undoRevision,"requestId","bad-"+skin,"edits",List.of(Map.of("op","set_skin","name","missing"))))).path("result");
                assertTrue(invalid.path("isError").asBoolean());
                assertEquals(1,edt(()->h.frame.history().redoCount()));
                assertEquals(undoRevision,h.data(h.call("openbone_get_context",Map.of())).path("revision").asText());
            }
            String revision=h.data(h.call("openbone_get_context",Map.of())).path("revision").asText();
            var changed=h.data(h.call("openbone_apply_edits",Map.of("expectedRevision",revision,"requestId","hero-final-skin",
                    "edits",List.of(Map.of("op","set_skin","name","weapon/sword")))));
            revision=changed.path("revision").asText();
            var beforeKey=edt(()->h.frame.history().state().model());
            var head=model.bones().stream().filter(b->b.name().equals("head")).findFirst().orElseThrow();
            var keyed=h.data(h.call("openbone_apply_edits",Map.of("expectedRevision",revision,"requestId","hero-keyframe",
                    "edits",List.of(Map.of("op","put_keyframe","animationId",clip.id().value(),"boneId",head.id().value(),
                            "channel","ROTATE","time",.2,"values",List.of(25.))))));
            revision=keyed.path("revision").asText();
            var keyPreview=h.call("openbone_capture_preview",Map.of("animationId",clip.id().value(),"time",.2,
                    "expectedRevision",revision,"fit",true,"showBones",false,"showGrid",false));
            ImageIO.write(h.image(keyPreview),"PNG",output.resolve("head-keyframe.png").toFile());
            var undoKey=h.data(h.call("openbone_undo",Map.of("expectedRevision",revision,"requestId","hero-keyframe-undo")));
            assertEquals(beforeKey,edt(()->h.frame.history().state().model()));
            revision=undoKey.path("revision").asText();
            Path portable=output.resolve("hero.openbone.json");
            h.call("openbone_save_project",Map.of("expectedRevision",revision,"requestId","hero-save","path",portable.toString(),"overwrite",true));
            h.call("openbone_load_project",Map.of("expectedRevision",revision,"requestId","hero-reopen","path",portable.toString(),"dirtyPolicy","reject"));
            assertEquals("weapon/sword",h.data(h.call("openbone_get_context",Map.of())).path("activeSkin").asText());
            assertEquals(model.resources().activation(),edt(()->h.frame.history().state().model().resources().activation()));
            for(String version:List.of("4.2.22","4.3.26"))for(String format:List.of("JSON","BINARY")) {
                String exportRevision=edt(()->h.frame.history().revision());
                var exportArgs=Map.of("directory",output.resolve("exports").resolve(version+format).toString(),
                        "version",version,"format",format,"expectedRevision",exportRevision,"requestId","hero-export-"+version+format);
                var exported=h.data(h.call("openbone_export_spine",exportArgs));
                assertEquals(exported,h.data(h.call("openbone_export_spine",exportArgs)));
                assertEquals(exportRevision,edt(()->h.frame.history().revision()));
                h.call("openbone_load_project",Map.of("path",exported.path("path").asText(),"expectedRevision",exportRevision,
                        "requestId","hero-export-load-"+version+format,"dirtyPolicy","discard"));
                for(String skin:List.of("weapon/sword","weapon/morningstar")) {
                    var selected=h.data(h.call("openbone_apply_edits",Map.of("expectedRevision",edt(()->h.frame.history().revision()),
                            "requestId","hero-export-skin-"+version+format+skin,"edits",List.of(Map.of("op","set_skin","name",skin)))));
                    var imported=edt(()->h.frame.history().state().model());
                    assertEquals(12,imported.animations().size());
                    var run=imported.animations().stream().filter(a->a.name().equals("run")).findFirst().orElseThrow();
                    var capture=h.call("openbone_capture_preview",Map.of("animationId",run.id().value(),"time",.2,
                            "expectedRevision",selected.path("revision").asText(),"fit",true,"showBones",false,"showGrid",false));
                    var pixels=h.image(capture);int foreground=0,background=pixels.getRGB(0,0);
                    for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)if(pixels.getRGB(x,y)!=background)foreground++;
                    assertTrue(foreground>2000,"Exported Hero must render actual character textures");
                    ImageIO.write(pixels,"PNG",output.resolve("export-"+version+format+"-"+skin.substring(7)+".png").toFile());
                }
            }
            assertArrayEquals(original,Files.readAllBytes(source));
        } finally {
            if(h.frame!=null)edt(()->{if(h.frame.isDisplayable()){h.frame.history().markSaved();h.invoke("closeRequested");}return null;});
            h.restore("openbone.userState",oldState);h.restore("openbone.mcp.port",oldPort);
        }
    }
}
