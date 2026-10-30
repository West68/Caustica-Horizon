package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A pickup particle can scale a quad to zero or emit a non-finite pose. Such geometry must never reach a
 * BLAS: a NaN vertex poisons the BVH and every later trace reads garbage, which is one way an item pickup
 * can break path tracing. The rule is pure, so it is tested without a device.
 */
class ModelParticleGeometryTest {

    @Test
    void acceptsFiniteTriangles() {
        assertTrue(RtEntities.finiteGeometry(3,
                new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f}, 9,
                new float[] {0f, 0f, 1f, 0f, 0f, 1f}, 6));
    }

    @Test
    void rejectsEmptyIndexBuffer() {
        assertFalse(RtEntities.finiteGeometry(0, new float[0], 0, new float[0], 0));
        assertFalse(RtEntities.finiteGeometry(2, new float[] {0f, 0f, 0f}, 3, new float[0], 0));
    }

    @Test
    void rejectsNanPosition() {
        assertFalse(RtEntities.finiteGeometry(3,
                new float[] {0f, Float.NaN, 0f}, 3, new float[0], 0));
    }

    @Test
    void rejectsInfinitePosition() {
        assertFalse(RtEntities.finiteGeometry(3,
                new float[] {Float.POSITIVE_INFINITY, 0f, 0f}, 3, new float[0], 0));
    }

    @Test
    void rejectsNanUv() {
        assertFalse(RtEntities.finiteGeometry(3,
                new float[] {0f, 0f, 0f}, 3, new float[] {0f, Float.NaN}, 2));
    }
}
