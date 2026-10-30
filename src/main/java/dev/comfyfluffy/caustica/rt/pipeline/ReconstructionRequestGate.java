package dev.comfyfluffy.caustica.rt.pipeline;

/** Retries a failed reconstruction request only after its actual configuration changes. */
final class ReconstructionRequestGate {
    private boolean known;
    private int width, height, quality, preset;
    boolean changed(int width, int height, int quality, int preset) {
        if (known && this.width == width && this.height == height
                && this.quality == quality && this.preset == preset) return false;
        known = true;
        this.width = width; this.height = height; this.quality = quality; this.preset = preset;
        return true;
    }
}
