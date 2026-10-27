package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpineNativeCliTest {
    @TempDir Path temp;

    @Test void nativeVersionProbeHandlesBothKryoStringEncodingsAndInvalidHeaders()throws Exception {
        for(var family:SpineVersion.values())for(boolean utf8:List.of(false,true)) {
            byte[] bytes=container(family.exportVersion(),utf8,-1340035040);
            assertEquals(new SpineNativeHeader(72,family.exportVersion()),SpineNativeHeader.read(bytes));
            assertTrue(SpineImportPolicy.select(bytes,SpineImportPolicy.Format.NATIVE,null).known());
            assertThrows(ProjectFormatException.class,()->SpineNativeHeader.read(Arrays.copyOf(bytes,3)));
        }
        byte[] unknown=container("4.4.9",false,-1340035040);
        var risk=assertThrows(SpineVersionRiskException.class,()->SpineImportPolicy.select(unknown,SpineImportPolicy.Format.NATIVE,null));
        assertEquals(SpineImportPolicy.Parser.NATIVE_43,risk.selection().parser());
        assertFalse(SpineImportPolicy.select(unknown,SpineImportPolicy.Format.NATIVE,risk.selection().approval()).known());
        assertThrows(ProjectFormatException.class,()->SpineNativeHeader.read(container("4.2.22",false,0)));
    }

    @Test @EnabledIfSystemProperty(named="openbone.verifySpineCli",matches="true")
    void licensedCliCreatesRealNativeProjectsAndImportsTheirActualData()throws Exception {
        SpineCliBridge.requireAvailable();
        var source=ConstraintTimelineCodecTest.base();
        for(var family:SpineVersion.values()) {
            Path directory=temp.resolve(family.family());
            Path project=new ExportPackage().publish(directory,source,temp,family.exportVersion(),ExportPackage.Format.NATIVE);
            assertEquals(family.exportVersion(),SpineNativeHeader.read(Files.readAllBytes(project)).version());
            var imported=new io.github.samlinb.openbone.application.ProjectDocument(io.github.samlinb.openbone.core.model.DocumentSnapshot.EMPTY);
            imported.importSkeleton(project);
            assertEquals(source.bones().stream().map(b->b.name()).toList(),imported.history().state().model().bones().stream().map(b->b.name()).toList());
            assertTrue(imported.history().isDirty());
        }
    }

    private static byte[] container(String version,boolean utf8,int magic)throws IOException {
        var raw=new ByteArrayOutputStream();var output=new DataOutputStream(raw);output.writeByte(72);output.writeInt(magic);
        if(utf8){output.writeByte(128|version.length()+1);output.writeBytes(version);}
        else {for(int i=0;i<version.length();i++)output.writeByte(version.charAt(i)|(i==version.length()-1?128:0));}
        var bytes=new ByteArrayOutputStream();var deflater=new Deflater(Deflater.DEFAULT_COMPRESSION,true);
        try(var compressed=new DeflaterOutputStream(bytes,deflater)){compressed.write(raw.toByteArray());}finally{deflater.end();}
        return bytes.toByteArray();
    }
}
