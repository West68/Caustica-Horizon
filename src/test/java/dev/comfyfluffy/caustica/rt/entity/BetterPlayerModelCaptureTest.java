package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.mixin.BetterPlayerModelNativeCaptureMixin;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BetterPlayerModelCaptureTest {
    private static boolean nativeLoaded(boolean loaded, VertexConsumer target) throws Exception {
        Method hook = BetterPlayerModelNativeCaptureMixin.class.getDeclaredMethod(
                "caustica$useCompleteRtMesh", boolean.class, VertexConsumer.class);
        hook.setAccessible(true);
        return (boolean) hook.invoke(null, loaded, target);
    }

    @Test void selectsCompleteJavaGeometryForBothRtSinks() throws Exception {
        var collector = new RtEntityCollector();
        var field = RtEntityCollector.class.getDeclaredField("customQuadVertexConsumer");
        field.setAccessible(true);
        var customSink = (VertexConsumer) field.get(collector);
        assertTrue(RtEntityCollector.isGeometryCapture(customSink));
        assertFalse(nativeLoaded(true, customSink));
        assertFalse(nativeLoaded(true, new RtEntityCapture()));
        assertFalse(nativeLoaded(false, customSink));
    }

    @Test void leavesRasterGuiAndMissingNativeLibraryAlone() throws Exception {
        VertexConsumer raster = new VertexConsumer() {
            public VertexConsumer addVertex(float x,float y,float z) { return this; }
            public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
            public VertexConsumer setColor(int color) { return this; }
            public VertexConsumer setUv(float u,float v) { return this; }
            public VertexConsumer setUv1(int u,int v) { return this; }
            public VertexConsumer setUv2(int u,int v) { return this; }
            public VertexConsumer setNormal(float x,float y,float z) { return this; }
            public VertexConsumer setLineWidth(float w) { return this; }
        };
        assertFalse(RtEntityCollector.isGeometryCapture(raster));
        assertTrue(nativeLoaded(true, raster));
        assertFalse(nativeLoaded(false, raster));
        assertTrue(nativeLoaded(true, null));
    }
}
