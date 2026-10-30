package dev.comfyfluffy.caustica.rt.entity;
import org.joml.Matrix4f;
/** Vanilla glint motion, reading live options rather than a render state skipped by the RT renderer. */
final class RtGlintTransform {
    static Matrix4f matrix(long millis,double speed,float scale) {
        long phase=(long)(millis*speed*8.0);
        return new Matrix4f().translation(-(phase%110000L)/110000f,(phase%30000L)/30000f,0)
                .rotateZ(.17453292f).scale(scale);
    }
}
