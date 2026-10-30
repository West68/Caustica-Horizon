package dev.comfyfluffy.caustica.rt;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
/** DH's API arrays are row-major; JOML/our reflected shader serializers are column-major. */
public final class DhProjectionMath {
    private DhProjectionMath() {}
    public static Matrix4f fromRows(float[] rows,Matrix4f destination) {
        if(rows.length!=16)throw new IllegalArgumentException("4x4 matrix required");
        destination.set(rows).transpose();
        if(!destination.isFinite())throw new IllegalArgumentException("Invalid DH projection");
        return destination;
    }
    public static Matrix4f jittered(Matrix4fc projection,float x,float y,int width,int height,Matrix4f destination) {
        return destination.translation(-2*x/width,-2*y/height,0).mul(projection);
    }
}
