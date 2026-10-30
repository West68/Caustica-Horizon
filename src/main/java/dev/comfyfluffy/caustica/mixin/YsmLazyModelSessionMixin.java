package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.client.YsmModelMemory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Better Player Model 1.1.5 stamps each lazy model with the sync session that created it and resolves any
 * model from another session to the default one. Rejoining a world starts a new session but only "updates"
 * the existing catalog entries, so every model, the player's own included, stays on the old stamp and the
 * player renders as the default model for the rest of the game. A model still listed in the current
 * catalog belongs to this session.
 */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.client.model.LazyModelAssembly", remap = false)
public abstract class YsmLazyModelSessionMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/elfmcys/yesstevemodel/client/ClientModelManager;isCurrentSyncSession(I)Z"),
            require = 0, remap = false)
    private boolean caustica$adoptListedModel(int session, Operation<Boolean> original) {
        return original.call(session) || YsmModelMemory.listedInCurrentCatalog(this);
    }
}
