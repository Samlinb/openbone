package io.github.samlinb.openbone.desktop;

import io.github.samlinb.openbone.application.Selection;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ProjectCodec;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the redesigned shell against the real Swing editor and GL viewport. */
@EnabledIfSystemProperty(named="openbone.verifyGl", matches="true")
class WorkspaceDesktopTest {
    @TempDir Path temp;
    EditorFrame frame;
    @SuppressWarnings("unchecked") Map<String,JComponent> controls() throws Exception {
        var field=EditorFrame.class.getDeclaredField("named");field.setAccessible(true);return (Map<String,JComponent>)field.get(frame);
    }
    static <T>T edt(Callable<T> work) throws Exception {
        var task=new FutureTask<>(work);SwingUtilities.invokeAndWait(task);return task.get(10,TimeUnit.SECONDS);
    }
    @Test void modeLayoutGraphSelectionEditingUndoAndLayoutRoundTrip() throws Exception {
        String previous=System.getProperty("openbone.userState");System.setProperty("openbone.userState",temp.resolve("state").toString());
        var root=new Bone(EntityId.create(),"root",null,new Transform(0,0,90,1,1),90);
        var chest=new Bone(EntityId.create(),"chest",root.id(),new Transform(90.12345678912345,.000000000012345,0,1,1),70);
        var k0=new Keyframe(EntityId.create(),0,List.of(-15.),List.of(new Curve(Curve.Kind.BEZIER,.25,0,.75,1)));
        var k1=new Keyframe(EntityId.create(),.5,List.of(15.),List.of(Curve.LINEAR));
        var k2=new Keyframe(EntityId.create(),1,List.of(-15.),List.of(Curve.LINEAR));
        var clip=new AnimationClip(EntityId.create(),"walk",List.of(new BoneTimeline(chest.id(),Channel.ROTATE,List.of(k0,k1,k2))));
        var model=new DocumentSnapshot(List.of(root,chest),List.of(),List.of(),List.of(clip));
        Path project=temp.resolve("workspace.json");Files.write(project,new ProjectCodec().encode(model));
        try {
            edt(()->{frame=new EditorFrame(project);frame.setSize(1280,900);frame.showEditor();frame.history().select(Selection.of(chest.id()));frame.changed();return null;});
            edt(()->{
                var c=controls();assertInstanceOf(WorkspaceTheme.class,UIManager.getLookAndFeel());
                var search=(JTextField)c.get("workspace.search");search.setText("chest");
                assertEquals(3,((JTree)find(frame,"hierarchy")).getRowCount());
                assertEquals(model,frame.history().state().model());search.setText("");
                var split=(JSplitPane)c.get("workspace.timelineSplit");assertFalse(split.getBottomComponent().isVisible());
                assertTrue(c.get("animation.setupMode").isShowing());assertFalse(c.get("animation.mode").isShowing());
                ((JToggleButton)c.get("animation.animateMode")).doClick();frame.validate();
                assertTrue(((JToggleButton)c.get("animation.animateMode")).isSelected());
                assertFalse(((JToggleButton)c.get("animation.setupMode")).isSelected());
                assertTrue(split.getBottomComponent().isShowing());assertTrue(frame.viewport().canvas().getHeight()>100);
                assertTrue(c.get("animation.trackViewport").getHeight()>30);
                frame.history().selectKeys(java.util.Set.of(k1.id()));frame.changed();
                ((JToggleButton)c.get("animation.curvesView")).doClick();frame.validate();
                assertSame(c.get("animation.graph"),((JViewport)c.get("animation.trackViewport")).getView());
                assertEquals(model,frame.history().state().model());assertEquals(0,frame.history().undoCount());
                ((JTextField)c.get("property.x")).postActionEvent();assertEquals(0,frame.history().undoCount());
                var field=(JTextField)c.get("property.rotation");field.setText("25");field.postActionEvent();
                assertEquals(chest.local(),frame.history().state().model().bone(chest.id()).local(),"An animation edit must preserve exact Setup values");
                assertEquals(1,frame.history().undoCount());frame.history().undo();frame.changed();assertEquals(model,frame.history().state().model());
                frame.history().redo();frame.changed();assertEquals(1,frame.history().undoCount());frame.history().undo();frame.changed();
                split.setDividerLocation(split.getHeight()-WorkspaceTheme.scale(290));frame.validate();
                assertTrue(((JViewport)c.get("animation.list").getParent()).getExtentSize().height>=WorkspaceTheme.scale(29),"Keep at least one animation row visible when resizing the timeline");
                return null;
            });
            // A production capture requires an actual, current OpenGL context.
            Path output=Path.of("target/workspace-desktop");Files.createDirectories(output);
            String scale=System.getProperty("flatlaf.uiScale","1");
            captureEditor(output.resolve("workspace-"+scale+".png"));
            edt(()->{
                var c=controls();
                ((JToggleButton)c.get("animation.setupMode")).doClick();frame.validate();assertFalse(((JSplitPane)c.get("workspace.timelineSplit")).getBottomComponent().isVisible());
                assertTrue(((JToggleButton)c.get("animation.setupMode")).isSelected());
                ((JToggleButton)c.get("animation.animateMode")).doClick();frame.validate();assertTrue(c.get("animation.graph").isShowing());
                frame.history().markSaved();close();return null;
            });
            var layout=WorkspaceLayout.read(temp.resolve("state/layout.json"));assertTrue(layout.curves());assertTrue(layout.timeline()>=180);
            edt(()->{frame=new EditorFrame(project);frame.showEditor();assertTrue(((JToggleButton)controls().get("animation.curvesView")).isSelected());return null;});
            edt(()->frame.viewport().capture(()->{})).get(12,TimeUnit.SECONDS);
            edt(()->{close();return null;});
        }finally{
            if(frame!=null)edt(()->{if(frame.isDisplayable()){frame.history().markSaved();close();}return null;});
            if(previous==null)System.clearProperty("openbone.userState");else System.setProperty("openbone.userState",previous);
        }
    }

    @Test @EnabledIfSystemProperty(named="openbone.workspace.previewProject",matches=".+")
    void previewExistingProjectWithoutSaving() throws Exception {
        String previous=System.getProperty("openbone.userState");System.setProperty("openbone.userState",temp.resolve("preview-state").toString());
        Path project=Path.of(System.getProperty("openbone.workspace.previewProject"));byte[] original=Files.readAllBytes(project);
        try {
            edt(()->{
                frame=new EditorFrame(project);frame.setSize(1600,1000);frame.showEditor();
                var model=frame.history().state().model();var clips=model.animations();
                for(int i=0;i<clips.size();i++)if(clips.get(i).name().equals("walk")){((JComboBox<?>)controls().get("animation.clips")).setSelectedIndex(i);break;}
                ((JComboBox<?>)controls().get("animation.mode")).setSelectedIndex(1);
                var bone=model.bones().stream().filter(b->b.name().equals("hip")||b.name().equals("chest")).findFirst().orElse(model.bones().get(0));
                frame.history().select(Selection.of(bone.id()));frame.changed();
                ((JTextField)controls().get("workspace.search")).setText(bone.name());
                frame.validate();var fit=EditorFrame.class.getDeclaredMethod("frameCurrentPose");fit.setAccessible(true);fit.invoke(frame);
                return null;
            });
            Path output=Path.of("target/workspace-desktop");Files.createDirectories(output);
            captureEditor(output.resolve("project-timeline.png"));
            edt(()->{
                var combo=(JComboBox<?>)controls().get("animation.graphTrack");
                for(int i=0;i<combo.getItemCount();i++)if(combo.getItemAt(i).toString().contains("hip")&&combo.getItemAt(i).toString().contains("Rotate")){combo.setSelectedIndex(i);break;}
                ((JToggleButton)controls().get("animation.curvesView")).doClick();frame.validate();return null;
            });
            captureEditor(output.resolve("project-curves.png"));
            edt(()->{((JComboBox<?>)controls().get("animation.mode")).setSelectedIndex(0);frame.validate();return null;});
            captureEditor(output.resolve("project-setup.png"));
            assertArrayEquals(original,Files.readAllBytes(project));
        }finally{
            if(frame!=null)edt(()->{if(frame.isDisplayable()){frame.history().markSaved();close();}return null;});
            if(previous==null)System.clearProperty("openbone.userState");else System.setProperty("openbone.userState",previous);
        }
    }

    /** Captures only this app: Swing paint plus its current real GL framebuffer, on EDT. */
    void captureEditor(Path output) throws Exception {
        var pixels=edt(()->frame.viewport().capture(()->{})).get(12,TimeUnit.SECONDS);
        assertTrue(pixels.getWidth()>100);
        var image=edt(()->{
            var root=frame.getRootPane();var result=new java.awt.image.BufferedImage(root.getWidth(),root.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=result.createGraphics();root.printAll(g);
            var canvas=frame.viewport().canvas();var at=SwingUtilities.convertPoint(canvas,0,0,root);
            g.drawImage(pixels,at.x,at.y,canvas.getWidth(),canvas.getHeight(),null);g.dispose();return result;
        });
        ImageIO.write(image,"PNG",output.toFile());
    }
    void close() throws Exception {
        var close=EditorFrame.class.getDeclaredMethod("closeRequested");close.setAccessible(true);close.invoke(frame);
    }
    static Component find(Container parent,String name) {
        for(Component c:parent.getComponents()){
            if(name.equals(c.getName()))return c;
            if(c instanceof Container container){var found=find(container,name);if(found!=null)return found;}
        }
        return null;
    }
}
