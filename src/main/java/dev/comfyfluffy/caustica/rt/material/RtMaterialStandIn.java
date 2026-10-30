package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.RtContext;

import java.util.List;

/** A 1x1 RGBA8 image that Blaze3D can build its own view on when the real page format (BC5) is foreign to it. */
public final class RtMaterialStandIn {
    private final RtMaterialPageTexture texture;

    private RtMaterialStandIn(RtMaterialPageTexture texture) { this.texture = texture; }

    public static RtMaterialStandIn create(RtContext ctx) {
        return new RtMaterialStandIn(new RtMaterialPageTexture(ctx, 1, 1, List.of(new byte[4]), "material stand-in"));
    }

    public long image() { return texture.image(); }

    public void destroy() { texture.destroy(); }
}
