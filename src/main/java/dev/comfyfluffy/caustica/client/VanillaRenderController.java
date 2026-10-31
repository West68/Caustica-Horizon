package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

public final class VanillaRenderController {
	public static final VanillaRenderController INSTANCE = new VanillaRenderController();

	private boolean frameStarted;
	private boolean baseReady;
	private boolean projectionCaptured;
	private boolean worldSkipped;
	private boolean failureLatched;
	private boolean loggedActive;
	private boolean loggedWaitingForRtPlayerSection;
	private boolean loggedRtPlayerSectionReady;
	private boolean rtActive = true;
	private Boolean lastLoggedRtActive;
	private String inactiveReason;
	private String lastLoggedInactiveReason;

	private VanillaRenderController() {
	}

	public void beginFrame(RenderTarget mainTarget) {
		this.frameStarted = true;
		this.projectionCaptured = false;
		this.worldSkipped = false;
		this.baseReady = false;
		this.inactiveReason = null;
		boolean wasActive = this.rtActive;
		this.rtActive = RtComposite.enabled();
		if (this.rtActive && !wasActive) releaseRasterTerrain();
		EntityCullingCompat.update(this.rtActive);
		SodiumAnimationCompat.update(this.rtActive);

		if (!Boolean.valueOf(this.rtActive).equals(this.lastLoggedRtActive)) {
			this.lastLoggedRtActive = this.rtActive;
			CausticaMod.LOGGER.info("RT output mode: {}", this.rtActive ? "rt" : "vanilla");
		}

		if (!this.rtActive) {
			return;
		}

		this.inactiveReason = findInactiveReason(mainTarget);
		this.baseReady = this.inactiveReason == null;
		if (this.baseReady) {
			if (!this.loggedActive) {
				this.loggedActive = true;
				CausticaMod.LOGGER.info("Vanilla world rendering cancellation active; using existing RT composite seam");
			}
		} else {
			logInactive(this.inactiveReason);
		}
	}

	/** Frees raster terrain ahead of RT bring-up, which would otherwise allocate while it still holds its VRAM. */
	public void releaseRasterForResume() {
		if (!this.rtActive) {
			this.rtActive = true;
			// A Sodium reload rebuilds every visible section on the raster side; doing that on every key press
			// during a stuck rebuild storm is what made repeated toggles worse than useless. Once per 5 s is
			// plenty - the buffers it frees cannot come back that fast.
			long now = System.currentTimeMillis();
			if (now - lastRasterReleaseMs >= 5000L) {
				lastRasterReleaseMs = now;
				releaseRasterTerrain();
			}
		}
	}

	public boolean hasLatchedFailure() {
		return this.failureLatched;
	}

	public boolean onVanillaFallback() {
		return this.failureLatched || RtComposite.INSTANCE.hasFailed();
	}

	/** Explicit mode re-enable / world invalidation retries a fresh generation, not every failed frame. */
	public void resetRuntimeState() {
		failureLatched = false;
		frameStarted = false;
		baseReady = false;
		projectionCaptured = false;
		worldSkipped = false;
		inactiveReason = null;
		lastLoggedInactiveReason = null;
		loggedActive = false;
	}

	public void markProjectionCaptured() {
		this.projectionCaptured = true;
	}

	public boolean shouldCancelLevelRenderer() {
		return this.shouldCancelLevelRenderer(false);
	}

	public boolean shouldCancelLevelRenderer(boolean waitingForRtPlayerSection) {
		if (!this.rtActive) {
			return false;
		}
		if (!this.frameStarted) {
			logInactive("frame controller was not started");
			return false;
		}
		if (!this.baseReady) {
			return false;
		}
		if (!this.projectionCaptured) {
			logInactive("level projection was not captured");
			return false;
		}
		if (waitingForRtPlayerSection && !this.loggedWaitingForRtPlayerSection) {
			this.loggedWaitingForRtPlayerSection = true;
			CausticaMod.LOGGER.info("Keeping vanilla LevelRenderer canceled while waiting for RT player section residency");
		}
		return true;
	}

	public void markRtPlayerSectionReady() {
		if (!this.loggedRtPlayerSectionReady) {
			this.loggedRtPlayerSectionReady = true;
			CausticaMod.LOGGER.info("Satisfied vanilla terrain-load callback from RT player section residency");
		}
	}

	/**
	 * RT switched back on after a raster session: Sodium still holds every section mesh it uploaded while
	 * the raster world was drawn, next to the RT terrain, which pushed an 8 GB GPU into paging and kept the
	 * frame rate low for the rest of the session. A Sodium reload drops those buffers; under RT the vanilla
	 * world is skipped, so Sodium never rebuilds them until raster is used again.
	 */
	private static void releaseRasterTerrain() {
		try {
			Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			Object renderer = type.getMethod("instanceNullable").invoke(null);
			if (renderer != null) {
				type.getMethod("reload").invoke(renderer);
				CausticaMod.LOGGER.info("RT resumed: released Sodium raster terrain buffers");
			}
		} catch (ClassNotFoundException ignored) {
		} catch (Throwable t) {
			CausticaMod.LOGGER.warn("RT resumed: could not release Sodium raster terrain buffers", t);
		}
	}

	public void markWorldSkipped() {
		this.worldSkipped = true;
	}

	public boolean wasWorldSkippedThisFrame() {
		return this.worldSkipped;
	}

	/** Capture replaces raster hands only when this frame actually skipped the vanilla world. */
	public boolean shouldCaptureRtFirstPerson() {
		return this.rtActive && this.baseReady && this.projectionCaptured && this.worldSkipped;
	}

	public boolean shouldCompositeRt() {
		return this.rtActive;
	}

	/** Runtime work switch for per-frame RT work; mirrors {@link RtComposite#enabled()}. */
	public static boolean rtRuntimeWorkRequested() {
		return RtComposite.enabled();
	}

	public void markRtCompositeResult(boolean success) {
		if (this.worldSkipped && !success) {
			latchFailure("RT composite did not produce a replacement frame");
		}
	}

	public void markMissedBeforeHandSeam() {
		if (this.worldSkipped) {
			latchFailure("missed before-hand RT composite seam after vanilla world was skipped");
		}
	}

	private static long lastRasterReleaseMs = 0L;

	private String findInactiveReason(RenderTarget mainTarget) {
		if (this.failureLatched || RtComposite.INSTANCE.hasFailed()) {
			return "RT composite failure latch is set";
		}
		if (!RtComposite.enabled()) {
			return "caustica.rt is false";
		}
		if (RtContext.currentOrNull() == null) {
			return "RT context is not ready";
		}
		if (RtTerrain.currentOrNull() == null) {
			return "RT terrain is not ready";
		}
		if (RtComposite.INSTANCE.requiresVanillaWorldFallback()) {
			return "RT resources are crossing an epoch boundary";
		}
		if (mainTarget == null || mainTarget.getColorTexture() == null || mainTarget.getDepthTexture() == null) {
			return "main render target textures are not ready";
		}
		return null;
	}

	private void latchFailure(String reason) {
		if (!this.failureLatched) {
			CausticaMod.LOGGER.warn("Disabling vanilla world cancellation: {}", reason);
		}
		this.failureLatched = true;
		this.baseReady = false;
		this.inactiveReason = reason;
	}

	private void logInactive(String reason) {
		if (reason == null || reason.equals(this.lastLoggedInactiveReason)) {
			return;
		}
		CausticaMod.LOGGER.info("Vanilla world cancellation inactive: {}", reason);
		this.lastLoggedInactiveReason = reason;
	}
}
