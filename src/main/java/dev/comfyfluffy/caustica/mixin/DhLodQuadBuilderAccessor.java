package dev.comfyfluffy.caustica.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the two per-face quad lists DH has finished merging.
 *
 * <p>The fields are {@code private final ArrayList<BufferQuad>[]}. They are read as {@code Object} because
 * Distant Horizons is not on Caustica's compile classpath; the caller immediately copies every quad and
 * never keeps DH's list, so DH's pooling and reuse stay untouched.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder", remap = false)
public interface DhLodQuadBuilderAccessor {

    @Accessor("opaqueQuads")
    Object caustica$opaqueQuads();

    @Accessor("transparentQuads")
    Object caustica$transparentQuads();
}
