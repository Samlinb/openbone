package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageResourcePanelTest {
    @TempDir Path temp;
    @Test void showsSplitImagesAndKeepsSelectionAcrossReplacementAndUndo() throws Exception {
        for(String extension:List.of("json","atlas","png")) try(var in=getClass().getResourceAsStream("/spine38/dandao_1."+extension)) { Files.copy(in,temp.resolve("dandao_1."+extension)); }
        Path file=temp.resolve("dandao_1.json");var d=AtlasSplitter.split(file,new SkeletonJsonCodec().read(file)).document();
        var history=new CommandHistory(d);var error=new AtomicReference<Exception>();
        var green=new BufferedImage(16,12,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<12;y++) for(int x=0;x<16;x++) green.setRGB(x,y,0xff22bb44);
        Path png=temp.resolve("green.png");ImageIO.write(green,"PNG",png.toFile());
        SwingUtilities.invokeAndWait(()->{
            var panel=new ImageResourcePanel("test.images",()->history,()->temp,()->true,()->{},error::set,id->{});
            var list=(JList<?>)find(panel,"test.images.list");assertEquals(11,list.getModel().getSize());list.setSelectedIndex(5);
            var selected=(ImageResources.Entry)list.getSelectedValue();
            try {
                var after=ImageResources.replace(d,temp,selected,png);history.execute("Replace",Commands.model(v->after));panel.refresh();
                assertEquals(selected.attachments(),((ImageResources.Entry)list.getSelectedValue()).attachments());
                // Decoding is asynchronous; selection and history must update
                // immediately without waiting for image dimensions on the EDT.
                assertNotNull(((JLabel)find(panel,"test.images.details")).getText());
                history.undo();panel.refresh();assertEquals(selected.key(),((ImageResources.Entry)list.getSelectedValue()).key());
                assertEquals(d.animations(),history.state().model().animations());
                panel.setSize(300,650);layout(panel);
                var screenshot=new BufferedImage(300,650,BufferedImage.TYPE_INT_RGB);var g=screenshot.createGraphics();panel.printAll(g);g.dispose();
                Path target=Path.of("target/image-resources-panel.png");Files.createDirectories(target.getParent());ImageIO.write(screenshot,"PNG",target.toFile());
            } catch(Exception failure) { throw new RuntimeException(failure); }
            assertNull(error.get());
        });
    }
    static Component find(Container parent,String name) {
        for(var c:parent.getComponents()) {if(name.equals(c.getName())) return c;if(c instanceof Container container) {var found=find(container,name);if(found!=null) return found;}}return null;
    }
    static void layout(Container c) {c.doLayout();for(var child:c.getComponents()) if(child instanceof Container container) layout(container);}
}
