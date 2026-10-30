package dev.comfyfluffy.caustica.client;

import java.util.function.DoubleUnaryOperator;
import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicParallaxMathTest {
    private static double trace(double u,double ray,int steps,DoubleUnaryOperator depth) {
        if(depth.applyAsDouble(u)<=1e-6)return 0;
        double lo=0,hi=1;
        for(int i=1;i<=steps;i++) {
            double d=(double)i/steps;
            if(d>=depth.applyAsDouble(u-ray*d)) {hi=d;break;}
            lo=d;
        }
        for(int i=0;i<3;i++) {
            double d=(lo+hi)*.5;
            if(d>=depth.applyAsDouble(u-ray*d))hi=d;else lo=d;
        }
        return (lo+hi)*.5;
    }
    @Test void flatNormalMapHasExactlyZeroRelief() {
        assertEquals(0,trace(.5,.2,32,u->0));
    }
    @Test void constantAndSlopingHeightIntersectionsAgreeWithAnalyticRoots() {
        for(int steps:new int[]{8,16,32,40}) {
            assertEquals(.6,trace(.5,.2,steps,u->.6),1.0/(steps*8));
            assertEquals(.4/1.04,trace(.5,.2,steps,u->.3+.2*u),1.0/(steps*8));
        }
    }
    @Test void uvDualBasisSurvivesRotatedScaledAndMirroredFaces() {
        for(float mirror:new float[]{-1,1}) {
            var transform=new Matrix4f().rotateXYZ(.3f,-.6f,.8f).scale(2*mirror,.7f,1.3f);
            var u=transform.transformDirection(new Vector3f(1,0,0));
            var v=transform.transformDirection(new Vector3f(.2f,1,0));
            float a=u.dot(u),b=u.dot(v),c=v.dot(v),metric=a*c-b*b;
            var du=new Vector3f(u).mul(c).sub(new Vector3f(v).mul(b)).div(metric);
            var dv=new Vector3f(v).mul(a).sub(new Vector3f(u).mul(b)).div(metric);
            assertEquals(1,u.dot(du),1e-5);assertEquals(0,u.dot(dv),1e-5);
            assertEquals(0,v.dot(du),1e-5);assertEquals(1,v.dot(dv),1e-5);
        }
    }
}
