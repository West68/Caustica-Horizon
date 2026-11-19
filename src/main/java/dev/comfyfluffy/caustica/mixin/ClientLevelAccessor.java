package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lightning sky flash countdown; vanilla only reads it inside the cancelled level renderer. */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("skyFlashTime")
    int caustica$skyFlashTime();
}
