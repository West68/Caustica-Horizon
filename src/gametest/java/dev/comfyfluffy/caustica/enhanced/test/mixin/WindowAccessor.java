package dev.comfyfluffy.caustica.enhanced.test.mixin;
import dev.comfyfluffy.caustica.enhanced.test.Cadence;
@org.spongepowered.asm.mixin.Mixin(com.mojang.blaze3d.platform.Window.class)
public interface WindowAccessor {
 @org.spongepowered.asm.mixin.gen.Accessor("width") void ce$width(int v);
 @org.spongepowered.asm.mixin.gen.Accessor("height") void ce$height(int v);
 @org.spongepowered.asm.mixin.gen.Accessor("framebufferWidth") void ce$fbWidth(int v);
 @org.spongepowered.asm.mixin.gen.Accessor("framebufferHeight") void ce$fbHeight(int v);
}
