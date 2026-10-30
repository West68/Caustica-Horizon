package dev.comfyfluffy.caustica.streamline;
import java.util.concurrent.atomic.LongAdder;
/** Opt-in API wall-clock costs. These are NOT GPU execution timings. */
public final class StreamlineCallTimings {
    public static final String[] NAMES={"acquire","present","deviceIdle","inputRetirement","reflexSleep","dlssgGetState","dlssgSetOptions"};
    private static final boolean ENABLED=Boolean.getBoolean("caustica.rt.fgTimings");
    private static final LongAdder[] nanos=new LongAdder[NAMES.length],calls=new LongAdder[NAMES.length];
    static {for(int i=0;i<NAMES.length;i++){nanos[i]=new LongAdder();calls[i]=new LongAdder();}}
    static long begin(){return ENABLED?System.nanoTime():0;}
    static void end(int category,long begin){if(begin!=0){nanos[category].add(System.nanoTime()-begin);calls[category].increment();}}
    public static long[] snapshot(){long[] out=new long[NAMES.length*2];for(int i=0;i<NAMES.length;i++){out[2*i]=nanos[i].sum();out[2*i+1]=calls[i].sum();}return out;}
    private StreamlineCallTimings(){}
}
