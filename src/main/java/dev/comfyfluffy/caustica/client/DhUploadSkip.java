package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;

/**
 * DH vertex-buffer upload elision while Caustica renders DH LODs in hardware (DH raster draw cancelled).
 * Saves the duplicate DH vertex VRAM. If DH's own raster draw is ever needed again, the render-data cache is
 * cleared once so every section re-uploads real buffers before DH draws. {@code -Dcaustica.dh.skipUnusedUploads=false}
 * restores DH's original uploads.
 */
public final class DhUploadSkip {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("caustica.dh.skipUnusedUploads", "true"));
    private static final int REBUILD_GRACE_FRAMES = 120;
    private static volatile boolean skipped;
    private static int graceFrames;

    private DhUploadSkip() {
    }

    public static boolean active() {
        return ENABLED && DistantHorizonsCompat.hardwareOnly();
    }

    public static void markSkipped() {
        skipped = true;
    }

    /** Called before DH's raster draw when hardware capture is off; true means this draw must not run yet. */
    public static boolean blockRasterDraw() {
        if (skipped) {
            skipped = false;
            graceFrames = REBUILD_GRACE_FRAMES;
            try {
                Object proxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed")
                        .getField("renderProxy").get(null);
                proxy.getClass().getMethod("clearRenderDataCache").invoke(proxy);
                CausticaMod.LOGGER.info("DH raster resumed after hardware capture; rebuilding elided vertex buffers");
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Could not clear DH render data after elided uploads", t);
            }
        }
        if (graceFrames > 0) {
            graceFrames--;
            return true;
        }
        return false;
    }
}
