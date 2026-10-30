package dev.comfyfluffy.caustica.rt;

/** Explicit emission edits and material reloads invalidate reconstruction without rebuilding features. */
final class EmissionHistoryState {
    private boolean observed;
    private long materialEpoch;
    private int gainBits, torchBits;
    private boolean itrp, itrpColors, entityEmission;

    boolean changed(long epoch, float gain, boolean referenceLook, boolean referenceColors, boolean entities) {
        return changed(epoch, gain, referenceLook, referenceColors, entities, 1f);
    }

    boolean changed(long epoch, float gain, boolean referenceLook, boolean referenceColors, boolean entities, float torchGain) {
        int bits = Float.floatToIntBits(gain), nextTorchBits=Float.floatToIntBits(torchGain);
        boolean changed = !observed || materialEpoch != epoch || gainBits != bits || torchBits != nextTorchBits
                || itrp != referenceLook || itrpColors != referenceColors || entityEmission != entities;
        observed = true;
        materialEpoch = epoch;
        gainBits = bits;
        torchBits = nextTorchBits;
        itrp = referenceLook;
        itrpColors = referenceColors;
        entityEmission = entities;
        return changed;
    }
}
