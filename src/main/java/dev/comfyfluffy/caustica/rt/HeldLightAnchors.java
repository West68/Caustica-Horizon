package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Render-thread measurements in entity-anchor space; independent of model height, yaw and skeleton. */
final class HeldLightAnchors {
    private Object owner, world;
    private final Object[] items = new Object[2];
    private final long[] frames = {Long.MIN_VALUE, Long.MIN_VALUE};
    private final int[] counts = new int[2];
    private final double[][] sums = new double[2][3];
    private final Matrix4f inverse = new Matrix4f();

    void context(Object owner, Object world, Object main, Object off) {
        if (this.owner != owner || this.world != world) {
            frames[0] = frames[1] = Long.MIN_VALUE;
            this.owner = owner;
            this.world = world;
        }
        if (items[0] != main) frames[0] = Long.MIN_VALUE;
        if (items[1] != off) frames[1] = Long.MIN_VALUE;
        items[0] = main; items[1] = off;
    }

    void record(int hand, long frame, float[] vertices, int from, int to,
                Matrix4fc itemPose, boolean flameTop) {
        if (hand < 0 || hand > 1 || from < 0 || to > vertices.length || to - from < 9
                || from % 3 != 0 || (to - from) % 3 != 0) return;
        // Find a flame's tip along the ITEM's local up axis, not world up: YSM may tilt/scale the hand.
        boolean local = flameTop && itemPose != null;
        if (local) {
            float det = itemPose.determinant();
            if (!Float.isFinite(det) || Math.abs(det) < 1e-12f) return;
            itemPose.invert(inverse);
        }
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = from; i < to; i += 3) {
            float x=vertices[i], y=vertices[i+1], z=vertices[i+2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
            float h=local ? localY(x,y,z) : y;
            if (!Float.isFinite(h)) return;
            min=Math.min(min,h); max=Math.max(max,h);
        }
        float cut=flameTop ? max-.25f*(max-min) : Float.NEGATIVE_INFINITY;
        double x=0,y=0,z=0; int n=0;
        for (int i=from;i<to;i+=3) {
            float h=local ? localY(vertices[i],vertices[i+1],vertices[i+2]) : vertices[i+1];
            if (h>=cut) { x+=vertices[i]; y+=vertices[i+1]; z+=vertices[i+2]; n++; }
        }
        if (n==0) return;
        if (frames[hand]!=frame) {
            frames[hand]=frame; counts[hand]=0;
            sums[hand][0]=sums[hand][1]=sums[hand][2]=0;
        }
        // One item can submit several layers. Combine them instead of using whichever layer was last.
        sums[hand][0]+=x; sums[hand][1]+=y; sums[hand][2]+=z; counts[hand]+=n;
    }

    private float localY(float x,float y,float z) {
        return inverse.m01()*x+inverse.m11()*y+inverse.m21()*z+inverse.m31();
    }

    boolean position(int hand,long frame,Vector3f result) {
        long age=frame-frames[hand];
        // Brief missing submissions must not jump to a vanilla-height hand on every arm swing.
        if (frames[hand]==Long.MIN_VALUE || age<0 || age>2 || counts[hand]==0) return false;
        result.set((float)(sums[hand][0]/counts[hand]),(float)(sums[hand][1]/counts[hand]),
                (float)(sums[hand][2]/counts[hand]));
        return true;
    }

    static float influenceRadius(float configured, boolean creativeFlying) {
        // Creative flight used to clamp the radius to 3 blocks "so it does not light distant ground". That
        // made the held light collapse into a small bright disc as soon as the player left the ground,
        // because heldFalloff reaches 0 exactly at the radius. The configured radius already bounds the
        // reach, and the falloff (w*w/(d*d+4)) keeps the ground dimmer as the player climbs, so the clamp
        // is gone: flying and standing now use the same radius.
        return configured;
    }
}
