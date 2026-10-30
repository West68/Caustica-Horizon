package dev.comfyfluffy.caustica.rt.entity;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Portal surfaces must survive primary-camera occlusion so secondary rays can reach them. */
final class RtPortalCapture {
    private RtPortalCapture() {}

    static BlockEntityRenderState extract(BlockEntityRenderDispatcher dispatcher, BlockEntity entity,
            float partialTick, Vec3 camera) {
        if (entity.getClass() != TheEndPortalBlockEntity.class)
            return dispatcher.tryExtractRenderState(entity, partialTick, null, false);
        return extractPortal(dispatcher.getRenderer(entity), entity, partialTick, camera);
    }

    private static <E extends BlockEntity, S extends BlockEntityRenderState> S extractPortal(
            BlockEntityRenderer<E, S> renderer, E entity, float partialTick, Vec3 camera) {
        if (renderer == null || !entity.hasLevel() || !entity.getType().isValid(entity.getBlockState())
                || !renderer.shouldRender(entity, camera)) return null;
        // tryExtractRenderState is also a raster visibility gate (including EntityCulling).
        // Retain the renderer's validity/distance checks and its own face extraction, but never
        // use the direct camera's occlusion decision to remove a portal from the RT scene.
        S state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, partialTick, camera, null);
        return state;
    }
}
