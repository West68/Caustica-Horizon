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
	// One failed frame latches the vanilla path, and turning RT back on competes for VRAM with the raster world that
	// ran meanwhile. Such failures can clear: retry in place first, then rebuild all of RT, a bounded number of times.
	private static final int RECOVERY_FAILED_TICKS = 5 * 20;
	private static final int RECOVERY_STUCK_TICKS = 60 * 20; // RT back on, nothing failed, world still not replaced
	private static final int RECOVERY_HEALTHY_TICKS = 60 * 20;
	private static final int RECOVERY_SOFT_ATTEMPTS = 2;
	private static final int RECOVERY_ATTEMPTS = 5;
	private static int recoveryAttempts = 0;
	private static int recoveryWaitTicks = 0;
	private static int recoveryHealthyTicks = 0;
	private static boolean recoveryRebuilding = false;
	private static boolean recoveryNotified = false;
	private static boolean watchResume = false;
	private static long lastToggleMs = 0L;
	// A recovery rebuild tears RT down and brings it back up over several ticks. Mashing the toggle during
	// that window used to restart the whole cycle (and reset the stuck timer below), so RT never finished
	// coming up - the "press T after a resource reload and ray tracing never returns" report. The toggle now
	// only records the wanted state while a rebuild is running and applies it once the rebuild finishes.
	private static boolean rebuildInProgress = false;
	private static long rebuildStartedMs = 0L;
	private static Boolean pendingToggle = null;
	private static long lastRasterReleaseMs = 0L;

	@Override
	public void onInitializeClient() {
		BetterPlayerModelCompat.init();
		// Same default as Iris' shader toggle.
		net.minecraft.client.KeyMapping toggleKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
				new net.minecraft.client.KeyMapping("caustica.enhanced.key.toggle", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
						org.lwjgl.glfw.GLFW.GLFW_KEY_K, net.minecraft.client.KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				// Held or mashed keys would tear down and rebuild all of RT for each repeat.
				long now = System.currentTimeMillis();
				if (now - lastToggleMs < 500L) continue;
				lastToggleMs = now;
				boolean enabled = !dev.comfyfluffy.caustica.CausticaConfig.Rt.ENABLED.value();
				if (rebuildInProgress) {
					// Never destroy a rebuild that is still running: remember the target and let it apply.
					pendingToggle = enabled;
					CausticaMod.LOGGER.info("RT toggle during rebuild: requested={} queued until the rebuild finishes ({} ms in)", enabled, now - rebuildStartedMs);
					if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
							"caustica.enhanced.key.rebuild.start"));
					continue;
				}
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
				VanillaRenderController.INSTANCE.releaseRasterForResume();
				if (!recoveryRebuilding) {
					recoveryAttempts = 0;
					recoveryNotified = false;
					watchResume = true;
				}
				recoveryRebuilding = false;
				// Deliberately NOT recoveryWaitTicks = 0: every toggle used to restart the stuck countdown,
				// so a player who mashed the key never got a single recovery attempt ("RT is not rendering"
				// never appears in their log). Only a real recovery clears it, below.
			}
			if (recoverIfStuck(client)) {
				return;
			}
			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
					if (rebuildInProgress) {
						rebuildInProgress = false;
						long ms = System.currentTimeMillis() - rebuildStartedMs;
						CausticaMod.LOGGER.info("RT rebuild finished in {} ms (attempts used: {})", ms, recoveryAttempts);
						if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
								"caustica.enhanced.key.rebuild.done", String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)));
						// A rebuild that reached bring-up is a real recovery: only now is the counter reset.
						recoveryWaitTicks = 0;
						recoveryAttempts = 0;
						recoveryNotified = false;
						applyPendingToggle(client);
					}
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

	/** True when RT was torn down for a rebuild, which the next tick starts through the restart path. */
	private static boolean recoverIfStuck(net.minecraft.client.Minecraft client) {
		boolean failed = VanillaRenderController.INSTANCE.onVanillaFallback();
		if (client.level == null || (!failed && VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame())) {
			recoveryWaitTicks = 0;
			if (client.level != null && ++recoveryHealthyTicks >= RECOVERY_HEALTHY_TICKS) {
				recoveryAttempts = 0;
				recoveryNotified = false;
				watchResume = false;
			}
			return false;
		}
		if (!failed && !watchResume) {
			return false; // First join: terrain and materials can legitimately take a long time.
		}
		recoveryHealthyTicks = 0;
		if (++recoveryWaitTicks < (failed ? RECOVERY_FAILED_TICKS : RECOVERY_STUCK_TICKS)) {
			return false;
		}
		recoveryWaitTicks = 0;
		if (recoveryAttempts >= RECOVERY_ATTEMPTS) {
			if (!recoveryNotified) {
				recoveryNotified = true;
				CausticaMod.LOGGER.error("RT did not recover after {} attempts (last state: rtInitDone={}, context={}, terrain={})",
						RECOVERY_ATTEMPTS, rtInitDone, dev.comfyfluffy.caustica.rt.RtContext.currentOrNull() != null,
						dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull() != null);
				rebuildInProgress = false;
				if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
						"caustica.enhanced.key.rebuild.failed", RECOVERY_ATTEMPTS));
			}
			return false;
		}
		recoveryAttempts++;
		CausticaMod.LOGGER.warn("RT is not rendering; recovery attempt {} of {}", recoveryAttempts, RECOVERY_ATTEMPTS);
		if (recoveryAttempts <= RECOVERY_SOFT_ATTEMPTS) {
			VanillaRenderController.INSTANCE.resetRuntimeState();
			RtComposite.INSTANCE.resetFailureLatch();
			return false;
		}
		CausticaMod.LOGGER.warn("RT rebuild {} of {} starting: tearing RT down and rebuilding it", recoveryAttempts, RECOVERY_ATTEMPTS);
		shutdownRt();
		runtimeWasRequested = false;
		recoveryRebuilding = true;
		rebuildInProgress = true;
		rebuildStartedMs = System.currentTimeMillis();
		if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
				"caustica.enhanced.key.rebuild.start"));
		return true;
	}

	/** Apply a toggle that arrived while a rebuild was running, now that the rebuild has finished. */
	private static void applyPendingToggle(net.minecraft.client.Minecraft client) {
		if (pendingToggle == null) {
			return;
		}
		boolean wanted = pendingToggle;
		pendingToggle = null;
		if (dev.comfyfluffy.caustica.CausticaConfig.Rt.ENABLED.value() == wanted) {
			return;
		}
		dev.comfyfluffy.caustica.CausticaConfig.Rt.ENABLED.set(wanted);
		dev.comfyfluffy.caustica.CausticaConfig.save();
		CausticaMod.LOGGER.info("Applied queued RT toggle: enabled={}", wanted);
		if (client.player != null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
				wanted ? "caustica.enhanced.key.toggle.on" : "caustica.enhanced.key.toggle.off"));
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
