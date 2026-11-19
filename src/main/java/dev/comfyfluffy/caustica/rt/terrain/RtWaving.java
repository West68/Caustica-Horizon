package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Waving foliage, after ITRP's WavingPlants.glsl. Its block groups: leaves and vines sway as a whole
 * (block.4 / block.7040); short plants and flowers (7010), crops (7011) and both halves of tall plants
 * (7020/7021) bend from the root, only their upper vertices moving. Saplings, petals and the rest of
 * block.7000 stay still, as in ITRP.
 *
 * <p>The mesher stores one float per vertex: 0 still; negative = leaves, magnitude the skylight weight;
 * positive = plant bend weight times skylight weight, {@code +4} for crops. Only sections near the player
 * carry this data (see {@link #wantsData}); world.rgen-independent GPU work lives in {@link RtWavingPass}.
 */
public final class RtWaving {
    public static final int NONE = 0, LEAVES = 1, PLANT = 2, TALL_LOWER = 3, TALL_UPPER = 4, CROP = 5;
    private static final Map<BlockState, Integer> KINDS = new ConcurrentHashMap<>();

    /** Player block position, published by the render thread each frame for the mesher workers. */
    private static volatile double playerX, playerY, playerZ;
    private static volatile boolean enabled;
    private static volatile float range = 64f;

    private RtWaving() {}

    public static int kind(BlockState state) {
        Integer cached = KINDS.get(state);
        if (cached != null) return cached;
        int k = classify(state);
        KINDS.put(state, k);
        return k;
    }

    private static int classify(BlockState state) {
        Block b = state.getBlock();
        if (state.is(BlockTags.LEAVES) || b == Blocks.VINE) return LEAVES;
        if (b == Blocks.WHEAT || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.BEETROOTS) return CROP;
        if (b instanceof DoublePlantBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? TALL_UPPER : TALL_LOWER;
        }
        if (b == Blocks.SHORT_GRASS || b == Blocks.FERN || b == Blocks.BUSH || b == Blocks.FIREFLY_BUSH
                || b == Blocks.SHORT_DRY_GRASS || b == Blocks.TALL_DRY_GRASS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.TORCHFLOWER_CROP || state.is(BlockTags.SMALL_FLOWERS)) return PLANT;
        return NONE;
    }

    /** Render thread, once per frame. */
    public static void publish(double x, double y, double z) {
        playerX = x; playerY = y; playerZ = z;
        enabled = CausticaConfig.Rt.Composite.WAVING.value() && CausticaConfig.Rt.Visuals.WAVING_PLANTS.value() > 0f;
        range = CausticaConfig.Rt.Visuals.WAVING_RANGE.value();
    }

    public static boolean enabled() { return enabled; }

    public static float range() { return range; }

    /** Sections within the waving range plus a margin keep vertex data, so they are ready before fading in. */
    public static float dataRadius() { return range + 40f; }

    /** Should a section meshed now carry per-vertex waving data? Any thread. */
    public static boolean wantsData(int scx, int scy, int scz) {
        if (!enabled) return false;
        return distanceToSection(scx, scy, scz) <= dataRadius();
    }

    /** Distance from the player to the nearest point of a 16^3 section. */
    public static double distanceToSection(int scx, int scy, int scz) {
        double dx = Math.max(Math.max((scx << 4) - playerX, playerX - ((scx << 4) + 16)), 0);
        double dy = Math.max(Math.max((scy << 4) - playerY, playerY - ((scy << 4) + 16)), 0);
        double dz = Math.max(Math.max((scz << 4) - playerZ, playerZ - ((scz << 4) + 16)), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** ITRP lightWeight: saturate(lightmap * 3 - 1.5)^2, shaded foliage under cover does not move. */
    static float lightWeight(int skyLight) {
        float w = Math.clamp(skyLight / 15f * 3f - 1.5f, 0f, 1f);
        return w * w;
    }

    /**
     * Per-vertex encoding for a quad corner. {@code height} = vertex height inside its block, 0 root .. 1 top:
     * the bend weight of a plant (ITRP's grassWeight, made position-based so multi-face models stay joined).
     */
    static float encode(int kind, float light, float height) {
        if (kind == NONE || light <= 0f) return 0f;
        if (kind == LEAVES) return -light;
        float g = height;
        float weight = switch (kind) {
            case TALL_LOWER -> g * .6f;
            case TALL_UPPER -> g * .6f + .6f;
            default -> g;
        };
        if (weight <= 0f) return 0f;
        return kind == CROP ? weight * light + 4f : weight * light;
    }
}
