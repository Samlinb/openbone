package io.github.samlinb.openbone.desktop;

import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.animation.*;
import io.github.samlinb.openbone.core.math.Transform;
import io.github.samlinb.openbone.core.model.*;
import static io.github.samlinb.openbone.TestData.id;
import static org.junit.jupiter.api.Assertions.*;

class AnimationDurationCacheTest {
    private static AnimationClip clip(int value,String name,double end) {
        return new AnimationClip(id(value),name,List.of(new BoneTimeline(id(1),Channel.ROTATE,List.of(
                new Keyframe(id(value+100),0,List.of(0.),List.of(Curve.LINEAR)),
                new Keyframe(id(value+200),end,List.of(30.),List.of(Curve.LINEAR))))));
    }
    private static DocumentSnapshot model(List<AnimationClip> clips) {
        return new DocumentSnapshot(List.of(new Bone(id(1),"root",null,Transform.IDENTITY,1)),List.of(),List.of(),clips);
    }
    private static final class Host implements AnimationPanel.Host {
        final CommandHistory history=new CommandHistory(model(List.of(clip(30,"first",1),clip(31,"second",2))));
        final Map<String,JComponent> controls=new HashMap<>();AnimationPanel panel;
        public CommandHistory history(){return history;}
        public Bone selectedBone(){return null;}
        public boolean commitFields(){return true;}
        public void changed(){if(panel!=null)panel.refresh();}
        public void error(Exception e){throw new AssertionError(e);}
        public void cancelViewport(){}
        public void poseChanged(){}
        public Map<String,JComponent> controls(){return controls;}
    }
    @Test void durationUpdatesImmediatelyAfterClipSwitchKeysUndoRedoAndModelReplacement()throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var host=new Host();host.panel=new AnimationPanel(host);var panel=host.panel;
            try {
                panel.remoteSeek(id(30),0);assertEquals(1,panel.duration());
                for(int i=0;i<1000;i++)assertEquals(1,panel.duration());
                panel.remoteSeek(id(31),0);assertEquals(2,panel.duration());
                host.history.execute("extend",Commands.model(d->AnimationEdits.put(d,id(31),id(1),Channel.ROTATE,5,List.of(60.))));
                assertEquals(5,panel.duration()); // No refresh() is needed to invalidate the cached model.
                host.history.undo();assertEquals(2,panel.duration());host.history.redo();assertEquals(5,panel.duration());
                host.history.reset(model(List.of(clip(31,"replacement with same ID",7))));assertEquals(7,panel.duration());
                host.history.reset(model(List.of()));assertEquals(0,panel.duration());
            } finally {panel.stop();}
        });
    }
}
