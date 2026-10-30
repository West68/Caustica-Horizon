package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic animation driver for the Punchy first-person probe, gated by
 * {@code -Dcaustica.enhanced.punchyScenario=true}. One run covers every pose the RT first-person path has
 * to survive: bare arms, simple items, tool models, block models, the swing/attack pose, block breaking,
 * walk bob and item switching. Each phase boundary logs and takes one screenshot, so a probe line can be
 * aligned to a visual record. The held item is re-applied every tick because the integrated server would
 * revert a one-shot client-side change. With the flag off the method returns immediately.
 */
public final class PunchyScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final boolean ENABLED = Boolean.getBoolean("caustica.enhanced.punchyScenario");
    /** Ticks per phase: long enough for several probe lines inside each one. */
    private static final int PHASE_TICKS = 100;
    private static final int PHASES = 7;
    private static final int WALK = 5;
    private static final int SWITCH = 6;
    private static int ticks = -1;

    private static ItemStack itemFor(int phase, int phaseTick) {
        return switch (phase) {
            case 1 -> new ItemStack(Items.TORCH);
            case 2, 4 -> new ItemStack(Items.DIAMOND_SWORD);
            case 3 -> new ItemStack(Items.STONE);
            case SWITCH -> switch ((phaseTick / 10) % 4) {
                case 1 -> new ItemStack(Items.TORCH);
                case 2 -> new ItemStack(Items.DIAMOND_PICKAXE);
                case 3 -> new ItemStack(Items.OAK_PLANKS);
                default -> new ItemStack(Items.DIAMOND_SWORD);
            };
            default -> ItemStack.EMPTY;
        };
    }

    private static String nameOf(int phase) {
        return switch (phase) {
            case 1 -> "torch";
            case 2 -> "sword-swing";
            case 3 -> "block";
            case 4 -> "mining";
            case WALK -> "walking";
            case SWITCH -> "item-switching";
            default -> "empty";
        };
    }

    public static void tick(Minecraft mc) {
        if (!ENABLED) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            ticks = -1;
            return;
        }
        if (ticks < 0) {
            ticks = 0;
            LOGGER.info("[punchyScenario] start: {} phases x {} ticks", PHASES, PHASE_TICKS);
        }
        int phase = (ticks / PHASE_TICKS) % PHASES;
        int phaseTick = ticks % PHASE_TICKS;
        ItemStack stack = itemFor(phase, phaseTick);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        if (phaseTick == 0) {
            LOGGER.info("[punchyScenario] phase={} ({}) item={}", phase, nameOf(phase),
                    stack.isEmpty() ? "empty" : stack.getItem());
            // Phase boundary: one frame per case, so every pose has a visual record.
            Screenshot.grab(mc, false);
        }
        switch (phase) {
            // Attack / swing pose.
            case 2 -> {
                if (phaseTick % 15 == 0) {
                    player.swing(InteractionHand.MAIN_HAND);
                }
            }
            // Block breaking drives the mining animation through the same swing channel.
            case 4 -> {
                if (mc.hitResult instanceof BlockHitResult hit) {
                    if (mc.gameMode.isDestroying()) {
                        mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
                    } else {
                        mc.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection());
                    }
                } else {
                    player.swing(InteractionHand.MAIN_HAND);
                }
            }
            // Walk bob: advance the player so the walk animation state actually advances.
            case WALK -> {
                double dx = -Math.sin(Math.toRadians(player.getYRot())) * 0.12D;
                double dz = Math.cos(Math.toRadians(player.getYRot())) * 0.12D;
                player.setPos(player.getX() + dx, player.getY(), player.getZ() + dz);
            }
            default -> {}
        }
        ticks++;
    }

    private PunchyScenario() {}
}
