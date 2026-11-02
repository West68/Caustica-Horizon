package dev.comfyfluffy.caustica.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RasterChunkUpdatesTest {
    private static ChunkLoadingRenderState state() {
        var s = new ChunkLoadingRenderState();
        s.addedLoadedChunks = new LongOpenHashSet(); s.removedLoadedChunks = new LongOpenHashSet();
        s.addedEmptySections = new LongOpenHashSet(); s.removedEmptySections = new LongOpenHashSet();
        s.loadedExpectedChunks = new LongOpenHashSet(); return s;
    }
    @Test void copiesBorrowedNotificationsAndReplaysOnlyOnce() {
        Object world = new Object(); var queue = new RasterChunkUpdates(); var s = state();
        s.addedLoadedChunks.add(10); s.addedEmptySections.add(20); s.loadedExpectedChunks.add(10);
        queue.defer(world, s); s.addedLoadedChunks.clear(); s.addedEmptySections.clear();
        var out = state(); assertTrue(queue.replay(world, out));
        assertTrue(out.addedLoadedChunks.contains(10)); assertTrue(out.addedEmptySections.contains(20));
        assertTrue(out.loadedExpectedChunks.contains(10)); assertFalse(queue.replay(world, state()));
    }
    @Test void cancellingLoadUnloadPairsDoNotAccumulateTravelHistory() {
        Object world = new Object(); var queue = new RasterChunkUpdates();
        for (long i = 0; i < 1000; i++) {
            var load = state(); load.addedLoadedChunks.add(i); queue.defer(world, load);
            var unload = state(); unload.removedLoadedChunks.add(i); queue.defer(world, unload);
        }
        var out = state(); queue.replay(world, out);
        assertTrue(out.addedLoadedChunks.isEmpty()); assertTrue(out.removedLoadedChunks.isEmpty());
    }
    @Test void currentFrameUnloadsOverrideMissedLoadsAndExpectedReadiness() {
        Object world = new Object(); var queue = new RasterChunkUpdates(); var load = state();
        load.addedLoadedChunks.add(10); load.loadedExpectedChunks.add(10); queue.defer(world, load);
        var now = state(); now.removedLoadedChunks.add(10); queue.replay(world, now);
        assertFalse(now.addedLoadedChunks.contains(10)); assertFalse(now.loadedExpectedChunks.contains(10));
    }
    @Test void worldChangeDropsOldCoordinatesAndNormalFramesKeepBorrowedSets() {
        var queue = new RasterChunkUpdates(); var old = state(); old.addedLoadedChunks.add(10);
        queue.defer(new Object(), old); var next = state(); var borrowed = next.addedLoadedChunks;
        assertFalse(queue.replay(new Object(), next)); assertSame(borrowed, next.addedLoadedChunks);
        assertTrue(next.addedLoadedChunks.isEmpty());
    }
}
