package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a small CTM-diagnostic structure in front of the player and photographs it, gated by
 * {@code -Dcaustica.enhanced.continuityScenario=true}. A 3x3 glass wall makes connected-texture
 * behaviour obvious: with CTM the interior mullions vanish and the wall reads as one sheet, without it
 * every block keeps its own border. Glass panes, a stone block and a torch cover the pane path, ordinary
 * LabPBR and emission. Blocks are placed in the client level - the structure exists for the screenshot
 * only and the world is a disposable test copy. Off by default.
 */
public final class ContinuityScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final boolean ENABLED = Boolean.getBoolean("caustica.enhanced.continuityScenario");
    private static final int SHOTS = 4;
    private static final int SHOT_INTERVAL = 40;
    private static int ticks = -1;
    private static BlockPos anchor;
    private static boolean built;
    /** When set, cycle PARALLAX 0/1/2 once per window so all three modes are measured under one GPU state. */
    private static final boolean CYCLE_PARALLAX = Boolean.getBoolean("caustica.enhanced.cycleParallax");
    private static final int CYCLE_WINDOW_TICKS = 600;
    private static int lastCycleMode = -1;
    // Pinned pose, re-applied before every shot so the three parallax modes stay comparable.
    private static double pinX, pinY, pinZ;
    private static float pinYaw, pinPitch;

    private static void put(Minecraft mc, BlockPos pos, BlockState state) {
        mc.level.setBlockAndUpdate(pos, state);
    }

    private static void pinCamera(LocalPlayer player) {
        player.setPos(pinX, pinY, pinZ);
        player.setYRot(pinYaw);
        player.setXRot(pinPitch);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
    }

    private static void build(LocalPlayer player, Minecraft mc) {
        Direction facing = player.getDirection();
        Direction side = facing.getClockWise();
        BlockPos base = player.blockPosition().relative(facing, 4);
        // 3x3 glass wall: CTM has to merge the nine tiles into one sheet.
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                put(mc, base.offset(side.getStepX() * x, y, side.getStepZ() * x), Blocks.GLASS.defaultBlockState());
            }
        }
        // Panes to the side, an ordinary block and a torch for emission comparison.
        BlockPos pane = base.offset(side.getStepX() * -3, 0, side.getStepZ() * -3);
        for (int z = 0; z <= 2; z++) {
            put(mc, pane.offset(facing.getStepX() * z, 0, facing.getStepZ() * z), Blocks.GLASS_PANE.defaultBlockState());
        }
        BlockPos stone = base.offset(side.getStepX() * 3, 0, side.getStepZ() * 3);
        put(mc, stone, Blocks.STONE.defaultBlockState());
        put(mc, stone.above(), Blocks.TORCH.defaultBlockState());
        // Height-mapped test surfaces for parallax: a floor strip seen at a grazing angle plus a short wall.
        // Stone bricks / dirt / deepslate bricks all ship a real LabPBR height map in their _n alpha.
        for (int z = 1; z <= 6; z++) {
            for (int x = -1; x <= 1; x++) {
                BlockPos p = player.blockPosition().offset(side.getStepX() * x, -1, side.getStepZ() * x)
                        .relative(facing, z);
                put(mc, p, (z % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.DEEPSLATE_BRICKS).defaultBlockState());
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                put(mc, base.offset(side.getStepX() * (x + 6), y, side.getStepZ() * (x + 6)),
                        Blocks.DIRT.defaultBlockState());
            }
        }
        LOGGER.info("[continuityScenario] built at {} facing {} (glass wall / panes / stone+torch / parallax floor)",
                base, facing);
        // Pin the camera so OFF / Parallax / Smooth runs are pixel-comparable: stand two blocks back from the
        // floor strip and look down it at a grazing angle, where parallax depth is most visible.
        pinX = player.getX() - facing.getStepX() * 2.0D;
        pinY = base.getY() + 1.0D;
        pinZ = player.getZ() - facing.getStepZ() * 2.0D;
        pinYaw = facing.toYRot();
        // Positive XRot looks down in Minecraft, so the sky check needs a negative pitch.
        pinPitch = Boolean.getBoolean("caustica.enhanced.lookUp") ? -42.0F : 30.0F;
        pinCamera(player);
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            ticks = -1;
            anchor = null;
            built = false;
            return;
        }
        if (!built) {
            // The paused/loading frames right after a join must not be photographed.
            if (player.tickCount < 40) {
                return;
            }
            anchor = player.blockPosition();
            build(player, mc);
            built = true;
            ticks = 0;
            return;
        }
        pinCamera(player);
        if (CYCLE_PARALLAX) {
            int mode = (ticks / CYCLE_WINDOW_TICKS) % 3;
            if (mode != lastCycleMode) {
                lastCycleMode = mode;
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Material.PARALLAX.set(mode);
                LOGGER.info("[continuityScenario] parallax mode -> {}", mode);
            }
        }
        pinCamera(player);
        if (ticks % SHOT_INTERVAL == 0 && ticks / SHOT_INTERVAL < SHOTS) {
            Screenshot.grab(mc, false);
            LOGGER.info("[continuityScenario] shot {} at tick {}", ticks / SHOT_INTERVAL, ticks);
        }
        ticks++;
    }

    private ContinuityScenario() {}
}
