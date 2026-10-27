package io.github.samlinb.openbone.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.application.ProjectDocument;
import io.github.samlinb.openbone.core.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Optional diagnostics against caller-owned local fixtures; no source writes or fallback consent. */
@EnabledIfSystemProperty(named="openbone.auditNinja",matches="true")
class LocalNinjaImportAuditTest {
    @Test void auditAllPreparedSources() throws Exception {
        Path output=Path.of(System.getProperty("openbone.ninjaOutput","E:/resource/ninja3-assets-047097ea/OpenBone项目与预览"));
        List<Path> paths;
        Path sourceList=output.resolve("_整理记录/audit-sources.json");
        if(Files.isRegularFile(sourceList)) {
            paths=new ArrayList<>();
            for(var node:new ObjectMapper().readTree(Files.readString(sourceList)))paths.add(Path.of(node.asText()));
        } else try(var walk=Files.walk(output)) {
            paths=walk.filter(Files::isRegularFile).filter(p->p.getParent().getFileName().toString().equals("_导入源"))
                    .filter(p->p.getFileName().toString().endsWith(".skel") || p.getFileName().toString().endsWith(".json") && !p.getFileName().toString().equals("source-provenance.json"))
                    .sorted().toList();
        }
        var entries=new ArrayList<Map<String,Object>>();
        var counts=new TreeMap<String,Integer>();
        int i=0;
        for(Path source:paths) {
            var row=new LinkedHashMap<String,Object>();row.put("source",source.toString());
            try {
                var project=new ProjectDocument(DocumentSnapshot.EMPTY);project.openResolved(source);
                var model=project.history().state().model();
                row.put("status","ok");row.put("bones",model.bones().size());row.put("attachments",model.attachments().size());
                row.put("animations",model.animations().size());row.put("warnings",project.importWarnings());
                var encoded=new ProjectCodec().encode(model);
                if(!model.equals(new ProjectCodec().decode(encoded)))throw new AssertionError("Project codec roundtrip differs");
            } catch(SpineVersionRiskException risk) {
                row.put("status","requires_version_approval");row.put("sourceVersion",risk.selection().sourceVersion());
                row.put("approval",risk.selection().approval());
            } catch(Exception | AssertionError error) {
                row.put("status","failed");row.put("error",error.toString());
                var trace=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(trace));row.put("trace",trace.toString());
            }
            entries.add(row);counts.merge(row.get("status").toString(),1,Integer::sum);
            if(++i%25==0)System.out.println("Ninja model audit "+i+"/"+paths.size()+" "+counts);
        }
        var report=new LinkedHashMap<String,Object>();report.put("sourceCount",paths.size());report.put("counts",counts);report.put("entries",entries);
        Path reportPath=output.resolve("_整理记录/model-import-audit.json");
        Files.writeString(reportPath,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Ninja model audit complete: "+counts+" -> "+reportPath);
    }
}
