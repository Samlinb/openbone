package io.github.samlinb.openbone.desktop;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.io.*;
import static org.junit.jupiter.api.Assertions.*;

class SpineFeaturePanelTest {
    @TempDir Path temp;
    @SuppressWarnings("unchecked")
    static Map<String,JComponent> controls(EditorFrame frame) throws Exception {
        var field = EditorFrame.class.getDeclaredField("named"); field.setAccessible(true);
        return (Map<String,JComponent>)field.get(frame);
    }
    static void input(EditorFrame frame,String name,String value) throws Exception {
        var text = (JTextField)controls(frame).get(name); assertNotNull(text,name);
        text.setText(value); text.postActionEvent();
    }
    @Test void setupPropertiesAndTimelineShearKeysUseRealSwingActionsAndUndo() throws Exception {
        var bone = new Bone(EntityId.create(),"root",null,new Transform(0,0,0,1,1,5,-5),20);
        var slot = new Slot(EntityId.create(),"slot",bone.id(),null);
        var box = new BoundingBoxAttachment(EntityId.create(),"box",slot.id(),List.of(new Vec2(0,0),new Vec2(10,0),new Vec2(10,10),new Vec2(0,10)),BoundingBoxAttachment.DEFAULT_COLOR);
        var clip = new ClippingAttachment(EntityId.create(),"clip",slot.id(),List.of(new Vec2(-20,-20),new Vec2(20,-20),new Vec2(20,20),new Vec2(-20,20)),null);
        var d = new DocumentSnapshot(List.of(bone),List.of(slot.withActiveAttachment(box.id())),List.of(box,clip));
        var path = temp.resolve("editing.json"); Files.write(path,new ProjectCodec().encode(d));
        String previous = System.getProperty("openbone.userState"); System.setProperty("openbone.userState",temp.resolve("state").toString());
        try {
            SwingUtilities.invokeAndWait(() -> {
                var frame = new EditorFrame(path);
                try {
                    frame.history().select(Selection.of(bone.id())); frame.changed();
                    input(frame,"property.shearX","25"); assertEquals(25,frame.history().state().model().bone(bone.id()).local().shearX());
                    frame.history().undo(); frame.changed(); assertEquals(5,frame.history().state().model().bone(bone.id()).local().shearX());
                    frame.history().select(Selection.of(box.id())); frame.changed();
                    input(frame,"property.boundingbox.color","ff6600ff"); input(frame,"property.boundingbox.x","2");
                    var edited = (BoundingBoxAttachment)frame.history().state().model().attachment(box.id());
                    assertEquals("ff6600ff",edited.color().hex()); assertEquals(2,edited.vertices().get(0).x());
                    frame.history().select(Selection.of(clip.id())); frame.changed();
                    input(frame,"property.clip.color","aa55ccff"); assertEquals("aa55ccff",((ClippingAttachment)frame.history().state().model().attachment(clip.id())).color().hex());
                    frame.history().select(Selection.of(bone.id())); frame.changed();
                    ((JComboBox<?>)controls(frame).get("animation.mode")).setSelectedIndex(1);
                    input(frame,"property.shearX","35");
                    input(frame,"animation.time","1"); input(frame,"property.shearX","65");
                    input(frame,"animation.time","0.5");
                    var model = frame.history().state().model(); var track = model.animations().get(0).timelines().stream().filter(t -> t.channel()==Channel.SHEAR).findFirst().orElseThrow();
                    assertEquals(2,track.keys().size());
                    assertEquals(50,AnimationSampler.pose(model,model.animations().get(0).id(),.5,false).get(bone.id()).shearX(),1e-10);
                    assertEquals(50,Double.parseDouble(((JTextField)controls(frame).get("property.shearX")).getText()),1e-10);
                    assertEquals(model,new ProjectCodec().decode(new ProjectCodec().encode(model)));
                } catch (Exception failure) { throw new RuntimeException(failure); }
                finally {
                    try {
                        frame.history().markSaved();
                        var close = EditorFrame.class.getDeclaredMethod("closeRequested"); close.setAccessible(true); close.invoke(frame);
                    } catch (Exception failure) { frame.dispose(); throw new RuntimeException(failure); }
                }
            });
        } finally {
            if (previous == null) System.clearProperty("openbone.userState"); else System.setProperty("openbone.userState",previous);
        }
    }
}
