package dev.comfyfluffy.caustica.rt;

/** CPU-only aggregate of completed timestamp queries, with no wait for unavailable GPU results. */
final class GpuTimingWindow {
    private final double[] sums;
    private final long[] counts;
    private boolean active;
    private long firstFrame;
    GpuTimingWindow(int spans){sums=new double[spans];counts=new long[spans];}
    void begin(long frame){firstFrame=frame;java.util.Arrays.fill(sums,0);java.util.Arrays.fill(counts,0);active=true;}
    void add(long frame,int span,double millis){if(active&&frame>=firstFrame&&Double.isFinite(millis)&&millis>=0){sums[span]+=millis;counts[span]++;}}
    long count(int span){return counts[span];}
    double mean(int span){return counts[span]>0?sums[span]/counts[span]:Double.NaN;}
    void end(){active=false;}
}
