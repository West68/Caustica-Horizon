package dev.comfyfluffy.caustica.rt.pipeline;

import java.util.ArrayList;
import java.util.List;

/** Bounded dual-filter pyramid. All weights are normalized, including tiny/odd viewports. */
public final class ItrpBloomPlan {
    public static final int MAX_LEVELS=9;
    // Bloom_FS.glsl BLOOM_EXTENDED, ordinary opaque-pixel branch. The pack default HIGHLIGHT_DIFFUSION_FILTER=1
    // scales the four finest levels by weightLodN(filter 1)/weightLodN(filter 0) = 2, 1.92, 1.728, 1.5552.
    private static final double[] BASE={2,1.25,.83333333,.75231481,.67515432,.60281636,.53583677,.4744388,.09302722};
    private static final double[] DIFFUSION={2.0/1.0,1.6/.83333333,1.2/.69444444,.9/.5787037,1,1,1,1,1};
    static final double[] WEIGHTS=new double[BASE.length];
    static {for(int i=0;i<BASE.length;i++)WEIGHTS[i]=BASE[i]*DIFFUSION[i];}
    public record Size(int width,int height) {}
    private ItrpBloomPlan() {}
    public static List<Size> levels(int width,int height) {
        if(width<1 || height<1)throw new IllegalArgumentException("Empty bloom viewport");
        var result=new ArrayList<Size>();
        do {
            width=width/2+width%2;height=height/2+height%2;
            result.add(new Size(width,height));
        } while(result.size()<MAX_LEVELS && (width>1 || height>1));
        return List.copyOf(result);
    }
    /** Fraction contributed by the already-normalized lower pyramid (reference extended LOD weights). */
    public static float lowMix(int level,int count) {
        if(level<0 || count<1 || count>MAX_LEVELS || level>=count)throw new IllegalArgumentException();
        double tail=0;
        for(int i=level+1;i<count;i++)tail+=WEIGHTS[i];
        return (float)(tail/(WEIGHTS[level]+tail));
    }
    public static long imageBytes(int width,int height) {
        var sizes=levels(width,height);long pixels=0;
        for(int i=0;i<sizes.size();i++) {
            var s=sizes.get(i);pixels+=Math.multiplyExact((long)s.width,s.height)*(i==sizes.size()-1?1:2);
        }
        return Math.multiplyExact(pixels,8); // RGBA16F; source/display images are not duplicated.
    }
}
