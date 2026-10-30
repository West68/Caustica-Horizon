package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtEntityCaptureTest {
    private static final float[] X = {0f, 1f, 1f, 0f};
    private static final float[] Y = {0f, 0f, 1f, 1f};
    private static final float[] Z = {0f, 0f, 0f, 0f};
    private static final float[] U = {0f, 1f, 1f, 0f};
    private static final float[] V = {0f, 0f, 1f, 1f};

    @Test void foilKeepsExactBaseCutoutUvsAndIndependentAnimatedUvsAfterBucketPacking() {
        var c=new RtEntityCapture();c.currentAlphaBucket=RtAccel.ENTITY_BUCKET_ANY_HIT;
        c.addDirectQuad(X,Y,Z,U,V,0,0,1,-1);
        float[] baseU={.725001f,.735003f,.735003f,.725001f};
        float[] baseV={.121003f,.121003f,.139997f,.139997f};
        c.markFoilCoverage(0,0,17,baseU[0],baseV[0],baseU[1],baseV[1],baseU[2],baseV[2],baseU[3],baseV[3]);
        // An opaque quad forces a bucket reorder; the two foil records must still carry UVs 012/023.
        addQuad(c,RtAccel.ENTITY_BUCKET_OPAQUE,11);
        var packed=c.packGeometry();
        for(int v=0;v<4;v++) {
            assertEquals(baseU[v],c.uvList.getFloat(v*2),0f);
            assertEquals(baseV[v],c.uvList.getFloat(v*2+1),0f);
        }
        int[][] corners={{0,1,2},{0,2,3}};
        for(int t=0;t<2;t++) {
            int p=(t+2)*12;
            int flags=Float.floatToRawIntBits(packed.primitives().getFloat(p+9));
            assertEquals(16384,flags&65535);assertEquals(17,flags>>>16);
            int[] words={Float.floatToRawIntBits(packed.primitives().getFloat(p+3)),
                Float.floatToRawIntBits(packed.primitives().getFloat(p+10)),
                Float.floatToRawIntBits(packed.primitives().getFloat(p+11))};
            for(int v=0;v<3;v++) {
                assertEquals(U[corners[t][v]],Float.float16ToFloat((short)words[v]),.001f);
                assertEquals(V[corners[t][v]],Float.float16ToFloat((short)(words[v]>>>16)),.001f);
            }
        }
    }

    @Test
    void packsTrianglesIntoFixedAlphaBucketOrder() {
        RtEntityCapture capture = new RtEntityCapture();
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 22);
        addQuad(capture, RtAccel.ENTITY_BUCKET_OPAQUE, 11);
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 33);

        RtEntityCapture.PackedGeometry packed = capture.packGeometry();

        assertArrayEquals(new int[] {2, 4}, packed.bucketTris());
        assertArrayEquals(new int[] {
                4, 5, 6, 4, 6, 7,
                0, 1, 2, 0, 2, 3,
                8, 9, 10, 8, 10, 11
        }, packed.indices().toIntArray());
        assertMaterialIds(packed, 11, 11, 22, 22, 33, 33);
    }

    @Test
    void resetClearsBucketMetadata() {
        RtEntityCapture capture = new RtEntityCapture();
        addQuad(capture, RtAccel.ENTITY_BUCKET_OPAQUE, 11);

        capture.reset();

        assertArrayEquals(new int[] {0, 0}, capture.packGeometry().bucketTris());
    }

    @Test void dynamicUvTransformMatchesBulkAndDirectCaptureWithoutChangingPositions() {
        var matrix=new org.joml.Matrix4f().translate(.25f,-.4f,0).rotateZ(.35f).scale(2);
        var direct=new RtEntityCapture();direct.textureTransform=matrix;
        direct.addDirectQuad(X,Y,Z,U,V,0,0,1,-1);
        var bulk=new RtEntityCapture();bulk.textureTransform=matrix;
        for(int i=0;i<4;i++)bulk.addVertex(X[i],Y[i],Z[i],-1,U[i],V[i],0,0,0,0,1);
        assertArrayEquals(direct.uvList.toFloatArray(),bulk.uvList.toFloatArray());
        for(int i=0;i<4;i++) {
            var uv=matrix.transformPosition(new org.joml.Vector3f(U[i],V[i],0));
            assertEquals(uv.x,direct.uvList.getFloat(i*2),1e-6f);
            assertEquals(uv.y,direct.uvList.getFloat(i*2+1),1e-6f);
            assertEquals(X[i],direct.verts.getFloat(i*3));
        }
        direct.clearUvRemap();assertEquals(null,direct.textureTransform);
    }

    private static void addQuad(RtEntityCapture capture, int bucket, int materialId) {
        capture.currentAlphaBucket = bucket;
        capture.currentMaterialId = materialId;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
    }

    private static void assertMaterialIds(RtEntityCapture.PackedGeometry packed, int... expected) {
        assertEquals(expected.length * 12, packed.primitives().size());
        for (int triangle = 0; triangle < expected.length; triangle++) {
            int actual = Float.floatToRawIntBits(packed.primitives().getFloat(triangle * 12 + 8));
            assertEquals(expected[triangle], actual, "material id for packed triangle " + triangle);
        }
    }
}
