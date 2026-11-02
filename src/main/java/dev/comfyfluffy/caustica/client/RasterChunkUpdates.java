package dev.comfyfluffy.caustica.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import java.lang.ref.WeakReference;

/** Retains net loading notifications while RT skips LevelRenderer's occlusion-graph update. */
public final class RasterChunkUpdates {
    private WeakReference<Object> world = new WeakReference<>(null);
    private final LongOpenHashSet addedChunks = new LongOpenHashSet(), removedChunks = new LongOpenHashSet();
    private final LongOpenHashSet addedEmpty = new LongOpenHashSet(), removedEmpty = new LongOpenHashSet();
    private final LongOpenHashSet expected = new LongOpenHashSet();
    private boolean deferred;

    private void observe(Object currentWorld) {
        if (world.get() != currentWorld) {
            clear();
            world = new WeakReference<>(currentWorld);
        }
    }

    public void defer(Object currentWorld, ChunkLoadingRenderState state) {
        observe(currentWorld);
        merge(addedChunks, removedChunks, state.addedLoadedChunks, state.removedLoadedChunks);
        merge(addedEmpty, removedEmpty, state.addedEmptySections, state.removedEmptySections);
        if (state.loadedExpectedChunks != null) expected.addAll(state.loadedExpectedChunks);
        if (state.removedLoadedChunks != null) expected.removeAll(state.removedLoadedChunks);
        deferred = true;
    }

    /** Current-frame notifications take precedence. Never mutate the chunk cache's borrowed sets. */
    public boolean replay(Object currentWorld, ChunkLoadingRenderState state) {
        observe(currentWorld);
        if (!deferred) return false;
        defer(currentWorld, state);
        state.addedLoadedChunks = new LongOpenHashSet(addedChunks);
        state.removedLoadedChunks = new LongOpenHashSet(removedChunks);
        state.addedEmptySections = new LongOpenHashSet(addedEmpty);
        state.removedEmptySections = new LongOpenHashSet(removedEmpty);
        state.loadedExpectedChunks = new LongOpenHashSet(expected);
        clear();
        return true;
    }

    private static void merge(LongOpenHashSet added, LongOpenHashSet removed,
                              LongOpenHashSet newAdded, LongOpenHashSet newRemoved) {
        if (newAdded != null && !newAdded.isEmpty()) for (long key : newAdded)
            if (!removed.remove(key)) added.add(key);
        if (newRemoved != null && !newRemoved.isEmpty()) for (long key : newRemoved)
            if (!added.remove(key)) removed.add(key);
    }

    private void clear() {
        addedChunks.clear(); removedChunks.clear(); addedEmpty.clear(); removedEmpty.clear(); expected.clear();
        deferred = false;
    }
}
