package io.github.samlinb.openbone.desktop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.awt.Rectangle;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceLayoutTest {
    @TempDir Path root;
    @Test void logicalBoundsClampToCurrentScreen(){assertEquals(new Rectangle(0,0,1280,720),WorkspaceLayout.fit(new Rectangle(-9000,9000,9000,9000),new Rectangle(0,0,1280,720)));assertEquals(new Rectangle(-1280,0,640,480),WorkspaceLayout.fit(new Rectangle(-2000,-2000,20,20),new Rectangle(-1280,0,1280,720)));}
    @Test void persistsAndRejectsFutureLayout() throws Exception {var s=new WorkspaceLayout.State(1,20,30,1000,700,200,300,true);Path file=root.resolve("中文/layout.json");WorkspaceLayout.save(file,s);assertEquals(s,WorkspaceLayout.read(file));Files.writeString(file,"{\"version\":2,\"width\":800,\"height\":600}");assertThrows(Exception.class,()->WorkspaceLayout.read(file));}
}
