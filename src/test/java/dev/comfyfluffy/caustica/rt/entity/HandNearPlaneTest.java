package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class HandNearPlaneTest {
    @Test void pushesTheEntireAnimatedHandAheadOfTheEye() {
        float[] depths = {-0.35f, -0.01f, 0.03f, 0.08f, 0.5f};
        for (float depth : depths) {
            float push = HandProjection.nearPlanePush(depth);
            assertTrue(depth + push >= 0.0799999f);
            assertTrue(HandProjection.safeDepthScale(.5f, depth + push) * (depth + push) >= .0799999f);
        }
        assertEquals(0f, HandProjection.nearPlanePush(.5f));
    }

    @Test void cameraSpaceCorrectionWorksWithRotatedView() {
        Matrix4f view = new Matrix4f().rotateXYZ(.3f, .7f, -.2f);
        Vector3f vertex = new Vector3f(.4f, -.2f, .1f);
        float before = -view.transformDirection(new Vector3f(vertex)).z;
        float push = HandProjection.nearPlanePush(before);
        Vector3f correction = new Vector3f(0f, 0f, -push);
        new Matrix4f(view).invert().transformDirection(correction);
        float after = -view.transformDirection(vertex.add(correction)).z;
        assertTrue(after >= .0799999f);
    }
}
