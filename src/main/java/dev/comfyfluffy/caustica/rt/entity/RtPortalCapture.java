package dev.comfyfluffy.caustica.rt.entity;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Block entities that must survive the raster visibility gate.
 *
 * <p>{@code tryExtractRenderState} is a raster gate as much as an extractor: it folds in distance and
 * occlusion decisions that only make sense for the direct view. Two families cannot use it:
 * <ul>
 *   <li>portal surfaces, which secondary rays must still reach through primary-camera occlusion;</li>
 *   <li>beacon beams, which are a custom-geometry column submitted far above the block and are dropped
 *       by that same gate, leaving an activated beacon with no visible beam in the RT scene.</li>
 * </ul>
 * Both keep renderer validity checks. Portal surfaces use the RT chunk range instead of the
 * renderer's distance gate. Beacons retain their renderer distance check.
 */
final class RtPortalCapture {
    private RtPortalCapture() {}

    static BlockEntityRenderState extract(BlockEntityRenderDispatcher dispatcher, BlockEntity entity,
            float partialTick, Vec3 camera) {
        if (isPortal(entity))
            return extractUngated(dispatcher.getRenderer(entity), entity, partialTick, camera, false);
        if (entity instanceof BeaconBlockEntity)
            return extractUngated(dispatcher.getRenderer(entity), entity, partialTick, camera, true);
        return dispatcher.tryExtractRenderState(entity, partialTick, null, false);
    }

    static boolean isPortal(BlockEntity entity) {
        return entity instanceof TheEndPortalBlockEntity;
    }

    private static <E extends BlockEntity, S extends BlockEntityRenderState> S extractUngated(
            BlockEntityRenderer<E, S> renderer, E entity, float partialTick, Vec3 camera, boolean checkDistance) {
        if (renderer == null || !entity.hasLevel() || !entity.getType().isValid(entity.getBlockState())
                || (checkDistance && !renderer.shouldRender(entity, camera))) return null;
        S state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, partialTick, camera, null);
        return state;
    }
}
