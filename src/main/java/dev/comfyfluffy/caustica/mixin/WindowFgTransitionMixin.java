package dev.comfyfluffy.caustica.mixin;
import com.mojang.blaze3d.platform.Window;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Window.class)
public abstract class WindowFgTransitionMixin {
    //? if <26.3 {
    @Inject(method={"toggleFullScreen","setMode"},at=@At("HEAD"))
    private void caustica$beforeWindowModeChange(CallbackInfo ci) {
        StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    @Inject(method="setWindowed",at=@At("HEAD"))
    private void caustica$beforeWindowSizeChange(int width,int height,CallbackInfo ci) {
        Window window=(Window)(Object)this;
        if(window.isFullscreen()||window.getScreenWidth()!=width||window.getScreenHeight()!=height)
            StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    @Inject(method={"onFramebufferResize","onResize"},at=@At("HEAD"))
    private void caustica$framebufferChanged(long handle,int width,int height,CallbackInfo ci) {
        if(width>0&&height>0)StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    //?} else {
    /*// 26.3 (SDL): fullscreen requests are applied in setMode; resize callbacks lost the window handle.
    @Shadow private boolean fullscreen;
    @Inject(method={"setMode","changeFullscreenVideoMode"},at=@At("HEAD"))
    private void caustica$beforeWindowModeChange(CallbackInfo ci) {
        StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    @Inject(method="setWindowed",at=@At("HEAD"))
    private void caustica$beforeWindowSizeChange(int width,int height,CallbackInfo ci) {
        Window window=(Window)(Object)this;
        if(fullscreen||window.getScreenWidth()!=width||window.getScreenHeight()!=height)
            StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    @Inject(method={"onFramebufferResize","onResize"},at=@At("HEAD"))
    private void caustica$framebufferChanged(int width,int height,CallbackInfo ci) {
        if(width>0&&height>0)StreamlineSwapchainCoordinator.INSTANCE.windowChanging();
    }
    *///?}
}
