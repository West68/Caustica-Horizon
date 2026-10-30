package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.*;
import java.util.Optional;

/** A transparent intermediate needs alpha accumulation even when the source pass only wrote RGB. */
public final class SchematicOverlayState {
    public static ColorTargetState[] colors(ColorTargetState[] source) {
        ColorTargetState[] result=new ColorTargetState[source.length];
        for(int i=0;i<source.length;i++)result[i]=new ColorTargetState(
                Optional.of(BlendFunction.TRANSLUCENT),source[i].format(),ColorTargetState.WRITE_ALL);
        return result;
    }
    private SchematicOverlayState() {}
}
