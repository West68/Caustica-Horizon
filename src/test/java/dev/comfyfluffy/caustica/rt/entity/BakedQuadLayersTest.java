package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BakedQuadLayersTest {
    private static void face(RtEntityCapture c, BakedQuadLayers layers, float z, boolean overlay, boolean reverse) {
        float[] x = {0, 1, 1, 0}, y = {0, 0, 1, 1}, zs = {z, z, z, z};
        if (reverse) { x = new float[]{0, 0, 1, 1}; y = new float[]{0, 1, 1, 0}; }
        c.addDirectQuad(x, y, zs, new float[]{0,1,1,0}, new float[]{0,0,1,1},
                0, 0, reverse ? -1 : 1, overlay ? 0xFF55AA33 : -1);
        layers.record(c, overlay);
    }

    @Test void baseRemainsAndOverlayMovesEvenWhenSubmittedFirst() {
        var c = new RtEntityCapture(); var layers = new BakedQuadLayers();
        layers.begin(); face(c,layers,0,true,false); face(c,layers,0,false,false);
        float[] uv = c.uvList.toFloatArray(), prim = c.prim.toFloatArray();
        int[] indices = c.idx.toIntArray(), buckets = c.alphaBuckets.toIntArray();
        layers.end(c);
        assertEquals(BakedQuadLayers.OFFSET,c.verts.getFloat(2),1e-8);
        assertEquals(0,c.verts.getFloat(14));
        assertArrayEquals(uv,c.uvList.toFloatArray()); assertArrayEquals(prim,c.prim.toFloatArray());
        assertArrayEquals(indices,c.idx.toIntArray()); assertArrayEquals(buckets,c.alphaBuckets.toIntArray());
    }
    @Test void nestedPartsResolveOnceAndOppositeWindingsSeparate() {
        var c = new RtEntityCapture(); var layers = new BakedQuadLayers();
        layers.begin(); layers.begin(); face(c,layers,0,false,false); layers.end(c);
        layers.begin(); face(c,layers,0,true,true); layers.end(c);
        assertEquals(0,c.verts.getFloat(14));
        layers.end(c);
        assertEquals(-BakedQuadLayers.OFFSET,c.verts.getFloat(14),1e-8);
    }
    @Test void unrelatedFacesAndLaterSubmissionsStayInPlace() {
        var c = new RtEntityCapture(); var layers = new BakedQuadLayers();
        layers.begin(); face(c,layers,0,false,false); face(c,layers,.1f,true,false); layers.end(c);
        assertEquals(.1f,c.verts.getFloat(14));
        layers.begin(); face(c,layers,0,true,false); layers.end(c);
        assertEquals(0,c.verts.getFloat(26));
    }
    @Test void animatedRotationAndLargeRebasedTranslationPreserveMatching() {
        var c = new RtEntityCapture(); var layers = new BakedQuadLayers();
        float invSqrt2 = (float)Math.sqrt(.5);
        float[] x={8192,8193,8193,8192};
        float[] y={0,0,invSqrt2,invSqrt2};
        float[] z={0,0,invSqrt2,invSqrt2};
        float[] u={0,1,1,0}, v={0,0,1,1};
        layers.begin();
        c.addDirectQuad(x,y,z,u,v,0,-invSqrt2,invSqrt2,-1); layers.record(c,false);
        c.addDirectQuad(x,y,z,u,v,0,-invSqrt2,invSqrt2,-1); layers.record(c,true);
        layers.end(c);
        assertEquals(-invSqrt2*BakedQuadLayers.OFFSET,c.verts.getFloat(13),1e-8);
        assertEquals(invSqrt2*BakedQuadLayers.OFFSET,c.verts.getFloat(14),1e-8);
    }
    @Test void pathologicalModelsKeepBoundedCostWithoutPartiallyMovingLayers() {
        var c = new RtEntityCapture(); var layers = new BakedQuadLayers();
        layers.begin();
        for(int i=0;i<=BakedQuadLayers.LIMIT;i++) face(c,layers,0,true,false);
        layers.end(c);
        for(int i=2;i<c.verts.size();i+=3) assertEquals(0,c.verts.getFloat(i));
    }
}
