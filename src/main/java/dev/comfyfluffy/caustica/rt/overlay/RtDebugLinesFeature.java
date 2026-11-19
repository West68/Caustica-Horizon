package dev.comfyfluffy.caustica.rt.overlay;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

/**
 * Vanilla line/wall visuals that only exist inside the cancelled level renderer: the world border (drawn as
 * a coloured grid on the border plane), entity hitboxes (F3+B) and chunk borders (F3+G). Same native line
 * list, per-fragment TLAS occlusion and MSAA mask/composite split as {@link RtBlockOutlineFeature}; each
 * colour is one draw range. The full-resolution scratch images exist only while something is drawn.
 */
final class RtDebugLinesFeature implements RtOverlayFeature {
    private static final int PUSH_BYTES = 96;
    private static final float LINE_WIDTH_PX_AT_REFERENCE = 1.5f;
    private static final float REFERENCE_HEIGHT = 1080f;
    private static final double BORDER_RANGE = 64.0;
    private static final double HITBOX_RANGE = 64.0;

    private RtContext ctxRef;
    private RtOverlayPipelines.Pipeline pipeline;
    private RtOverlayPipelines.AccelStructureSet accelSet;
    private RtOverlayPipelines.Pipeline compositePipeline;
    private RtOverlayPipelines.StorageImageSet compositeSet;
    private RtImage msaaImage;
    private RtImage resolvedMask;

    private final Matrix4f viewProj = new Matrix4f();
    private final FloatArrayList verts = new FloatArrayList(1024);
    /** Per colour range: first vertex, vertex count, r, g, b, a. */
    private float[] groups = new float[6 * 16];
    private int groupCount;
    private int groupStart;
    private int idleFrames;
    private RtBuffer vbo;
    private long boundSet;
    private double ox, oy, oz;

    @Override
    public boolean prepare(RtContext ctx, RtOverlayFramePool pool, RtGpuExecutor.GraphicsUse graphicsUse,
                           int width, int height) {
        RtTerrain terrain = RtTerrain.currentOrNull();
        long tlas = RtComposite.INSTANCE.currentTlasHandle();
        Minecraft mc = Minecraft.getInstance();
        if (terrain == null || tlas == 0L || mc.level == null) {
            return idle();
        }
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        ox = terrain.blockX;
        oy = terrain.blockY;
        oz = terrain.blockZ;
        verts.clear();
        groupCount = 0;
        groupStart = 0;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        worldBorder(mc, cam, partial);
        if (!mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden) {
            if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) hitboxes(mc, cam, partial);
            if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.CHUNK_BORDERS)) chunkBorders(mc, cam);
        }
        if (groupCount == 0) {
            return idle();
        }

        ensureResources(ctx, width, height);
        int floats = verts.size();
        vbo = pool.acquireVertex(ctx, (long) floats * Float.BYTES, "debug lines vbo");
        MemoryUtil.memFloatBuffer(vbo.mapped, floats).put(verts.elements(), 0, floats);
        vbo.flush(0L, (long) floats * Float.BYTES);
        viewProj.set(RtComposite.INSTANCE.currentViewProjection());
        boundSet = accelSet.bind(ctx, tlas, graphicsUse);
        return true;
    }

    /** Nothing to draw: after a couple of seconds give the full-resolution scratch images back. */
    private boolean idle() {
        if (msaaImage != null && ++idleFrames > 240) {
            msaaImage.destroy();
            msaaImage = null;
            if (resolvedMask != null) {
                resolvedMask.destroy();
                resolvedMask = null;
            }
        }
        return false;
    }

    private void line(double x1, double y1, double z1, double x2, double y2, double z2) {
        verts.add((float) (x1 - ox));
        verts.add((float) (y1 - oy));
        verts.add((float) (z1 - oz));
        verts.add((float) (x2 - ox));
        verts.add((float) (y2 - oy));
        verts.add((float) (z2 - oz));
    }

    /** Close the range of lines added since the last call as one colour. */
    private void endGroup(float r, float g, float b, float a) {
        int end = verts.size() / 3;
        if (end == groupStart) return;
        if ((groupCount + 1) * 6 > groups.length) groups = java.util.Arrays.copyOf(groups, groups.length * 2);
        int c = groupCount++ * 6;
        groups[c] = groupStart;
        groups[c + 1] = end - groupStart;
        groups[c + 2] = r;
        groups[c + 3] = g;
        groups[c + 4] = b;
        groups[c + 5] = a;
        groupStart = end;
    }

    private void worldBorder(Minecraft mc, Vec3 cam, float partial) {
        var border = mc.level.getWorldBorder();
        double minX = border.getMinX(partial), maxX = border.getMaxX(partial);
        double minZ = border.getMinZ(partial), maxZ = border.getMaxZ(partial);
        double nearest = Math.min(Math.min(Math.abs(cam.x - minX), Math.abs(cam.x - maxX)),
                Math.min(Math.abs(cam.z - minZ), Math.abs(cam.z - maxZ)));
        if (nearest >= BORDER_RANGE) return;
        int rgb = border.getStatus().getColor();
        double y0 = Math.max(mc.level.getMinY(), Math.floor(cam.y) - 32.0);
        double y1 = Math.min(mc.level.getMaxY() + 1, Math.floor(cam.y) + 32.0);
        borderWall(true, minX, minZ, maxZ, cam, y0, y1);
        borderWall(true, maxX, minZ, maxZ, cam, y0, y1);
        borderWall(false, minZ, minX, maxX, cam, y0, y1);
        borderWall(false, maxZ, minX, maxX, cam, y0, y1);
        float fade = (float) (1.0 - nearest / BORDER_RANGE);
        endGroup((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f,
                Mth.clamp(0.25f + 0.75f * fade * fade, 0f, 1f));
    }

    /** A 2-block grid on one border plane, limited to the part near the camera. */
    private void borderWall(boolean xPlane, double plane, double lo, double hi, Vec3 cam, double y0, double y1) {
        double camAlong = xPlane ? cam.z : cam.x;
        if (Math.abs((xPlane ? cam.x : cam.z) - plane) >= BORDER_RANGE) return;
        double a0 = Math.max(lo, Math.floor(camAlong / 2.0) * 2.0 - 48.0);
        double a1 = Math.min(hi, Math.floor(camAlong / 2.0) * 2.0 + 48.0);
        if (a1 <= a0 || y1 <= y0) return;
        for (double a = Math.ceil(a0 / 2.0) * 2.0; a <= a1; a += 2.0) {
            if (xPlane) line(plane, y0, a, plane, y1, a);
            else line(a, y0, plane, a, y1, plane);
        }
        for (double y = Math.ceil(y0 / 2.0) * 2.0; y <= y1; y += 2.0) {
            if (xPlane) line(plane, y, a0, plane, y, a1);
            else line(a0, y, plane, a1, y, plane);
        }
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        line(x0, y0, z0, x1, y0, z0); line(x1, y0, z0, x1, y0, z1); line(x1, y0, z1, x0, y0, z1); line(x0, y0, z1, x0, y0, z0);
        line(x0, y1, z0, x1, y1, z0); line(x1, y1, z0, x1, y1, z1); line(x1, y1, z1, x0, y1, z1); line(x0, y1, z1, x0, y1, z0);
        line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x1, y0, z1, x1, y1, z1); line(x0, y0, z1, x0, y1, z1);
    }

    private boolean hitboxWanted(Minecraft mc, Entity entity, Vec3 cam) {
        return !entity.isInvisible()
                && !(entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson())
                && entity.distanceToSqr(cam) <= HITBOX_RANGE * HITBOX_RANGE;
    }

    private void hitboxes(Minecraft mc, Vec3 cam, float partial) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!hitboxWanted(mc, entity, cam)) continue;
            // The bounding box sits at the tick position; move it to the interpolated render position.
            Vec3 shift = entity.getPosition(partial).subtract(entity.position());
            AABB bb = entity.getBoundingBox();
            box(bb.minX + shift.x, bb.minY + shift.y, bb.minZ + shift.z,
                    bb.maxX + shift.x, bb.maxY + shift.y, bb.maxZ + shift.z);
        }
        endGroup(1f, 1f, 1f, 1f);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity) || !hitboxWanted(mc, entity, cam)) continue;
            Vec3 shift = entity.getPosition(partial).subtract(entity.position());
            AABB bb = entity.getBoundingBox();
            double eye = entity.getY() + shift.y + entity.getEyeHeight();
            box(bb.minX + shift.x, eye - 0.01, bb.minZ + shift.z, bb.maxX + shift.x, eye + 0.01, bb.maxZ + shift.z);
        }
        endGroup(1f, 0f, 0f, 1f);
    }

    private void chunkBorders(Minecraft mc, Vec3 cam) {
        double cx = Math.floor(cam.x / 16.0) * 16.0, cz = Math.floor(cam.z / 16.0) * 16.0;
        double y0 = mc.level.getMinY(), y1 = mc.level.getMaxY() + 1;
        // Neighbouring chunk corners.
        for (int i = -16; i <= 32; i += 16) {
            for (int j = -16; j <= 32; j += 16) {
                if (i >= 0 && i <= 16 && j >= 0 && j <= 16) continue;
                line(cx + i, y0, cz + j, cx + i, y1, cz + j);
            }
        }
        endGroup(1f, 0f, 0f, 0.5f);
        // The current chunk: verticals every 4 blocks, rings every 8 blocks near the camera.
        for (int i = 4; i < 16; i += 4) {
            line(cx + i, y0, cz, cx + i, y1, cz);
            line(cx + i, y0, cz + 16, cx + i, y1, cz + 16);
            line(cx, y0, cz + i, cx, y1, cz + i);
            line(cx + 16, y0, cz + i, cx + 16, y1, cz + i);
        }
        double r0 = Math.max(y0, Math.floor(cam.y / 8.0) * 8.0 - 32.0), r1 = Math.min(y1, r0 + 72.0);
        for (double y = r0; y <= r1; y += 8.0) {
            line(cx, y, cz, cx + 16, y, cz); line(cx + 16, y, cz, cx + 16, y, cz + 16);
            line(cx + 16, y, cz + 16, cx, y, cz + 16); line(cx, y, cz + 16, cx, y, cz);
        }
        endGroup(1f, 1f, 0f, 1f);
        line(cx, y0, cz, cx, y1, cz); line(cx + 16, y0, cz, cx + 16, y1, cz);
        line(cx, y0, cz + 16, cx, y1, cz + 16); line(cx + 16, y0, cz + 16, cx + 16, y1, cz + 16);
        endGroup(0.25f, 0.25f, 1f, 1f);
    }

    private void ensureResources(RtContext ctx, int width, int height) {
        this.ctxRef = ctx;
        idleFrames = 0;
        if (pipeline == null) {
            accelSet = RtOverlayPipelines.accelStructureSet(ctx, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "debug lines");
            pipeline = new RtOverlayPipelines.Spec("block_outline.vert.spv", "block_outline.frag.spv")
                    .vertex(RtOverlayPipelines.VertexFormat.POSITION)
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST)
                    // NONE (straight write), not ALPHA: ALPHA's blend factors (srcAlpha=ZERO, dstAlpha=ONE)
                    // preserve the DESTINATION's alpha, which was fine composited straight onto opaque `main`
                    // (only RGB mattered) but is wrong now that this pass writes into a transparent scratch
                    // mask whose alpha IS the coverage signal the composite pass reads — ALPHA here would
                    // leave every resolved pixel's alpha stuck at the clear value (0), invisible outline.
                    .blend(RtOverlayPipelines.Blend.NONE)
                    .attachment(RtWorldOverlay.TARGET_FORMAT)
                    .samples(RtDeviceBringup.overlayMsaaSamples())
                    .push(PUSH_BYTES, VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .descriptorSetLayout(accelSet.layout)
                    .build(ctx, "debug lines");
            compositeSet = RtOverlayPipelines.storageImageSet(ctx, 1, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "debug lines composite");
            compositePipeline = new RtOverlayPipelines.Spec("overlay_fullscreen_triangle.vert.spv", "overlay_passthrough_composite.frag.spv")
                    .blend(RtOverlayPipelines.Blend.ALPHA)
                    .attachment(RtWorldOverlay.TARGET_FORMAT)
                    .descriptorSetLayout(compositeSet.layout)
                    .build(ctx, "debug lines composite");
        }
        if (msaaImage == null || msaaImage.width != width || msaaImage.height != height) {
            if (msaaImage != null) {
                msaaImage.destroy();
            }
            msaaImage = ctx.createTransientMsaaColorImage(width, height, RtWorldOverlay.TARGET_FORMAT,
                    RtDeviceBringup.overlayMsaaSamples(), "debug lines msaa " + width + "x" + height);
        }
        if (resolvedMask == null || resolvedMask.width != width || resolvedMask.height != height) {
            if (resolvedMask != null) {
                resolvedMask.destroy();
            }
            resolvedMask = ctx.createStorageImage(width, height, RtWorldOverlay.TARGET_FORMAT,
                    "debug lines resolved mask " + width + "x" + height, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        }
        compositeSet.bind(ctx, 0, resolvedMask.view);
    }

    @Override
    public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctxRef, cmd, "debug lines mask")) {
                RtWorldOverlay.beginMsaaColorRendering(cmd, stack, msaaImage.view, resolvedMask.view, width, height);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout, 0,
                        stack.longs(boundSet), null);
                VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.handle), stack.longs(0L));
                float desiredWidthPx = LINE_WIDTH_PX_AT_REFERENCE * (height / REFERENCE_HEIGHT);
                float lineWidth = RtDeviceBringup.wideLinesEnabled()
                        ? Math.min(desiredWidthPx, RtDeviceBringup.maxLineWidth()) : 1.0f;
                VK10.vkCmdSetLineWidth(cmd, lineWidth);
                ByteBuffer push = stack.malloc(PUSH_BYTES);
                viewProj.get(0, push);
                RtEntities entities = RtEntities.INSTANCE;
                push.putFloat(64, entities.glowCamOffsetX()).putFloat(68, entities.glowCamOffsetY())
                        .putFloat(72, entities.glowCamOffsetZ());
                for (int g = 0; g < groupCount; g++) {
                    int c = g * 6;
                    push.putFloat(80, groups[c + 2]).putFloat(84, groups[c + 3]).putFloat(88, groups[c + 4])
                            .putFloat(92, groups[c + 5]);
                    VK10.vkCmdPushConstants(cmd, pipeline.layout,
                            VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
                    VK10.vkCmdDraw(cmd, (int) groups[c + 1], 1, (int) groups[c], 0);
                }
                RtWorldOverlay.endRendering(cmd);
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack); // resolved mask writes visible to the composite's reads

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctxRef, cmd, "debug lines composite")) {
                RtWorldOverlay.beginColorRendering(cmd, stack, targetView, width, height, false);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline.layout, 0,
                        stack.longs(compositeSet.set), null);
                VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                RtWorldOverlay.endRendering(cmd);
            }
        }
    }

    @Override
    public void destroy() {
        if (ctxRef == null) {
            return;
        }
        if (pipeline != null) {
            pipeline.destroy(ctxRef.vk());
            pipeline = null;
        }
        if (accelSet != null) {
            accelSet.destroy(ctxRef.vk());
            accelSet = null;
        }
        if (compositePipeline != null) {
            compositePipeline.destroy(ctxRef.vk());
            compositePipeline = null;
        }
        if (compositeSet != null) {
            compositeSet.destroy(ctxRef.vk());
            compositeSet = null;
        }
        if (msaaImage != null) {
            msaaImage.destroy();
            msaaImage = null;
        }
        if (resolvedMask != null) {
            resolvedMask.destroy();
            resolvedMask = null;
        }
        ctxRef = null;
    }
}
