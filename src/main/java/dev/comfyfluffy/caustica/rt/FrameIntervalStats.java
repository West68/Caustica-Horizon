package dev.comfyfluffy.caustica.rt;

/** P99 and the reciprocal mean of the slowest 1% are distinct statistics. */
final class FrameIntervalStats {
    record Result(double p99Millis, double onePercentLowFps) {}
    static Result summarize(long[] source, int count) {
        if(count<=0)return new Result(0,0);
        long[] sorted=java.util.Arrays.copyOf(source,count);
        java.util.Arrays.sort(sorted);
        int p99=Math.min(count-1,(int)Math.ceil(count*.99)-1);
        int tail=Math.max(1,(int)Math.ceil(count*.01));
        double sum=0;
        for(int i=count-tail;i<count;i++)sum+=sorted[i];
        return new Result(sorted[p99]/1e6,sum>0?1e9*tail/sum:0);
    }
    private FrameIntervalStats() {}
}
