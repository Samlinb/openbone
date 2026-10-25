package io.github.samlinb.openbone.viewport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in bounded histograms: 0.01ms bins through 1000ms, overflow retained in max.
 * Work boundary starts before backend input/update/render, ends AFTER swap + GPU finish.
 * Start-to-start interval also includes Swing event/timer work, scheduling and backend sync.
 * Both metrics are reported, neither is a CPU-only pose timer. */
final class FrameTelemetry {
    private final long[] work=new long[100001],interval=new long[100001];
    private long count,intervalCount,previous,start,first=System.nanoTime(),last=first,maxWork,maxInterval;
    private final Path path;
    FrameTelemetry(){String s=System.getProperty("openbone.performance");path=s==null?null:Path.of(s);}
    boolean enabled(){return path!=null;}
    void begin(){if(!enabled())return;start=System.nanoTime();if(previous!=0){long n=start-previous;interval[(int)Math.min(interval.length-1,n/10000)]++;intervalCount++;maxInterval=Math.max(maxInterval,n);}previous=start;}
    void end(boolean visible,Map<String,Object> viewport){if(!enabled()||start==0)return;long now=System.nanoTime(),n=now-start;work[(int)Math.min(work.length-1,n/10000)]++;count++;maxWork=Math.max(maxWork,n);start=0;if(now-last>=10_000_000_000L){write(visible,viewport);last=now;}}
    private static double p(long[] h,long count,double q){long target=(long)Math.ceil(count*q),seen=0;for(int i=0;i<h.length;i++){seen+=h[i];if(seen>=target)return (i+1)/100.;}return 0;}
    Map<String,Object> summary(){var m=new LinkedHashMap<String,Object>();m.put("frames",count);m.put("seconds",(System.nanoTime()-first)/1e9);m.put("workP50Ms",p(work,count,.5));m.put("workP95Ms",p(work,count,.95));m.put("workMaxMs",maxWork/1e6);m.put("intervalP50Ms",p(interval,intervalCount,.5));m.put("intervalP95Ms",p(interval,intervalCount,.95));m.put("intervalMaxMs",maxInterval/1e6);return m;}
    void write(boolean visible,Map<String,Object> viewport){if(!enabled())return;
        try{var m=new LinkedHashMap<>(summary());m.put("timeUTC",java.time.Instant.now().toString());m.put("visible",visible);m.put("viewport",viewport);var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();m.put("heapUsed",heap.getUsed());m.put("heapCommitted",heap.getCommitted());m.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());m.put("gc",ManagementFactory.getGarbageCollectorMXBeans().stream().map(g->Map.of("name",g.getName(),"count",g.getCollectionCount(),"ms",g.getCollectionTime())).toList());Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,new ObjectMapper().writeValueAsString(m)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){System.err.println("Performance log: "+e);}
    }
}
