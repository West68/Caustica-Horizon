package dev.comfyfluffy.caustica.rt;

/** Finite exponential-atmosphere beam transfer, independently evaluated in the native 21-unit scale.
 * Matches the reference E12 depth=80 spectral attenuation, not the unrelated native atmosphere march.
 * This is a unit bridge; it does not assert identical scene lighting between Iris and hardware PT. */
public final class ItrpDirectLight {
    private ItrpDirectLight() {}
    public static float[] evaluate(float elevation,float rain,boolean moon) {
        if(elevation<=0f) return new float[]{0f,0f,0f};
        double k=.5*Math.max(elevation,1e-6f);
        double column=.14*(-Math.expm1(-80*k))/k;
        double amplitude=21*Math.min(elevation*40,1)*(moon?.002*8:1);
        double[] molecular={.17,.40,.99};float[] result=new float[3];
        for(int i=0;i<3;i++) result[i]=(float)(amplitude*Math.exp(-(molecular[i]+.05+.5*Math.clamp(rain,0f,1f))*column)*Math.pow(2,-molecular[i]*.2));
        return result;
    }
}
