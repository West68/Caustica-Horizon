package dev.comfyfluffy.caustica.client;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * How much LOD work Distant Horizons still has queued. Every {@code WorldGenerationQueue} registers itself on
 * construction (DhGenerationQueueMixin); a closed queue is simply garbage collected. DH is not a compile
 * dependency, so the counters are read reflectively.
 */
public final class DhLodProgress {
    private static final Set<Object> QUEUES = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Map<Class<?>, Method[]> METHODS = Collections.synchronizedMap(new WeakHashMap<>());
    private DhLodProgress() {}

    public static void register(Object queue) { QUEUES.add(queue); }

    /** Waiting + running generation tasks over all live queues; -1 when DH has no queue. */
    public static int pending() {
        Object[] queues;
        synchronized (QUEUES) { queues = QUEUES.toArray(); }
        if (queues.length == 0) return -1;
        int n = 0;
        for (Object q : queues) {
            try {
                Method[] m = METHODS.computeIfAbsent(q.getClass(), c -> {
                    try { return new Method[]{c.getMethod("getWaitingTaskCount"), c.getMethod("getInProgressTaskCount")}; }
                    catch (NoSuchMethodException e) { return new Method[0]; }
                });
                if (m.length == 2) n += (int) m[0].invoke(q) + (int) m[1].invoke(q);
            } catch (ReflectiveOperationException ignored) {}
        }
        return n;
    }
}
