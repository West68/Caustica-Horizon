package dev.comfyfluffy.caustica.rt.entity;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/** Real world mobs cannot inherit held-item, GUI, item-frame or attachment render contexts. */
final class RtMobRenderScope {
    private static final class Flags {
        static final List<RenderFlagScope.Slot> SLOTS=resolve();
        private static List<RenderFlagScope.Slot> resolve() {
            if(!FabricLoader.getInstance().isModLoaded("entity_model_features"))return List.of();
            try {
                return RenderFlagScope.bind(Class.forName("traben.entity_model_features.models.animation.state.EMFState"),
                    "isInHand","isInGui","isOnHead","isInGroundOverride","isInItemFrame",
                    "isInShoulderMethod","isInHandItemLayerTransform","isInLeftHand","hasDoneArmOverride",
                    "modelVariationIgnoresVisibility");
            }catch(ReflectiveOperationException | LinkageError error) {
                org.slf4j.LoggerFactory.getLogger(RtMobRenderScope.class).warn("EMF world-mob context isolation unavailable",error);
                return List.of();
            }
        }
    }
    static RenderFlagScope open(Entity entity) {
        // Player/first-person and nested shoulder/item models keep their own legitimate contexts.
        return entity instanceof Mob && !Flags.SLOTS.isEmpty()?new RenderFlagScope(Flags.SLOTS):null;
    }
    private RtMobRenderScope() {}
}
