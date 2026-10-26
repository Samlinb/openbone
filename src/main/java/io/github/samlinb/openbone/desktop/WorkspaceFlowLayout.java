package io.github.samlinb.openbone.desktop;

import java.awt.*;

/** Flow rows report their wrapped height so scaled toolbars never hide actions. */
final class WorkspaceFlowLayout extends FlowLayout {
    WorkspaceFlowLayout(int alignment,int horizontal,int vertical) { super(alignment,horizontal,vertical); }
    @Override public Dimension preferredLayoutSize(Container target) { return wrapped(target); }
    @Override public Dimension minimumLayoutSize(Container target) { return wrapped(target); }
    private Dimension wrapped(Container target) {
        synchronized(target.getTreeLock()) {
            Insets in=target.getInsets();int width=target.getWidth();
            if(width<=0&&target.getParent()!=null)width=target.getParent().getWidth();
            if(width<=0)width=1000;
            int available=Math.max(1,width-in.left-in.right-2*getHgap());
            int rowWidth=0,rowHeight=0,totalHeight=0,maxWidth=0;
            for(Component c:target.getComponents())if(c.isVisible()){
                Dimension d=c.getPreferredSize();
                if(rowWidth>0&&rowWidth+getHgap()+d.width>available){
                    maxWidth=Math.max(maxWidth,rowWidth);totalHeight+=rowHeight+getVgap();rowWidth=0;rowHeight=0;
                }
                if(rowWidth>0)rowWidth+=getHgap();rowWidth+=d.width;rowHeight=Math.max(rowHeight,d.height);
            }
            return new Dimension(Math.max(maxWidth,rowWidth)+in.left+in.right+getHgap()*2,totalHeight+rowHeight+in.top+in.bottom+getVgap()*2);
        }
    }
}
