package io.github.samlinb.openbone.desktop;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/** Original line icons painted in logical coordinates for HiDPI. */
final class WorkspaceIcon implements Icon {
    enum Kind { SELECT, BONE, MOVE, ROTATE, SCALE, SAVE, OPEN, NEW, UNDO, REDO, PLAY, PAUSE, START, KEY, CURVE, LAYERS }
    private final Kind kind;
    WorkspaceIcon(Kind kind) { this.kind=kind; }
    public int getIconWidth() { return WorkspaceTheme.scale(16); }
    public int getIconHeight() { return WorkspaceTheme.scale(16); }
    public void paintIcon(Component c, Graphics raw, int x, int y) {
        var g=(Graphics2D)raw.create();
        try {
            g.translate(x,y);double scale=getIconWidth()/16.;g.scale(scale,scale);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.35f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.setColor(c.isEnabled()?c.getForeground():WorkspaceTheme.MUTED);
            switch(kind) {
                case SELECT -> path(g,2,1,3,13,6,9,10,14,12,13,8,8,13,7,2,1);
                case BONE -> { path(g,4,3,12,11);g.drawOval(1,0,5,5);g.drawOval(10,10,5,5); }
                case MOVE -> {path(g,8,1,8,15);path(g,1,8,15,8);path(g,5,4,8,1,11,4);path(g,5,12,8,15,11,12);path(g,4,5,1,8,4,11);path(g,12,5,15,8,12,11);}
                case ROTATE -> {g.draw(new Arc2D.Double(2,2,12,12,40,285,Arc2D.OPEN));path(g,10,1,14,2,13,6);}
                case SCALE -> {g.drawRect(2,7,7,7);path(g,8,8,14,2);path(g,9,2,14,2,14,7);}
                case SAVE -> {g.drawRect(2,2,12,12);g.drawRect(5,9,6,5);path(g,5,2,5,6,11,6,11,2);}
                case OPEN -> path(g,1,5,1,3,6,3,8,5,14,5,13,13,2,13,1,5,14,5);
                case NEW -> path(g,9,1,3,1,3,15,13,15,13,5,9,1,9,5,13,5);
                case UNDO, REDO -> {if(kind==Kind.REDO){g.translate(16,0);g.scale(-1,1);}path(g,5,2,1,6,5,10);g.draw(new Arc2D.Double(1,6,13,9,0,180,Arc2D.OPEN));}
                case PLAY -> path(g,4,2,13,8,4,14,4,2);
                case PAUSE -> {g.drawLine(5,2,5,14);g.drawLine(11,2,11,14);}
                case START -> {g.drawLine(2,2,2,14);path(g,13,2,4,8,13,14,13,2);}
                case KEY -> path(g,8,1,14,8,8,15,2,8,8,1);
                case CURVE -> {var p=new Path2D.Double();p.moveTo(1,13);p.curveTo(8,13,5,3,15,3);g.draw(p);g.fillRect(0,12,3,3);g.fillRect(13,1,3,3);}
                case LAYERS -> {path(g,1,5,8,1,15,5,8,9,1,5);path(g,1,9,8,13,15,9);}
            }
        } finally { g.dispose(); }
    }
    private static void path(Graphics2D g, double... coords) {
        var p=new Path2D.Double();p.moveTo(coords[0],coords[1]);
        for(int i=2;i<coords.length;i+=2)p.lineTo(coords[i],coords[i+1]);g.draw(p);
    }
}
