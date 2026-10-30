package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MirrorGuideChainTest {
    // Independently compare composed affine rows against explicit reverse-order plane reflections.
    static final class Transform {
        Vector3d[] rows={new Vector3d(1,0,0),new Vector3d(0,1,0),new Vector3d(0,0,1)};
        Vector3d offset=new Vector3d();
        Vector3d vector(Vector3d v){return new Vector3d(rows[0].dot(v),rows[1].dot(v),rows[2].dot(v));}
        Vector3d point(Vector3d p){return vector(p).add(offset);}
        void append(Vector3d p,Vector3d n){
            Vector3d cn=vector(n);
            offset.fma(2*p.dot(n),cn);
            for(int i=0;i<3;i++)rows[i].fma(-2*cn.get(i),n);
        }
    }
    static Vector3d reflect(Vector3d x,Vector3d p,Vector3d n){
        return new Vector3d(x).fma(-2*new Vector3d(x).sub(p).dot(n),n);
    }
    static void close(Vector3d a,Vector3d b){assertTrue(a.distance(b)<1e-9,a+" != "+b);}
    @Test void arbitraryMirrorPrefixesMatchExplicitUnfoldingAndPreserveNormalLength(){
        Random r=new Random(7125);
        for(int trial=0;trial<100;trial++){
            Transform t=new Transform();List<Vector3d> points=new ArrayList<>(),normals=new ArrayList<>();
            Vector3d input=new Vector3d(r.nextDouble(),r.nextDouble(),r.nextDouble());
            for(int count=0;count<6;count++){
                Vector3d p=new Vector3d(r.nextDouble()*20,r.nextDouble()*20,r.nextDouble()*20);
                Vector3d n=new Vector3d(r.nextDouble()-.5,r.nextDouble()-.5,r.nextDouble()-.5).normalize();
                points.add(p);normals.add(n);t.append(p,n);
                Vector3d expected=new Vector3d(input);
                for(int i=count;i>=0;i--)expected=reflect(expected,points.get(i),normals.get(i));
                close(expected,t.point(input));
                assertEquals(input.length(),t.vector(input).length(),1e-12);
            }
        }
    }
    @Test void parallelMirrorsProduceTranslationRatherThanLastPlaneFold(){
        Transform t=new Transform();t.append(new Vector3d(0,0,0),new Vector3d(1,0,0));
        t.append(new Vector3d(3,0,0),new Vector3d(-1,0,0));
        close(new Vector3d(-4,5,7),t.point(new Vector3d(2,5,7)));
        close(new Vector3d(2,5,7),t.vector(new Vector3d(2,5,7)));
    }
    @Test void objectMotionUsesLinearTransformWithoutCameraOrPlaneTranslation(){
        Transform t=new Transform();t.append(new Vector3d(7,3,2),new Vector3d(1,1,0).normalize());
        t.append(new Vector3d(-4,7,2),new Vector3d(0,1,2).normalize());
        Vector3d now=new Vector3d(2,3,5),disp=new Vector3d(.1,-.2,.3);
        close(t.point(now).sub(t.point(new Vector3d(now).sub(disp))),t.vector(disp));
        close(new Vector3d(),t.vector(new Vector3d()));
    }
}
