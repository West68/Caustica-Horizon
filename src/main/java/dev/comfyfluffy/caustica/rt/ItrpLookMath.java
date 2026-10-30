package dev.comfyfluffy.caustica.rt;

/** Default artistic transfer functions from the user-owned ITRP-PTGI 0.8.31 reference. */
public final class ItrpLookMath {
    private ItrpLookMath() {}
    /** Scene-linear RGB; retain the reference's intensity instead of normalizing each channel. */
    public static float[] torchColor(float kelvin) {
        if (!Float.isFinite(kelvin) || kelvin < 1000 || kelvin > 12000)
            throw new IllegalArgumentException("Kelvin outside supported range");
        double x=kelvin-6500.0, xc=Math.copySign(Math.pow(Math.abs(x),1.1),x);
        double[] coefficients={.1,.4,.98};float[] rgb=new float[3];
        for(int i=0;i<3;i++) rgb[i]=(float)(.5*Math.exp(xc*(.00045*Math.pow(coefficients[i],.9)-.00017)));
        return rgb;
    }
}
