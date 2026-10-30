package dev.comfyfluffy.caustica.streamline;
import java.util.concurrent.atomic.AtomicLong;
/** GLFW callbacks may run on Ixeris' event thread; SDK calls stay on the render thread. */
final class WindowTransitionEpoch {
    private final AtomicLong requested=new AtomicLong();
    private long building, committed;
    void changed() { requested.incrementAndGet(); }
    void configurationStarted() { building=requested.get(); }
    void configurationCompleted() { committed=building; }
    boolean pending() { return requested.get()!=committed; }
}
