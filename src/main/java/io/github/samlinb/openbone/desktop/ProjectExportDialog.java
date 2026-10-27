package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import javax.swing.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** File conversion and optional external CLI execution never block Swing's event thread. */
final class ProjectExportDialog {
    private ProjectExportDialog() {}
    static Path run(Window owner,Callable<Path> task)throws Exception {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Export dialog requires EDT");
        var dialog=new JDialog(owner,m("spineExport.progress"),Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("spineExport.progress");dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        var panel=new JPanel(new BorderLayout(0,12));panel.setBorder(BorderFactory.createEmptyBorder(20,24,20,24));
        panel.add(new JLabel(m("spineExport.progress")),BorderLayout.NORTH);var progress=new JProgressBar();progress.setIndeterminate(true);panel.add(progress,BorderLayout.CENTER);
        dialog.setContentPane(panel);dialog.pack();dialog.setLocationRelativeTo(owner);
        var worker=new SwingWorker<Path,Void>() {
            protected Path doInBackground()throws Exception{return task.call();}
            protected void done(){dialog.dispose();}
        };
        dialog.addWindowListener(new WindowAdapter(){public void windowOpened(WindowEvent event){worker.execute();}public void windowClosed(WindowEvent event){if(!worker.isDone())worker.cancel(true);}});
        dialog.setVisible(true);
        try{return worker.get();}catch(ExecutionException failed){if(failed.getCause() instanceof Exception cause)throw cause;throw new RuntimeException(failed.getCause());}
        catch(CancellationException cancelled){return null;}
    }
}
