package dev.comfyfluffy.caustica.rt.entity;
/** Bit 2 is reserved for shadows from the local player's analytic held emitters only. */
public final class LightOwnerMask {
    public static final int HELD_SHADOW=4;
    public static int receiver(int ordinaryMask,boolean owner) {
        return owner ? ordinaryMask & ~HELD_SHADOW : ordinaryMask | HELD_SHADOW;
    }
    private LightOwnerMask() {}
}
