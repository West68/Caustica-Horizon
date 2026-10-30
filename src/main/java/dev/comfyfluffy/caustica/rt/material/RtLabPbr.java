package dev.comfyfluffy.caustica.rt.material;

/**
 * Canonical decoder for LabPBR 1.3. roughness() is PERCEPTUAL roughness (1-red),
 * because the native GGX/VNDF path squares it to alpha. The standard's (1-red)^2
 * is linear GGX alpha and must not be squared a second time on upload.
 */
public final class RtLabPbr {
    private RtLabPbr() {
    }

    /** Decode normalized LabPBR channels and linear albedo into physical surface parameters. */
    public static Specular decodeSpec(float red, float green, float blue, float alpha,
                                      float albedoR, float albedoG, float albedoB) {
        float smoothness = clamp01(red);
        float roughness = 1.0f - smoothness;
        float g = clamp01(green) * 255.0f;
        float metalness;
        float f0r;
        float f0g;
        float f0b;
        if (g < 229.5f) {
            metalness = 0.0f;
            f0r = f0g = f0b = clamp01(green);
        } else {
            // Predefined (230..237) and albedo metals both reflect with the albedo colour. This is the
            // ITRP-PTGI default (LABPBR_PREDEFINED_METAL off): the measured iron/gold N,K constants give a
            // flat ~0.53 reflectance that discards the texture, so a dark anvil or iron tool turned an even
            // bright silver-white under sky and sun.
            metalness = 1.0f;
            f0r = clamp01(albedoR);
            f0g = clamp01(albedoG);
            f0b = clamp01(albedoB);
        }

        float a = clamp01(alpha) * 255.0f;
        float emission = a < 254.5f ? a / 254.0f : 0.0f;
        float b = clamp01(blue) * 255.0f;
        float sss = b > 64.5f ? (b - 65.0f) / 190.0f : 0.0f;
        return new Specular(roughness, metalness, f0r, f0g, f0b, emission, sss);
    }

    /** LabPBR blue 0..64 stores porosity; 65..255 is the independent SSS category. */
    public static float decodePorosity(float blue) {
        float code=clamp01(blue)*255f;
        return code<=64.5f ? Math.min(code/64f,1f) : 0f;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Specular(float roughness, float metalness,
                           float f0r, float f0g, float f0b,
                           float emission, float sss) {
    }
}
