package dev.comfyfluffy.caustica.rt;
/** Pure transport helpers, mirrored in environment_effects.slang and exercised without a GPU. */
public final class VisualMath {
    public static double attenuation(double distance,double radius) {
        if (!(radius>0) || distance<0 || distance>=radius) return 0;
        double x=distance/radius, window=Math.max(0,1-x*x*x*x);
        return window*window/(distance*distance+0.25);
    }
    public static double transmittance(double extinction,double distance) {
        return Math.exp(-Math.max(0,extinction)*Math.max(0,distance));
    }
    public static double phase(double cosine,double g) {
        double d=1+g*g-2*g*Math.clamp(cosine,-1,1);
        return (1-g*g)/(4*Math.PI*Math.pow(Math.max(d,1e-6),1.5));
    }
    public static float[] parseRule(String entry) {
        int split=entry.lastIndexOf(':');
        if(split<1)throw new IllegalArgumentException("Expected item:level");
        HeldLightRule.id(entry.substring(0,split));
        int level=Integer.parseInt(entry.substring(split+1));
        if(level<0 || level>15)throw new IllegalArgumentException("Luminance outside 0..15");
        return new float[]{level,1f,0.5f,0.2f};
    }
    public record MediumSample(double distance,double pdf){}
    public static MediumSample sampleMedium(double rate,double distance,double u) {
        if(distance<=0 || rate<0 || u<0 || u>=1)throw new IllegalArgumentException();
        if(rate*distance<.001)return new MediumSample(u*distance,1/distance);
        double norm=-Math.expm1(-rate*distance);
        double t=-Math.log1p(-u*norm)/rate;
        return new MediumSample(t,rate*Math.exp(-rate*t)/norm);
    }
    public static String[] splitRules(String text) { return text.trim().split("\\s+"); }
    private VisualMath(){}
}
