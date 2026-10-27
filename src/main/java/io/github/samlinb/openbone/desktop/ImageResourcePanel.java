package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import io.github.samlinb.openbone.application.*;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.io.ImageResources;
import static io.github.samlinb.openbone.i18n.M.m;

/** Shared image browser for the main workspace and the resource dialog. */
final class ImageResourcePanel extends JPanel {
    private final Supplier<CommandHistory> history;
    private final Supplier<Path> base;
    private final Runnable changed;
    private final Consumer<Exception> error;
    private final BooleanSupplier prepare;
    private final Consumer<EntityId> locate;
    private final DefaultListModel<ImageResources.Entry> rows=new DefaultListModel<>();
    private final JList<ImageResources.Entry> list=new JList<>(rows);
    private final Map<ImageResources.Key,BufferedImage> cache=new HashMap<>();
    private final Map<ImageResources.Key,ImageIcon> icons=new HashMap<>();
    private final Map<ImageResources.Key,String> failures=new HashMap<>();
    private final Supplier<ImageResources.PreviewCache> previewCaches;
    private SwingWorker<Void,LoadedPreview> previewWorker;
    private long previewGeneration;
    private java.util.List<Attachment> listedAttachments;
    private Map<EntityId,AtlasRegion> listedAtlas;
    private Path listedBase;
    private record LoadedPreview(ImageResources.Key key,BufferedImage image,ImageIcon icon,String failure) {}
    private final Preview preview=new Preview();
    private final JLabel details=new JLabel(" ");
    private final JButton open=new JButton(m("images.preview")),replace=new JButton(m("images.replace")),show=new JButton(m("images.locate"));
    private boolean refreshing;

    ImageResourcePanel(String name,Supplier<CommandHistory> history,Supplier<Path> base,BooleanSupplier prepare,
            Runnable changed,Consumer<Exception> error,Consumer<EntityId> locate) {
        this(name,history,base,prepare,changed,error,locate,ImageResources.PreviewCache::new);
    }
    ImageResourcePanel(String name,Supplier<CommandHistory> history,Supplier<Path> base,BooleanSupplier prepare,
            Runnable changed,Consumer<Exception> error,Consumer<EntityId> locate,Supplier<ImageResources.PreviewCache> previewCaches) {
        super(new BorderLayout(5,5));
        this.history=history;this.base=base;this.prepare=prepare;this.changed=changed;this.error=error;this.locate=locate;
        this.previewCaches=previewCaches;
        setName(name);setBorder(BorderFactory.createEmptyBorder(5,5,5,5));
        list.setName(name+".list");list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setFixedCellHeight(58);
        list.setCellRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> owner,Object value,int index,boolean selected,boolean focus) {
                var label=(JLabel)super.getListCellRendererComponent(owner,value,index,selected,focus);
                if(value instanceof ImageResources.Entry entry) {
                    label.setText(entry.name());label.setToolTipText(entry.key().source().toString());
                    // Swing invokes renderers during layout as well as painting.
                    // Never read files, decode pages or allocate thumbnails here.
                    label.setIcon(icons.get(entry.key()));
                }
                return label;
            }
        });
        list.addListSelectionListener(e->{if(!refreshing&&!e.getValueIsAdjusting()) updatePreview();});
        list.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2) openPreview();}});
        var bottom=new JPanel(new BorderLayout(4,4));bottom.add(preview);bottom.add(details,BorderLayout.SOUTH);
        var split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),bottom);split.setResizeWeight(.65);split.setDividerLocation(270);
        preview.setPreferredSize(new Dimension(180,170));add(split);
        preview.setName(name+".image");details.setName(name+".details");
        var actions=new JPanel(new GridLayout(0,1,4,4));
        open.setName(name+".preview");replace.setName(name+".replace");show.setName(name+".locate");
        replace.setToolTipText(m("images.replaceHint"));
        actions.add(open);actions.add(replace);actions.add(show);add(actions,BorderLayout.SOUTH);
        open.addActionListener(e->openPreview());replace.addActionListener(e->replaceImage());
        show.addActionListener(e->{var entry=list.getSelectedValue();if(entry!=null&&prepare.getAsBoolean()) locate.accept(entry.attachments().get(0));});
        refresh();
    }

    private BufferedImage image(ImageResources.Entry entry) {
        return cache.get(entry.key());
    }

    private void loadPreviews(java.util.List<ImageResources.Entry> entries,boolean changedEntries) {
        if(!changedEntries&&previewWorker!=null)return;
        if(changedEntries&&previewWorker!=null)previewWorker.cancel(true);
        var pending=entries.stream().filter(e->!cache.containsKey(e.key())&&!failures.containsKey(e.key())).toList();
        long generation=++previewGeneration;
        if(pending.isEmpty()){previewWorker=null;return;}
        previewWorker=new SwingWorker<>() {
            @Override protected Void doInBackground() {
                var decoded=previewCaches.get();
                for(var entry:pending) {
                    if(isCancelled())break;
                    LoadedPreview result;
                    try {
                        var image=decoded.preview(entry);
                        var thumbnail=new BufferedImage(48,48,BufferedImage.TYPE_INT_ARGB);var g=thumbnail.createGraphics();
                        paintImage(g,image,48,48);g.dispose();
                        result=new LoadedPreview(entry.key(),image,new ImageIcon(thumbnail),null);
                    } catch(Exception invalid) {result=new LoadedPreview(entry.key(),null,null,invalid.getMessage());}
                    if(!isCancelled())publish(result);
                }
                return null;
            }
            @Override protected void process(java.util.List<LoadedPreview> results) {
                if(generation!=previewGeneration||isCancelled())return;
                for(var result:results) {
                    if(result.image()!=null){cache.put(result.key(),result.image());icons.put(result.key(),result.icon());}
                    else failures.put(result.key(),result.failure());
                }
                list.repaint();updatePreview();
            }
        };
        previewWorker.execute();
    }

    void refresh() {
        var model=history.get().state().model();var directory=base.get();
        // Playback/seek refreshes have identical immutable image inputs. Do not
        // rebuild the list or restart workers on every captured animation frame.
        if(model.attachments()==listedAttachments&&model.resources().atlas()==listedAtlas&&Objects.equals(directory,listedBase))return;
        refreshing=true;
        try {
            var selected=list.getSelectedValue();var entries=ImageResources.list(model,directory);
            listedAttachments=model.attachments();listedAtlas=model.resources().atlas();listedBase=directory;
            // Retain decoded thumbnails when only bones, selection or animation keys changed.
            var keys=new HashSet<>(entries.stream().map(ImageResources.Entry::key).toList());
            cache.keySet().retainAll(keys);icons.keySet().retainAll(keys);failures.keySet().retainAll(keys);
            boolean changedEntries=!entries.equals(Collections.list(rows.elements()));
            if(changedEntries) {rows.clear();rows.addAll(entries);}
            int index=-1;
            for(int i=0;i<rows.size();i++) if(selected!=null && (rows.get(i).key().equals(selected.key())
                    || rows.get(i).attachments().stream().anyMatch(selected.attachments()::contains))) {index=i;break;}
            if(index<0&&!rows.isEmpty()) index=0;
            list.setSelectedIndex(index);
            loadPreviews(entries,changedEntries);
        } catch(Exception invalid){listedAttachments=null;rows.clear();details.setText(m("images.empty"));error.accept(invalid);}
        finally {refreshing=false;}
        updatePreview();
    }

    private void updatePreview() {
        var entry=list.getSelectedValue();var image=entry==null?null:image(entry);preview.image=image;preview.repaint();
        open.setEnabled(image!=null);replace.setEnabled(entry!=null);show.setEnabled(entry!=null);
        if(entry==null) {details.setText(m("images.empty"));details.setToolTipText(null);return;}
        details.setText((image==null?(failures.containsKey(entry.key())?m("images.missing"):"…"):image.getWidth()+" × "+image.getHeight())+" · "+entry.attachments().size()+" "+m("images.references"));
        details.setToolTipText(image==null&&failures.containsKey(entry.key())?failures.get(entry.key()):entry.key().source().toString());
    }

    private void openPreview() {
        var entry=list.getSelectedValue();if(entry==null) return;var image=image(entry);if(image==null) return;
        var dialog=new JDialog(SwingUtilities.getWindowAncestor(this),entry.name(),Dialog.ModalityType.MODELESS);
        var label=new JLabel(new ImageIcon(image));label.setOpaque(true);label.setBackground(new Color(60,60,65));
        dialog.add(new JScrollPane(label));dialog.setSize(600,500);dialog.setLocationRelativeTo(this);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setVisible(true);
    }

    private void replaceImage() {
        var entry=list.getSelectedValue();if(entry==null||!prepare.getAsBoolean()) return;
        var chooser=new JFileChooser(entry.key().source().getParent().toFile());
        chooser.setDialogTitle(m("images.replace"));chooser.setFileFilter(new FileNameExtensionFilter("PNG","png"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        try {
            var commandHistory=history.get();
            var result=ImageResources.replace(commandHistory.state().model(),base.get(),entry,chooser.getSelectedFile().toPath());
            commandHistory.execute(m("images.replace"),Commands.model(d->result));
            cache.clear();icons.clear();failures.clear();listedAttachments=null;
            if(previewWorker!=null)previewWorker.cancel(true);previewWorker=null;previewGeneration++;
            changed.run();refresh();
        } catch(Exception invalid){error.accept(invalid);}
    }

    private static void paintImage(Graphics2D g,BufferedImage image,int w,int h) {
        for(int y=0;y<h;y+=12) for(int x=0;x<w;x+=12) {g.setColor(((x/12+y/12)%2)==0?new Color(55,55,60):new Color(75,75,80));g.fillRect(x,y,12,12);}
        double scale=Math.min((double)w/image.getWidth(),(double)h/image.getHeight());int width=Math.max(1,(int)(image.getWidth()*scale)),height=Math.max(1,(int)(image.getHeight()*scale));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,(w-width)/2,(h-height)/2,width,height,null);
    }
    private static final class Preview extends JPanel {
        BufferedImage image;
        @Override protected void paintComponent(Graphics graphics) {super.paintComponent(graphics);if(image!=null) {var g=(Graphics2D)graphics.create();paintImage(g,image,getWidth(),getHeight());g.dispose();}}
    }
}
