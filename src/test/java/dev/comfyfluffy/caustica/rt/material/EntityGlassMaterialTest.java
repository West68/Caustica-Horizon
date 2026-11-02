package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class EntityGlassMaterialTest {
    @Test void oreEmissionControlDoesNotSelectOrdinaryLightsOrUnrelatedSprites() {
        assertTrue(RtMaterials.oreSprite("minecraft:block/iron_ore"));
        assertTrue(RtMaterials.oreSprite("minecraft:block/deepslate_diamond_ore_05_0_002"));
        assertTrue(RtMaterials.oreSprite("minecraft:block/nether_quartz_ore"));
        assertFalse(RtMaterials.oreSprite("minecraft:block/spore_blossom"));
        assertFalse(RtMaterials.oreSprite("minecraft:block/torch"));
        assertFalse(RtMaterials.oreSprite("minecraft:block/glowstone"));
        assertFalse(RtMaterials.oreSprite(null));
    }
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void clearGlassItemsRemainDielectricAcrossSolidCutoutAndTranslucentPipelines() {
        for (boolean translucent : new boolean[]{false, true}) {
            assertTrue(RtMaterials.refractiveItemSprite("minecraft:block/glass", translucent));
            assertTrue(RtMaterials.refractiveItemSprite("minecraft:block/glass_pane_top", translucent));
            assertTrue(RtMaterials.refractiveItemSprite("minecraft:block/red_stained_glass", translucent));
        }
        assertFalse(RtMaterials.refractiveItemSprite("minecraft:block/stone", false));
        assertFalse(RtMaterials.refractiveItemSprite("minecraft:item/diamond", true));
        assertFalse(RtMaterials.refractiveItemSprite(null, true));
    }

    @Test void itemAtlasVariantPreservesStitchedUvsPbrPageAndSharedParent() {
        ByteBuffer parent = ByteBuffer.allocate(MaterialHeaderData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int i = 0; i < parent.capacity(); i++) parent.put(i, (byte)(i * 7));
        int features = RtMaterialRegistry.FEATURE_SPEC | RtMaterialRegistry.FEATURE_NORMAL
                | RtMaterialRegistry.FEATURE_STOCHASTIC_ALPHA | (5 << 24);
        parent.putInt(4, features);
        ByteBuffer variant = ByteBuffer.allocate(parent.capacity()).order(ByteOrder.nativeOrder());
        variant.put(parent.array());
        RtMaterialRegistry.configureEntityGlassHeader(variant);
        assertEquals(RtMaterialRegistry.MODEL_GLASS, variant.getInt(0));
        assertEquals(features & ~RtMaterialRegistry.FEATURE_STOCHASTIC_ALPHA, variant.getInt(4));
        assertEquals(0.05f, variant.getFloat(48));
        assertEquals(0.0f, variant.getFloat(52));
        assertEquals(1.52f, variant.getFloat(56));
        assertEquals(1.0f, variant.getFloat(60));
        for (int i = 8; i < 48; i++) assertEquals(parent.get(i), variant.get(i), "UV/page byte " + i);
        for (int i = 64; i < 80; i++) assertEquals(parent.get(i), variant.get(i), "average byte " + i);
        assertEquals(features, parent.getInt(4));
    }
}
