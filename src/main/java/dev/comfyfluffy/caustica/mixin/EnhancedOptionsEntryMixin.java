package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.EnhancedOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entry stays available when Sodium replaces the video screen. It sits centred under the last row of option
 * buttons (Telemetry / Credits), and is placed again whenever the screen re-lays itself out on a resize or GUI
 * scale change: OptionsScreen.repositionElements moves only vanilla's layout, which left a fixed corner button
 * stranded over other widgets.
 */
@Mixin(OptionsScreen.class)
public abstract class EnhancedOptionsEntryMixin extends Screen {
    @Unique private static final int ENTRY_WIDTH = 150, ENTRY_HEIGHT = 20, ROW_GAP = 4;
    @Unique private Button caustica$entry;

    protected EnhancedOptionsEntryMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void caustica$addIndependentEntry(CallbackInfo ci) {
        caustica$entry = addRenderableWidget(Button.builder(Component.translatable("caustica.enhanced.entry"),
                b -> Minecraft.getInstance().gui.setScreen(new EnhancedOptionsScreen(this)))
                .size(ENTRY_WIDTH, ENTRY_HEIGHT).build());
        caustica$placeEntry();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void caustica$repositionEntry(CallbackInfo ci) {
        caustica$placeEntry();
    }

    @Unique
    private void caustica$placeEntry() {
        if (caustica$entry == null) return;
        int bottom = -1;
        for (var child : children()) {
            if (child instanceof AbstractWidget w && child != caustica$entry
                    && w.getMessage().getContents() instanceof TranslatableContents t
                    && (t.getKey().startsWith("options.telemetry") || t.getKey().equals("options.credits_and_attribution")))
                bottom = Math.max(bottom, w.getY() + w.getHeight());
        }
        if (bottom < 0) { caustica$entry.setPosition(Math.max(4, width - ENTRY_WIDTH - 8), 6); return; }
        caustica$entry.setPosition(width / 2 - ENTRY_WIDTH / 2, bottom + ROW_GAP);
    }
}
