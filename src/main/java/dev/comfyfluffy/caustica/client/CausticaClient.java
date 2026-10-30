package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;

public final class CausticaClient implements ClientModInitializer {
	private static boolean rtInitDone = false;
	private static boolean runtimeWasRequested = true;

	@Override
	public void onInitializeClient() {
		BetterPlayerModelCompat.init();
		// Same default as Iris' shader toggle.
		net.minecraft.client.KeyMapping toggleKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
				new net.minecraft.client.KeyMapping("caustica.enhanced.key.toggle", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
						org.lwjgl.glfw.GLFW.GLFW_KEY_K, net.minecraft.client.KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				boolean enabled = !dev.comfyfluffy.caustica.CausticaConfig.Rt.ENABLED.value();
				dev.comfyfluffy.caustica.CausticaConfig.Rt.ENABLED.set(enabled);
				dev.comfyfluffy.caustica.CausticaConfig.save();
				if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
						enabled ? "caustica.enhanced.key.toggle.on" : "caustica.enhanced.key.toggle.off"));
			}
		});
		// Developer tools (performance survey, probes, showcase filming) are not shipped in a release jar.
		boolean devTools = !dev.comfyfluffy.caustica.CausticaBuild.RELEASE;
		if (devTools) PerfSweep.init();
		try { Class.forName("dev.comfyfluffy.caustica.client.PerfAutoBench").getMethod("init").invoke(null); } catch (ReflectiveOperationException ignored) {} // bench jars only
		if (dev.comfyfluffy.caustica.CausticaBuild.RELEASE) {
			// Release: developer diagnostics stay off even if an older config switched them on.
			dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.DEBUG_VIEW.set(0);
			dev.comfyfluffy.caustica.CausticaConfig.Rt.FrameStats.ENABLED.set(false);
			dev.comfyfluffy.caustica.CausticaConfig.Rt.Diagnostics.GPU_TIMINGS.set(false);
		}
		YsmModelMemory.init();
		CausticaMod.LOGGER.info("Caustica client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			// Opt-in only (-Dcaustica.enhanced.punchyScenario=true); returns immediately otherwise.
			if (devTools) PunchyScenario.tick(client);
			// Opt-in only (-Dcaustica.enhanced.continuityScenario=true).
			if (devTools) ContinuityScenario.tick(client);
			if (!VanillaRenderController.rtRuntimeWorkRequested()) {
				runtimeWasRequested = false;
				if (rtInitDone) {
					shutdownRt();
				}
				return;
			}

			boolean restarting = !runtimeWasRequested;
			if (restarting) {
				runtimeWasRequested = true;
				VanillaRenderController.INSTANCE.resetRuntimeState();
				RtComposite.INSTANCE.resetFailureLatch();
			}
			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
					if (restarting && dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()) {
						dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.requestReconfigure();
					}
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtFrameStats.FRAME.beginIfInactive();
					// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
					// material flags resolve from the first section (PBR on join, no re-extract). No-op
					// until we're in a world with the block atlas loaded, or once already created.
					RtComposite.INSTANCE.ensureResourcesReady(ctx);
					RtTerrain.update(ctx);
                    dev.comfyfluffy.caustica.rt.entity.RtDhLods.INSTANCE.releaseIfWorldClosed(ctx);
					// Log DLSS-FG availability once when frame generation is enabled (capability query only;
					// the present-loop integration that consumes it is built separately).
					if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
						dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.probeAvailabilityOnce();
					}
				}
			}
		});

		// Vanilla's full render-state invalidation (LevelExtractor.allChanged(): dimension change via
		// setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
		// world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
		// world-unique). Resource reloads do NOT fire this; that path is handled separately.
		InvalidateRenderStateCallback.EVENT.register(() -> {
			RtTerrain.requestFullClear();
			VanillaRenderController.INSTANCE.resetRuntimeState();
			RtComposite.INSTANCE.resetFailureLatch(); // F3+A doubles as manual RT recovery after a latched failure
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			shutdownRt();
		});
	}

	private static void shutdownRt() {
		WorldRenderScaler.INSTANCE.destroy();
		if (!rtInitDone) {
			RtUiOverlay.destroy(); // No RT references exist on this path.
			RtWorkerPool.INSTANCE.shutdown();
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			// Device idle cannot complete command buffers that the Minecraft encoder has not submitted.
			// Flush them before destroying referenced pipelines/images; otherwise their state becomes INVALID.
			com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
			// Let the terrain epoch cancel queued work and release every active-task token before
			// shutdownNow(): discarded worker Runnables cannot deliver their terminal callbacks.
			RtTerrain.shutdown(ctx);
		}
		// Terrain shutdown drains async work and waits for every device queue before FG/UI resources die.
		RtUiOverlay.destroy();
		RtWorkerPool.INSTANCE.shutdown();
		if (ctx != null) {
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.destroy();
		if (ctx != null) {
			dev.comfyfluffy.caustica.rt.RtFramePresenter.INSTANCE.destroy(ctx.device());
			dev.comfyfluffy.caustica.rt.RtReflex.INSTANCE.destroy(ctx.device().vkDevice());
		}
		// Shut NGX down once, after every feature (RR + FG) has been released above.
		dev.comfyfluffy.caustica.ngx.NgxRuntime.INSTANCE.shutdown();
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
