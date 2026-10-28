package io.github.samlinb.openbone.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samlinb.openbone.core.animation.AnimationClip;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ExportPackage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePreviewTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();

    @Test void verifiesExportHashesBeforeGlInitialization() throws Exception {
        var model=new DocumentSnapshot(List.of(new Bone(EntityId.create(),"root",null,Transform.IDENTITY,20)),List.of(),List.of(),
                List.of(new AnimationClip(EntityId.create(),"idle",List.of())));
        Path skeleton=new ExportPackage().publish(temp,model,temp,"4.2.22",ExportPackage.Format.JSON);
        var request=json.valueToTree(Map.of("protocol",1,"entry",temp.resolve("current.json").toString(),"animation","idle","time",0,"width",768,"height",768));
        assertDoesNotThrow(()->new RuntimePreview(request,temp,false));
        Files.writeString(skeleton,"{}");
        assertThrows(Exception.class,()->new RuntimePreview(request,temp,false));
    }

    @Test void rejectsInvalidDimensionsAndProtocol() {
        var request=json.createObjectNode().put("protocol",1).put("entry","unused").put("animation","idle").put("time",0).put("width",0).put("height",768);
        assertThrows(IllegalArgumentException.class,()->new RuntimePreview(request,temp,false));
        request.put("width",2049);
        assertThrows(IllegalArgumentException.class,()->new RuntimePreview(request,temp,false));
        request.put("width",768).put("protocol",2);
        assertThrows(IllegalArgumentException.class,()->new RuntimePreview(request,temp,false));
        request.put("protocol","1");
        assertThrows(IllegalArgumentException.class,()->new RuntimePreview(request,temp,false));
    }
}
