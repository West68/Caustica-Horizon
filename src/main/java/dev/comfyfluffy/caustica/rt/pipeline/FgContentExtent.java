package dev.comfyfluffy.caustica.rt.pipeline;

/** The supported non-content padding of an exclusive-fullscreen Vulkan surface. */
public final class FgContentExtent {
    private FgContentExtent() {}
    public static boolean fits(int width, int height, int swapWidth, int swapHeight) {
        return width > 0 && height > 0 && width == swapWidth
                && (height == swapHeight || (long) height + 1 == swapHeight);
    }
}
