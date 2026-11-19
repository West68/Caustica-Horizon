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

//? if <26.3 {
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
			RtWorldOverlay.INSTANCE.compositeIntoUiOverlay(this.mainRenderTarget, RtComposite.INSTANCE.currentGraphicsUse());
		} finally {
			// The block-outline ray query consumes this frame's TLAS. Signal the shared RT frame token only
			// after its transient command buffer has been placed later in the same graphics submission.
			RtComposite.INSTANCE.finishGraphicsUse();
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
//?} else {
/*	// 26.3: render()/renderLevel() lost their parameters; the hand and screen effects moved into render3dHud,
	// and every feature flush opens its own render pass (no global output override), so the UI-overlay
	// redirect swaps the views handed to createRenderPass instead.
	@Inject(method = "render()V", at = @At("HEAD"))
	private void caustica$beginOverlayFrame(CallbackInfo ci) {
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.gameRenderBegin();
		RtUiOverlay.beginFrame();
		RtFirstPerson.beginFrame();
		RtComposite.INSTANCE.beginFrame();
		if (!dev.comfyfluffy.caustica.CausticaBuild.RELEASE) dev.comfyfluffy.caustica.client.CinematicDirector.beginFrame();
		if (RtReflex.enabled()) {
			long swapchain = RtReflex.INSTANCE.appliedSwapchain();
			if (swapchain != 0L
					&& ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
				RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_RENDERSUBMIT_START,
						RtReflex.INSTANCE.currentSimFrameId());
			}
		}
	}

	@Inject(method = "render()V", at = @At("TAIL"))
	private void caustica$endRtFrameStats(CallbackInfo ci) {
		RtComposite.INSTANCE.endFrame();
		if (!dev.comfyfluffy.caustica.CausticaBuild.RELEASE) dev.comfyfluffy.caustica.client.CinematicDirector.endFrame();
        dev.comfyfluffy.caustica.rt.CausticaLoopProbe.gameRenderEnd();
	}

	@Inject(method = "render()V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void caustica$beginWorldScale(CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
	}

	@WrapOperation(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
	private void caustica$redirectHandToOverlay(GameRenderer self, CameraRenderState cameraState,
			net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
			com.mojang.blaze3d.textures.GpuTextureView handDepth, Operation<Void> original) {
        if (RtFirstPerson.enabled()) {
            RtFirstPerson.begin();
            try { original.call(self,cameraState,playerState,handDepth); }
            finally { RtFirstPerson.end(); }
            caustica$completeWorld();
            if (RtFirstPerson.enabled()) return;
        }
		boolean redirect = RtUiOverlay.enabled();
		if (redirect) RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		try {
			original.call(self, cameraState, playerState, handDepth);
		} finally {
			if (redirect) RtUiOverlay.endOutputRedirect();
		}
	}

	// The hand pass: honour an active overlay redirect when its render pass is opened.
	@WrapOperation(method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/blaze3d/textures/GpuTextureView;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
	private com.mojang.blaze3d.systems.RenderPass caustica$handPassTarget(com.mojang.blaze3d.systems.CommandEncoder encoder,
			java.util.function.Supplier<String> label, com.mojang.blaze3d.textures.GpuTextureView color,
			java.util.Optional<?> clearColor, com.mojang.blaze3d.textures.GpuTextureView depth,
			java.util.OptionalDouble clearDepth, Operation<com.mojang.blaze3d.systems.RenderPass> original) {
		return original.call(encoder, label, RtUiOverlay.redirectColor(color), clearColor, RtUiOverlay.redirectDepth(depth), clearDepth);
	}

    @ModifyArg(method="renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"),index=2)
    private SubmitNodeCollector ce$captureHand(SubmitNodeCollector collector){return RtFirstPerson.collector(collector);}
    @WrapOperation(method="renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/blaze3d/systems/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void ce$skipDuplicateRasterHand(com.mojang.blaze3d.systems.RenderPass pass,
            net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame,Operation<Void> original){if(!RtFirstPerson.collecting())original.call(pass,frame);}

	// Screen effects (fire, underwater, view-blocking overlays): composite the world overlays first, then send
	// the flush into the UI overlay in HDR. The pass is only opened when there is something to draw.
	@Inject(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"))
	private void caustica$beforeScreenEffects(CameraRenderState cameraState,
			net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
			net.minecraft.client.renderer.state.OptionsRenderState options, boolean flag, CallbackInfo ci) {
        if(!RtComposite.INSTANCE.isHdrPresentActive())RtUiOverlay.compositeIfUsed();
        RtComposite.INSTANCE.captureStreamlineBeforeUi(this.mainRenderTarget);
	}

	@WrapOperation(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
	private com.mojang.blaze3d.systems.RenderPass caustica$redirectScreenEffectsToOverlay(com.mojang.blaze3d.systems.CommandEncoder encoder,
			java.util.function.Supplier<String> label, com.mojang.blaze3d.textures.GpuTextureView color,
			java.util.Optional<?> clearColor, com.mojang.blaze3d.textures.GpuTextureView depth,
			java.util.OptionalDouble clearDepth, Operation<com.mojang.blaze3d.systems.RenderPass> original) {
		if (!(RtUiOverlay.enabled() && RtComposite.INSTANCE.isHdrPresentActive()))
			return original.call(encoder, label, color, clearColor, depth, clearDepth);
		RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		try {
			return original.call(encoder, label, RtUiOverlay.redirectColor(color), clearColor, RtUiOverlay.redirectDepth(depth), clearDepth);
		} finally {
			RtUiOverlay.endOutputRedirect();
		}
	}

	@Inject(method = "render()V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", shift = At.Shift.AFTER))
	private void caustica$endWorldScale(CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.endSafetyNet(this.mainRenderTarget);
	}

	@ModifyArg(method = "renderLevel()V",
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

    @ModifyArg(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private net.minecraft.client.renderer.Projection caustica$captureHandProjection(net.minecraft.client.renderer.Projection projection) {
        RtFirstPerson.captureProjection(projection);
        return projection;
    }

	// Primary end-of-window: right after the 3D-HUD projection is set (now the first one in render3dHud)
	// and before the pre-hand depth clear.
	@Inject(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
					ordinal = 0,
					shift = At.Shift.AFTER))
	private void caustica$endWorldScaleBeforeHand(CameraRenderState cameraState,
			net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
			net.minecraft.client.renderer.state.OptionsRenderState options, boolean flag, CallbackInfo ci) {
        if(RtFirstPerson.enabled())return;
        caustica$completeWorld();
    }
    private void caustica$completeWorld() {
		WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
		try {
			dev.comfyfluffy.caustica.client.RtRasterFallback.draw(this.mainRenderTarget); // includes the Litematica bridge
			RtWorldOverlay.INSTANCE.compositeIntoUiOverlay(this.mainRenderTarget, RtComposite.INSTANCE.currentGraphicsUse());
		} finally {
			RtComposite.INSTANCE.finishGraphicsUse();
		}
	}

    @Inject(method = "render()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void caustica$compositeBeforeNativeGui(CallbackInfo ci) {
        if (!dev.comfyfluffy.caustica.rt.RtUiOverlay.redirectGui()) {
            dev.comfyfluffy.caustica.rt.RtUiOverlay.compositeIfUsed();
            RtComposite.INSTANCE.captureStreamlineBeforeUi(this.mainRenderTarget);
        }
    }

	@Inject(method = "render()V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER))
	private void caustica$compositeUiOverlay(CallbackInfo ci) {
		RtComposite.INSTANCE.captureFgHudless(this.mainRenderTarget);
		dev.comfyfluffy.caustica.rt.RtUiOverlay.compositeIfUsed();
	}
*///?}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
