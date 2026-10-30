package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtLabPbrTest {
    private static final float EPS = 1.0e-5f;

    @Test
    void decodesDielectricAndIgnoredEmission() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                0.25f, 0.04f, 64.0f / 255.0f, 1.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.75f, value.roughness(), EPS);
        assertEquals(0.0f, value.metalness(), EPS);
        assertEquals(0.04f, value.f0r(), EPS);
        assertEquals(0.0f, value.emission(), EPS);
        assertEquals(0.0f, value.sss(), EPS);
    }

    @Test
    void decodesGenericMetalEmissionAndSss() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                0.5f, 1.0f, 1.0f, 127.0f / 255.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.5f, value.roughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        assertEquals(0.7f, value.f0r(), EPS);
        assertEquals(0.6f, value.f0g(), EPS);
        assertEquals(0.5f, value.f0b(), EPS);
        assertEquals(0.5f, value.emission(), 0.002f);
        assertEquals(1.0f, value.sss(), EPS);
    }

    @Test
    void nativeGgxSquaresOnceAndBedrockBytesStayRoughDielectric() {
        for(int red=0;red<=255;red++) {
            var v=RtLabPbr.decodeSpec(red/255f,0,0,1,.1f,.1f,.1f);
            assertEquals(Math.pow(1-red/255f,2),v.roughness()*v.roughness(),1e-7);
            assertEquals(0,v.metalness());
            if(red>=14 && red<=36) org.junit.jupiter.api.Assertions.assertTrue(v.roughness()>.85f);
        }
    }

    @Test
    void predefinedMetalsReflectWithTheirAlbedoLikeItrp() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                1.0f, 230.0f / 255.0f, 0.0f, 1.0f,
                0.06f, 0.06f, 0.07f); // dark anvil texel tagged as predefined iron
        assertEquals(0.0f, value.roughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        assertEquals(0.06f, value.f0r(), EPS);
        assertEquals(0.06f, value.f0g(), EPS);
        assertEquals(0.07f, value.f0b(), EPS);
    }
}
