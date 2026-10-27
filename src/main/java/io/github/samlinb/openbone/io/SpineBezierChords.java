package io.github.samlinb.openbone.io;

import java.util.*;

/** The runtime's first-crossing ten-chord function, including looping time handles. */
final class SpineBezierChords {
    private SpineBezierChords() {}
    record Points(double[] x,double[] y) {}
    static Points points(double startTime,double endTime,double start,double end,double[] c) {
        var x=new double[11];var y=new double[11];
        x[0]=startTime;y[0]=start;x[10]=endTime;y[10]=end;
        for(int i=1;i<10;i++) {
            double u=i/10.,v=1-u;
            x[i]=v*v*v*startTime+3*v*v*u*c[0]+3*v*u*u*c[2]+u*u*u*endTime;
            y[i]=v*v*v*start+3*v*v*u*c[1]+3*v*u*u*c[3]+u*u*u*end;
        }
        return new Points(x,y);
    }
    static double sample(Points p,double time) {
        if(time<=p.x()[0])return p.y()[0];
        if(time>=p.x()[10])return p.y()[10];
        int i=1;while(i<10&&time>p.x()[i])i++;
        return p.y()[i-1]+(p.y()[i]-p.y()[i-1])*(time-p.x()[i-1])/(p.x()[i]-p.x()[i-1]);
    }
    static void times(NavigableSet<Double> times,Points p,boolean clampColor) {
        double start=p.x()[0],end=p.x()[10],high=start;
        times.add(start);
        for(int i=1;i<=10&&high<end;i++) {
            double x=p.x()[i];
            if(x<=high)continue;
            double limit=Math.min(end,x);
            if(high>start&&high<end) {
                times.add(high);
                // A looping time handle can jump when the first visible chord
                // changes. Preserve both sides at the next runtime float time;
                // native exports cannot represent adjacent-double key seconds.
                double nextValue=p.y()[i-1]+(p.y()[i]-p.y()[i-1])*(high-p.x()[i-1])/(x-p.x()[i-1]);
                double current=sample(p,high);
                if(Math.abs(nextValue-current)>1e-12*(1+Math.max(Math.abs(nextValue),Math.abs(current)))) {
                    double after=Math.nextUp((float)high);if(after<limit)times.add(after);
                }
            }
            if(limit<end)times.add(limit);
            if(clampColor&&p.y()[i]!=p.y()[i-1])for(double boundary:new double[]{0,1}) {
                double crossing=p.x()[i-1]+(boundary-p.y()[i-1])*(x-p.x()[i-1])/(p.y()[i]-p.y()[i-1]);
                if(crossing>high&&crossing<limit)times.add(crossing);
            }
            high=limit;
        }
    }
}
