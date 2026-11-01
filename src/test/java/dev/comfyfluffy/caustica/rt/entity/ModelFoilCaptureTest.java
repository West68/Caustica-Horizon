package dev.comfyfluffy.caustica.rt.entity;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelFoilCaptureTest {
    @Test
    void enchantmentRetainsBaseGeometryTextureCoverageAndMaterial() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentTexSlot = 7;
        capture.currentMaterialId = 23;
        capture.addDirectQuad(new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                new float[]{0, 0, 0, 0}, new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                0, 0, 1, -1);
        float[] positions = capture.verts.toFloatArray();
        int[] indices = capture.idx.toIntArray();
        float[] uv = capture.uvList.toFloatArray();
        capture.applyModelFoil(0, capture.prim.size(), 11, new Matrix4f().translation(.25f, .5f, 0), 1f);
        assertArrayEquals(positions, capture.verts.toFloatArray());
        assertArrayEquals(indices, capture.idx.toIntArray());
        assertArrayEquals(uv, capture.uvList.toFloatArray());
        assertEquals(24, capture.prim.size());
        for (int p = 0; p < capture.prim.size(); p += 12) {
            assertEquals(23, Float.floatToRawIntBits(capture.prim.getFloat(p + 8)));
            int flags = Float.floatToRawIntBits(capture.prim.getFloat(p + 9));
            assertEquals(7, flags >>> 16);
            assertEquals(8192 | 16384 | 255, flags & 65535);
            assertEquals(11, capture.prim.getFloat(p + 7));
        }
        int effectUv = Float.floatToRawIntBits(capture.prim.getFloat(3));
        assertEquals(.25f, Float.float16ToFloat((short) effectUv));
        assertEquals(.5f, Float.float16ToFloat((short) (effectUv >>> 16)));
    }

    @Test
    void alphaBearingGeometryKeepsItsOpacityData() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.addDirectQuad(new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                new float[]{0, 0, 0, 0}, new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
                0, 0, 1, 0x80FFFFFF);
        float[] original = capture.prim.toFloatArray();
        capture.applyModelFoil(0, capture.prim.size(), 11, new Matrix4f(), 1f);
        assertArrayEquals(original, capture.prim.toFloatArray());
    }
}
