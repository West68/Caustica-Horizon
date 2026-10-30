package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.entity.RtFirstPerson;
import net.minecraft.client.renderer.SubmitNodeCollector;
import dev.comfyfluffy.caustica.rt.RtReflex;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the level-rendering section of {@link GameRenderer#render} with the
 * render-scale window: low-res textures are swapped into the main target just
 * before {@code renderLevel} (so the level frame graph, sky, entity outline and
 * post chains all run at reduced resolution) and restored + upscaled right
 * before the pre-GUI depth clear.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	// Reset the UI overlay's per-frame clear latch at the very start of the frame (before the world, hand,
	// or GUI render into it).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void caustica$beginOverlayFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.gameRenderBegin();
		RtUiOverlay.beginFrame();
		RtFirstPerson.beginFrame();
		// Clear the stale HDR-present flag every frame: composite() only runs while a level renders, so on
		// menu frames it would otherwise stay true from the last world frame and present a black HDR image.
		RtComposite.INSTANCE.beginFrame();
		if (!dev.comfyfluffy.caustica.CausticaBuild.RELEASE) dev.comfyfluffy.caustica.client.CinematicDirector.beginFrame();
		// Reflex RENDERSUBMIT_START: render-graph recording begins here; RENDERSUBMIT_END is set at
		// VulkanGpuSurface.present() HEAD (VulkanGpuSurfaceMixin), just before the real present.
		if (RtReflex.enabled()) {
			long swapchain = RtReflex.INSTANCE.appliedSwapchain();
			if (swapchain != 0L
					&& ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
				RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_RENDERSUBMIT_START,
						RtReflex.INSTANCE.currentSimFrameId());
			}
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void caustica$endRtFrameStats(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		RtComposite.INSTANCE.endFrame();
		if (!dev.comfyfluffy.caustica.CausticaBuild.RELEASE) dev.comfyfluffy.caustica.client.CinematicDirector.endFrame();
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.gameRenderEnd();
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void caustica$beginWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
	}

	// Redirect the held-item/hand render into the combined UI overlay. SDR and HDR then feed DLSS-FG the same
	// shape: hudless excludes the screen-fixed hand, while pUI carries hand + screen effects + GUI overlays.
	// try/finally guarantees the output overrides are cleared even if the hand render throws.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"))
	private void caustica$redirectHandToOverlay(GameRenderer self, CameraRenderState cameraState, float deltaPartialTick,
			Matrix4fc modelViewMatrix, Operation<Void> original) {
        if (RtFirstPerson.enabled()) {
            RtFirstPerson.begin();
            try { original.call(self,cameraState,deltaPartialTick,modelViewMatrix); }
            finally { RtFirstPerson.end(); }
            caustica$completeWorld();
            // Restore raster hands immediately if the composite could not display captured geometry.
            if (RtFirstPerson.enabled()) return;
        }
		boolean redirect = RtUiOverlay.enabled();
		if (redirect) {
			RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, cameraState, deltaPartialTick, modelViewMatrix);
		} finally {
			if (redirect) {
				RtUiOverlay.endOutputRedirect();
			}
		}
	}

    @ModifyArg(method="renderItemInHand",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"),index=2)
    private SubmitNodeCollector ce$captureHand(SubmitNodeCollector collector){return RtFirstPerson.collector(collector);}
    @WrapOperation(method="renderItemInHand",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V"))
    private void ce$skipDuplicateRasterHand(FeatureRenderDispatcher dispatcher,SubmitNodeStorage storage,Operation<Void> original){if(!RtFirstPerson.collecting())original.call(dispatcher,storage);}

	// Redirect the screen-effect flush (fire, underwater, view-blocking-block overlays submitted by
	// ScreenEffectRenderer.submit) into the same combined UI overlay as the hand. This is the renderAllFeatures
	// call in the "screenEffects" section of renderLevel — distinct from the one inside renderItemInHand.
	// The spyglass scope and worn-pumpkin blur are drawn by the GUI/HUD instead, so they already reach the
	// overlay via the GuiRenderer redirect.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V"))
	private void caustica$redirectScreenEffectsToOverlay(FeatureRenderDispatcher self, SubmitNodeStorage storage,
			Operation<Void> original) {
        if(!RtComposite.INSTANCE.isHdrPresentActive())RtUiOverlay.compositeIfUsed();
        RtComposite.INSTANCE.captureStreamlineBeforeUi(this.mainRenderTarget);
		boolean redirect = RtUiOverlay.enabled() && RtComposite.INSTANCE.isHdrPresentActive();
		if (redirect) {
			RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, storage);
		} finally {
			if (redirect) {
				RtUiOverlay.endOutputRedirect();
			}
		}
	}

	// Safety net only: the primary end-of-window is caustica$endWorldScaleBeforeHand
	// inside renderLevel. This catches any path where renderLevel bailed early
	// (end() no-ops when the window is already closed).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
					shift = At.Shift.AFTER))
	private void caustica$endWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.endSafetyNet(this.mainRenderTarget);
	}

	// Capture the frame's camera for the RT composite at the exact point the level projection is built
	// (this projection already includes view bobbing, exactly as rendered). The RT path jitters the
	// primary ray in the shader, so the projection matrix itself is left unmodified — we only read it.
	@ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
			index = 0)
	private Matrix4f caustica$captureLevelProjection(Matrix4f projection) {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			return projection;
		}

		var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
		RtComposite.INSTANCE.captureFrame(projection, cameraState.viewRotationMatrix,
				cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
		VanillaRenderController.INSTANCE.markProjectionCaptured();
		return projection;
	}

    @ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private net.minecraft.client.renderer.Projection caustica$captureHandProjection(net.minecraft.client.renderer.Projection projection) {
        RtFirstPerson.captureProjection(projection);
        return projection;
    }

	// Primary end-of-window: right after the 3D-HUD projection is set and *before*
	// vanilla's pre-hand depth clear. The world (incl. entity outline targets and
	// translucency compositing) has fully rendered at low res by this point; the
	// upscale runs here, then the hand, screen effects and 3D crosshair draw at
	// native resolution on top — keeping the screen-fixed hand out of the FSR
	// inputs entirely (camera-reprojection MVs would be exactly wrong for it).
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
					ordinal = 1,
					shift = At.Shift.AFTER))
	private void caustica$endWorldScaleBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        if(RtFirstPerson.enabled())return;
        caustica$completeWorld();
    }
    private void caustica$completeWorld() {
		WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
		// Fold RT world overlays into the shared transparent UI image before hand/screen effects and the GUI
		// add their own layers. RtUiOverlay then performs the single final blend to SDR/HDR.
		try {
			dev.comfyfluffy.caustica.client.RtRasterFallback.draw(this.mainRenderTarget); // includes the Litematica bridge
			RtWorldOverlay.INSTANCE.compositeIntoUiOverlay(this.mainRenderTarget);
		} finally {
			// The block-outline ray query consumes this frame's TLAS. Signal terrain retirement only after
			// its transient command buffer has been placed later in the same graphics submission.
			RtComposite.INSTANCE.finishTerrainGraphicsUse();
		}
	}

    // SDR HUD draws directly on its real destination. Composite world overlays first so they
    // remain underneath the HUD and INVERT blend sees scene colour, not transparent black.
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void caustica$compositeBeforeNativeGui(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!dev.comfyfluffy.caustica.rt.RtUiOverlay.redirectGui()) {
            dev.comfyfluffy.caustica.rt.RtUiOverlay.compositeIfUsed();
            RtComposite.INSTANCE.captureStreamlineBeforeUi(this.mainRenderTarget);
        }
    }

	// Composite the redirected UI overlay back over the world once the GUI has fully rendered into it.
	// Done here (not at GuiRenderer.draw TAIL) because that TAIL inject did not fire on in-game HUD frames;
	// this INVOKE-after seam runs unconditionally once per frame in both gameplay and menus.
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
					shift = At.Shift.AFTER))
	private void caustica$compositeUiOverlay(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		// DLSS-FG quality: snapshot the main target before the combined UI overlay composites back below.
		// Hand/screen effects, world overlays and GUI are carried by the optional DLSSG UI resource.
		RtComposite.INSTANCE.captureFgHudless(this.mainRenderTarget);
		dev.comfyfluffy.caustica.rt.RtUiOverlay.compositeIfUsed();
	}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
