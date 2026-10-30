package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.texture.Stitcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The stitcher grows each side by doubling, so a block atlas that needs 11168 rows is allocated 16384 tall:
 * ~430 MiB of never-sampled texels (with mips) on a high-resolution pack, which pushed 2560x1440 + frame
 * generation past an 8 GB VRAM budget. Report only the rows actually holding sprites, rounded to the mip
 * granularity so every mip level stays texel-exact. Sprite UVs are derived from this height, so sampling is
 * unchanged; only the empty tail is not allocated.
 */
@Mixin(Stitcher.class)
public abstract class StitcherMixin<T extends Stitcher.Entry> {
    @Shadow @Final private int mipLevel;

    @Shadow public abstract void gatherSprites(Stitcher.SpriteLoader<T> loader);

    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void caustica$trimUnusedRows(CallbackInfoReturnable<Integer> cir) {
        int full = cir.getReturnValueI();
        int granule = 1 << mipLevel;
        int[] used = {0};
        gatherSprites((entry, x, y, padding) -> {
            int extent = entry.height() + 2 * padding;
            used[0] = Math.max(used[0], y + (extent + granule - 1) / granule * granule);
        });
        int trimmed = (used[0] + granule - 1) / granule * granule;
        if (trimmed > 0 && trimmed < full) cir.setReturnValue(trimmed);
    }
}
