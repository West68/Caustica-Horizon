package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.RuntimeSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** One settings row: the widget plus the settings its reset button restores (empty = no reset button). */
    public record Row(OptionInstance<?> option, List<RuntimeSetting<?>> settings) {
        public boolean isDefault() {
            return settings.stream().allMatch(s -> s.get().equals(s.defaultValue()));
        }

        public void reset() {
            for (RuntimeSetting<?> s : settings) s.set(null); // set(null) restores the stored default
        }
    }

    /** A titled group of rows on one settings page. */
    public record Section(String titleKey, List<Row> rows) {}

    private static Row row(OptionInstance<?> option, RuntimeSetting<?>... settings) {
        return new Row(option, List.of(settings));
    }

    private static Section section(String name, Row... rows) {
        return new Section("caustica.enhanced.section." + name, List.of(rows));
    }

    // The advanced page only holds diagnostics (debug views, frame stats); shown in release too so
    // players can send debug-view screenshots.
    public static final List<String> PAGES = List.of("color", "clouds", "material", "lighting", "world", "rt", "fg", "advanced");

    public static List<Section> sections(String category) {
        return switch (category) {
            case "color" -> List.of(
                section("tone",
                    row(itrpLook(), CausticaConfig.Rt.Look.ITRP),
                    row(itrpTone(), CausticaConfig.Rt.Look.ITRP_TONE),
                    row(scalar("vignette", CausticaConfig.Rt.Look.VIGNETTE, 0f, 1.5f), CausticaConfig.Rt.Look.VIGNETTE),
                    row(bool("caustica.options.rt.aces2", CausticaConfig.Rt.Look.ACES2), CausticaConfig.Rt.Look.ACES2),
                    row(scalar("brightness", CausticaConfig.Rt.Look.BRIGHTNESS, .25f, 2f), CausticaConfig.Rt.Look.BRIGHTNESS),
                    row(scalar("nightBrightness", CausticaConfig.Rt.Look.NIGHT_BRIGHTNESS, .1f, 2f), CausticaConfig.Rt.Look.NIGHT_BRIGHTNESS),
                    row(scalar("contrast", CausticaConfig.Rt.Look.CONTRAST, .5f, 1.5f), CausticaConfig.Rt.Look.CONTRAST),
                    row(scalar("saturation", CausticaConfig.Rt.Look.SATURATION, 0f, 2f), CausticaConfig.Rt.Look.SATURATION),
                    row(scalar("temperature", CausticaConfig.Rt.Look.TEMPERATURE, -1f, 1f), CausticaConfig.Rt.Look.TEMPERATURE),
                    row(scalar("gamma", CausticaConfig.Rt.Look.GAMMA, .5f, 2f), CausticaConfig.Rt.Look.GAMMA),
                    row(scalar("shadowLift", CausticaConfig.Rt.Look.SHADOW_LIFT, 0f, .25f), CausticaConfig.Rt.Look.SHADOW_LIFT),
                    row(scalar("highlightSoftness", CausticaConfig.Rt.Look.HIGHLIGHT_SOFTNESS, 0f, 2f), CausticaConfig.Rt.Look.HIGHLIGHT_SOFTNESS),
                    row(scalar("bloom", CausticaConfig.Rt.Look.BLOOM, 0f, 3f), CausticaConfig.Rt.Look.BLOOM),
                    row(scalar("detailSharpen", CausticaConfig.Rt.Look.DETAIL_SHARPEN, 0f, 3f), CausticaConfig.Rt.Look.DETAIL_SHARPEN)),
                section("exposure",
                    row(exposureMode(), CausticaConfig.Rt.Exposure.MODE),
                    row(manualEv(), CausticaConfig.Rt.Exposure.MANUAL_EV),
                    row(scalar("exposureKey", CausticaConfig.Rt.Exposure.KEY, .05f, .5f), CausticaConfig.Rt.Exposure.KEY),
                    row(ev("caustica.enhanced.minEv", CausticaConfig.Rt.Exposure.MIN_EV, -80, 20), CausticaConfig.Rt.Exposure.MIN_EV),
                    row(ev("caustica.enhanced.maxEv", CausticaConfig.Rt.Exposure.MAX_EV, -20, 80), CausticaConfig.Rt.Exposure.MAX_EV),
                    row(scalar("adaptUp", CausticaConfig.Rt.Exposure.ADAPT_UP, .01f, 1f), CausticaConfig.Rt.Exposure.ADAPT_UP),
                    row(scalar("adaptDown", CausticaConfig.Rt.Exposure.ADAPT_DOWN, .01f, 1f), CausticaConfig.Rt.Exposure.ADAPT_DOWN)));
            case "clouds" -> List.of(
                section("celestial",
                    row(sunSize(), CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS),
                    row(degrees("caustica.enhanced.moonSize", CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS, 1, 50, 10), CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS),
                    row(degrees("caustica.enhanced.sunTilt", CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT, -60, 60, 1), CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT),
                    row(scalar("sunDiscSize", CausticaConfig.Rt.Look.SUN_DISC_SIZE, .2f, 3f), CausticaConfig.Rt.Look.SUN_DISC_SIZE),
                    row(sunShape(), CausticaConfig.Rt.Look.SUN_SHAPE),
                    row(scalar("sunGlow", CausticaConfig.Rt.Look.SUN_GLOW, 0f, 3f), CausticaConfig.Rt.Look.SUN_GLOW),
                    row(scalar("sunRims", CausticaConfig.Rt.Look.SUN_RIMS, 0f, 3f), CausticaConfig.Rt.Look.SUN_RIMS),
                    row(toggle("caustica.enhanced.itrpStars", CausticaConfig.Rt.Look.STARS), CausticaConfig.Rt.Look.STARS),
                    row(scalar("starDensity", CausticaConfig.Rt.Look.STAR_DENSITY, 0f, 1f), CausticaConfig.Rt.Look.STAR_DENSITY),
                    row(toggle("caustica.enhanced.nebula", CausticaConfig.Rt.Look.NEBULA), CausticaConfig.Rt.Look.NEBULA)),
                section("clouds",
                    row(clouds(), CausticaConfig.Rt.Clouds.MODE),
                    row(scalar("cloudCoverage", CausticaConfig.Rt.Clouds.COVERAGE, -0.3f, 0.3f), CausticaConfig.Rt.Clouds.COVERAGE),
                    row(scalar("cloudDensity", CausticaConfig.Rt.Clouds.DENSITY, 0f, 2f), CausticaConfig.Rt.Clouds.DENSITY),
                    row(scalar("cloudSize", CausticaConfig.Rt.Clouds.SIZE, 0.5f, 2f), CausticaConfig.Rt.Clouds.SIZE),
                    row(scalar("cloudHeight", CausticaConfig.Rt.Clouds.HEIGHT, 500f, 1500f), CausticaConfig.Rt.Clouds.HEIGHT),
                    row(scalar("cloudBrightness", CausticaConfig.Rt.Clouds.BRIGHTNESS, 0.25f, 2f), CausticaConfig.Rt.Clouds.BRIGHTNESS),
                    row(scalar("cloudBottom", CausticaConfig.Rt.Clouds.BOTTOM, 0.25f, 2f), CausticaConfig.Rt.Clouds.BOTTOM),
                    row(scalar("cloudSunlight", CausticaConfig.Rt.Clouds.SUNLIGHT, 0f, 5f), CausticaConfig.Rt.Clouds.SUNLIGHT),
                    row(scalar("cloudSpeed", CausticaConfig.Rt.Clouds.SPEED, 0f, 3f), CausticaConfig.Rt.Clouds.SPEED),
                    row(scalar("cloudEdge", CausticaConfig.Rt.Clouds.EDGE, 0f, 2f), CausticaConfig.Rt.Clouds.EDGE),
                    row(scalar("cloudCirrus", CausticaConfig.Rt.Clouds.CIRRUS, 0f, 2f), CausticaConfig.Rt.Clouds.CIRRUS),
                    row(scalar("cloudRain", CausticaConfig.Rt.Clouds.RAIN, 0f, 1f), CausticaConfig.Rt.Clouds.RAIN),
                    row(toggle("caustica.enhanced.cloudShadow", CausticaConfig.Rt.Look.CLOUD_SHADOW), CausticaConfig.Rt.Look.CLOUD_SHADOW)),
                section("volumetricClouds",
                    row(scalar("volAltitude", CausticaConfig.Rt.Clouds.VOL_ALTITUDE, 100f, 3000f), CausticaConfig.Rt.Clouds.VOL_ALTITUDE),
                    row(scalar("volThickness", CausticaConfig.Rt.Clouds.VOL_THICKNESS, 100f, 3000f), CausticaConfig.Rt.Clouds.VOL_THICKNESS),
                    row(scalar("volCoverage", CausticaConfig.Rt.Clouds.VOL_COVERAGE, .05f, .98f), CausticaConfig.Rt.Clouds.VOL_COVERAGE),
                    row(scalar("volRainCoverage", CausticaConfig.Rt.Clouds.VOL_RAIN_COVERAGE, .05f, .98f), CausticaConfig.Rt.Clouds.VOL_RAIN_COVERAGE),
                    row(scalar("volDensity", CausticaConfig.Rt.Clouds.VOL_DENSITY, .01f, 3f), CausticaConfig.Rt.Clouds.VOL_DENSITY),
                    row(scalar("volScale", CausticaConfig.Rt.Clouds.VOL_SCALE, .1f, 3f), CausticaConfig.Rt.Clouds.VOL_SCALE),
                    row(scalar("volSunlight", CausticaConfig.Rt.Clouds.VOL_SUNLIGHT, 0f, 8f), CausticaConfig.Rt.Clouds.VOL_SUNLIGHT),
                    row(scalar("volRainSunlight", CausticaConfig.Rt.Clouds.VOL_RAIN_SUNLIGHT, 0f, 8f), CausticaConfig.Rt.Clouds.VOL_RAIN_SUNLIGHT),
                    row(scalar("volRainDensity", CausticaConfig.Rt.Clouds.VOL_RAIN_DENSITY, 0f, 4f), CausticaConfig.Rt.Clouds.VOL_RAIN_DENSITY),
                    row(scalar("volRainScale", CausticaConfig.Rt.Clouds.VOL_RAIN_SCALE, 0f, 4f), CausticaConfig.Rt.Clouds.VOL_RAIN_SCALE),
                    row(scalar("volSkylight", CausticaConfig.Rt.Clouds.VOL_SKYLIGHT, 0f, 4f), CausticaConfig.Rt.Clouds.VOL_SKYLIGHT),
                    row(milli("volBaseNoise", CausticaConfig.Rt.Clouds.VOL_BASE_NOISE, .0001f, .005f), CausticaConfig.Rt.Clouds.VOL_BASE_NOISE),
                    row(milli("volDetailNoise", CausticaConfig.Rt.Clouds.VOL_DETAIL_NOISE, .001f, .05f), CausticaConfig.Rt.Clouds.VOL_DETAIL_NOISE),
                    row(scalar("volDetailStrength", CausticaConfig.Rt.Clouds.VOL_DETAIL_STRENGTH, 0f, 3f), CausticaConfig.Rt.Clouds.VOL_DETAIL_STRENGTH),
                    row(scalar("volCoverageOffset", CausticaConfig.Rt.Clouds.VOL_COVERAGE_OFFSET, 0f, 1f), CausticaConfig.Rt.Clouds.VOL_COVERAGE_OFFSET),
                    row(scalar("volQuality", CausticaConfig.Rt.Clouds.VOL_QUALITY, .4f, 3f), CausticaConfig.Rt.Clouds.VOL_QUALITY)),
                section("fog",
                    row(scalar("fogDensity", CausticaConfig.Rt.Visuals.FOG, 0f, 4f), CausticaConfig.Rt.Visuals.FOG),
                    row(scalar("fogHeight", CausticaConfig.Rt.Visuals.FOG_HEIGHT, 16f, 256f), CausticaConfig.Rt.Visuals.FOG_HEIGHT),
                    row(scalar("fogRain", CausticaConfig.Rt.Visuals.FOG_RAIN, 0f, 4f), CausticaConfig.Rt.Visuals.FOG_RAIN),
                    row(scalar("fogHaze", CausticaConfig.Rt.Visuals.FOG_HAZE, 0f, 2f), CausticaConfig.Rt.Visuals.FOG_HAZE),
                    row(scalar("fogSun", CausticaConfig.Rt.Visuals.FOG_SUN, 0f, 8f), CausticaConfig.Rt.Visuals.FOG_SUN),
                    row(scalar("fogBase", CausticaConfig.Rt.Visuals.FOG_BASE, 0f, 1f), CausticaConfig.Rt.Visuals.FOG_BASE),
                    row(scalar("fogLayer", CausticaConfig.Rt.Visuals.FOG_LAYER, 0f, 4f), CausticaConfig.Rt.Visuals.FOG_LAYER),
                    row(scalar("fogLayerBottom", CausticaConfig.Rt.Visuals.FOG_LAYER_BOTTOM, -64f, 320f), CausticaConfig.Rt.Visuals.FOG_LAYER_BOTTOM),
                    row(scalar("fogLayerTop", CausticaConfig.Rt.Visuals.FOG_LAYER_TOP, -64f, 320f), CausticaConfig.Rt.Visuals.FOG_LAYER_TOP),
                    row(scalar("fogLayerFalloff", CausticaConfig.Rt.Visuals.FOG_LAYER_FALLOFF, 1f, 100f), CausticaConfig.Rt.Visuals.FOG_LAYER_FALLOFF),
                    row(scalar("fogLayerCoverage", CausticaConfig.Rt.Visuals.FOG_LAYER_COVERAGE, 0f, 1f), CausticaConfig.Rt.Visuals.FOG_LAYER_COVERAGE),
                    row(scalar("fogLayerLight", CausticaConfig.Rt.Visuals.FOG_LAYER_LIGHT, 0f, 10f), CausticaConfig.Rt.Visuals.FOG_LAYER_LIGHT),
                    row(scalar("fogLayerSun", CausticaConfig.Rt.Visuals.FOG_LAYER_SUN, 0f, 5f), CausticaConfig.Rt.Visuals.FOG_LAYER_SUN),
                    row(scalar("fogLayerAbsorb", CausticaConfig.Rt.Visuals.FOG_LAYER_ABSORB, .1f, 10f), CausticaConfig.Rt.Visuals.FOG_LAYER_ABSORB),
                    row(scalar("fogLayerVeil", CausticaConfig.Rt.Visuals.FOG_LAYER_VEIL, 0f, 1f), CausticaConfig.Rt.Visuals.FOG_LAYER_VEIL)));
            case "material" -> List.of(
                section("parallax",
                    row(parallax(), CausticaConfig.Rt.Material.PARALLAX),
                    row(scalar("parallaxStrength", CausticaConfig.Rt.Material.PARALLAX_STRENGTH, 0f, 2f), CausticaConfig.Rt.Material.PARALLAX_STRENGTH),
                    row(smoothSteps(), CausticaConfig.Rt.Material.SMOOTH_STEPS),
                    row(pbrMaxSize(), CausticaConfig.Rt.Material.PBR_MAX_SIZE),
                    row(toggle("caustica.enhanced.smoothHeightNormal", CausticaConfig.Rt.Material.SMOOTH_HEIGHT_NORMAL), CausticaConfig.Rt.Material.SMOOTH_HEIGHT_NORMAL),
                    row(toggle("caustica.enhanced.smoothShadow", CausticaConfig.Rt.Material.SMOOTH_SHADOW), CausticaConfig.Rt.Material.SMOOTH_SHADOW),
                    row(scalar("reliefShadow", CausticaConfig.Rt.Visuals.RELIEF_SHADOW, 0f, 1f), CausticaConfig.Rt.Visuals.RELIEF_SHADOW)),
                section("surface",
                    row(scalar("specular", CausticaConfig.Rt.Visuals.SPECULAR, 0f, 1f), CausticaConfig.Rt.Visuals.SPECULAR),
                    row(scalar("normalStrength", CausticaConfig.Rt.Visuals.NORMAL, 0f, 2f), CausticaConfig.Rt.Visuals.NORMAL),
                    row(scalar("zeroF0Fallback", CausticaConfig.Rt.Visuals.ZERO_F0_FALLBACK, 0f, .08f), CausticaConfig.Rt.Visuals.ZERO_F0_FALLBACK),
                    row(scalar("snowReflectance", CausticaConfig.Rt.Look.SNOW_REFLECTANCE, .1f, 1f), CausticaConfig.Rt.Look.SNOW_REFLECTANCE)),
                section("water",
                    row(waterWaves(), CausticaConfig.Rt.Composite.WATER_WAVES),
                    row(toggle("caustica.enhanced.waving", CausticaConfig.Rt.Composite.WAVING), CausticaConfig.Rt.Composite.WAVING),
                    row(scalar("wavingPlants", CausticaConfig.Rt.Visuals.WAVING_PLANTS, 0f, 3f), CausticaConfig.Rt.Visuals.WAVING_PLANTS),
                    row(scalar("wavingRange", CausticaConfig.Rt.Visuals.WAVING_RANGE, 16f, 128f), CausticaConfig.Rt.Visuals.WAVING_RANGE),
                    row(toggle("caustica.enhanced.fwaAnimations", CausticaConfig.Rt.Composite.FWA_ANIMATIONS), CausticaConfig.Rt.Composite.FWA_ANIMATIONS),
                    row(scalar("waterScatter", CausticaConfig.Rt.Visuals.WATER_SCATTER, 0f, 4f), CausticaConfig.Rt.Visuals.WATER_SCATTER),
                    row(scalar("waveStrength", CausticaConfig.Rt.Visuals.WAVE_STRENGTH, 0f, 4f), CausticaConfig.Rt.Visuals.WAVE_STRENGTH),
                    row(scalar("waveSize", CausticaConfig.Rt.Visuals.WAVE_SIZE, 0.25f, 4f), CausticaConfig.Rt.Visuals.WAVE_SIZE),
                    row(scalar("waveSpeed", CausticaConfig.Rt.Visuals.WAVE_SPEED, 0f, 4f), CausticaConfig.Rt.Visuals.WAVE_SPEED),
                    row(scalar("waterAbsorbR", CausticaConfig.Rt.Visuals.WATER_ABSORB_R, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_ABSORB_R),
                    row(scalar("waterAbsorbG", CausticaConfig.Rt.Visuals.WATER_ABSORB_G, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_ABSORB_G),
                    row(scalar("waterAbsorbB", CausticaConfig.Rt.Visuals.WATER_ABSORB_B, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_ABSORB_B),
                    row(scalar("waterFillR", CausticaConfig.Rt.Visuals.WATER_FILL_R, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_FILL_R),
                    row(scalar("waterFillG", CausticaConfig.Rt.Visuals.WATER_FILL_G, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_FILL_G),
                    row(scalar("waterFillB", CausticaConfig.Rt.Visuals.WATER_FILL_B, 0f, 1f), CausticaConfig.Rt.Visuals.WATER_FILL_B),
                    row(scalar("waterFillDensity", CausticaConfig.Rt.Visuals.WATER_FILL_DENSITY, 0f, 10f), CausticaConfig.Rt.Visuals.WATER_FILL_DENSITY)),
                section("weather",
                    row(scalar("wetness", CausticaConfig.Rt.Visuals.WETNESS, 0f, 1f), CausticaConfig.Rt.Visuals.WETNESS),
                    row(scalar("puddle", CausticaConfig.Rt.Visuals.PUDDLE, 0f, 1f), CausticaConfig.Rt.Visuals.PUDDLE),
                    row(scalar("ripples", CausticaConfig.Rt.Visuals.RIPPLES, 0f, 2f), CausticaConfig.Rt.Visuals.RIPPLES),
                    row(scalar("rippleSpeed", CausticaConfig.Rt.Visuals.RIPPLE_SPEED, .1f, 5f), CausticaConfig.Rt.Visuals.RIPPLE_SPEED),
                    row(scalar("rippleDensity", CausticaConfig.Rt.Visuals.RIPPLE_DENSITY, .25f, 4f), CausticaConfig.Rt.Visuals.RIPPLE_DENSITY),
                    row(scalar("rippleFrequency", CausticaConfig.Rt.Visuals.RIPPLE_FREQUENCY, .25f, 4f), CausticaConfig.Rt.Visuals.RIPPLE_FREQUENCY)));
            case "lighting" -> List.of(
                section("blockLights",
                    row(scalar("emission", CausticaConfig.Rt.Visuals.EMISSION, 0f, 16f), CausticaConfig.Rt.Visuals.EMISSION),
                    row(toggle("caustica.enhanced.oreEmission", CausticaConfig.Rt.Visuals.ORE_EMISSION), CausticaConfig.Rt.Visuals.ORE_EMISSION),
                    row(scalar("torchBrightness", CausticaConfig.Rt.Visuals.TORCH_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.TORCH_BRIGHTNESS),
                    row(scalar("torchKelvin", CausticaConfig.Rt.Look.TORCH_KELVIN, 1000f, 12000f), CausticaConfig.Rt.Look.TORCH_KELVIN),
                    row(toggle("caustica.enhanced.itrpLightColors", CausticaConfig.Rt.Visuals.ITRP_LIGHT_COLORS), CausticaConfig.Rt.Visuals.ITRP_LIGHT_COLORS),
                    row(scalar("torchG", CausticaConfig.Rt.Look.TORCH_G, 0f, 1f), CausticaConfig.Rt.Look.TORCH_G),
                    row(scalar("torchB", CausticaConfig.Rt.Look.TORCH_B, 0f, 1f), CausticaConfig.Rt.Look.TORCH_B),
                    row(toggle("caustica.enhanced.entityEmission", CausticaConfig.Rt.Visuals.VANILLA_ENTITY_EMISSION), CausticaConfig.Rt.Visuals.VANILLA_ENTITY_EMISSION),
                    row(lightSampling(), CausticaConfig.Rt.Lights.MODE)),
                section("heldLights",
                    row(toggle("caustica.enhanced.heldLights", CausticaConfig.Rt.Visuals.HELD_LIGHTS), CausticaConfig.Rt.Visuals.HELD_LIGHTS),
                    row(scalar("heldRadius", CausticaConfig.Rt.Visuals.HELD_RADIUS, 1f, 32f), CausticaConfig.Rt.Visuals.HELD_RADIUS),
                    row(scalar("heldBrightness", CausticaConfig.Rt.Visuals.HELD_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.HELD_BRIGHTNESS),
                    row(scalar("heldSoftness", CausticaConfig.Rt.Visuals.HELD_SOFTNESS, 0f, .5f), CausticaConfig.Rt.Visuals.HELD_SOFTNESS),
                    row(scalar("heldShadowFloor", CausticaConfig.Rt.Visuals.HELD_SHADOW_FLOOR, 0f, 1f), CausticaConfig.Rt.Visuals.HELD_SHADOW_FLOOR)),
                section("dimensions",
                    row(scalar("netherBrightness", CausticaConfig.Rt.Visuals.NETHER_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.NETHER_BRIGHTNESS),
                    row(scalar("portal", CausticaConfig.Rt.Visuals.PORTAL, 0f, 4f), CausticaConfig.Rt.Visuals.PORTAL),
                    row(scalar("endLight", CausticaConfig.Rt.Visuals.END_LIGHT, 0f, 8f), CausticaConfig.Rt.Visuals.END_LIGHT),
                    row(scalar("endStars", CausticaConfig.Rt.Visuals.END_STARS, 0f, 10f), CausticaConfig.Rt.Visuals.END_STARS),
                    row(toggle("caustica.enhanced.endNebula", CausticaConfig.Rt.Visuals.END_NEBULA), CausticaConfig.Rt.Visuals.END_NEBULA),
                    row(scalar("endSky", CausticaConfig.Rt.Visuals.END_SKY, 0f, 4f), CausticaConfig.Rt.Visuals.END_SKY),
                    row(scalar("endCycle", CausticaConfig.Rt.Visuals.END_CYCLE, 1f, 60f), CausticaConfig.Rt.Visuals.END_CYCLE),
                    row(scalar("endHalo", CausticaConfig.Rt.Visuals.END_HALO, 0f, 4f), CausticaConfig.Rt.Visuals.END_HALO)));
            case "world" -> List.of(
                section("scene",
                    row(entities(), CausticaConfig.Rt.Entities.ENABLED),
                    row(particles(), CausticaConfig.Rt.Entities.PARTICLES_ENABLED),
                    row(toggle("caustica.options.rt.glow", CausticaConfig.Rt.Entities.GLOW_ENABLED), CausticaConfig.Rt.Entities.GLOW_ENABLED),
                    row(toggle("caustica.enhanced.nameTags", CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED), CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED),
                    row(toggle("caustica.enhanced.nameTagShadows", CausticaConfig.Rt.Entities.NAME_TAG_SHADOWS), CausticaConfig.Rt.Entities.NAME_TAG_SHADOWS),
                    row(toggle("caustica.enhanced.blockOutline", CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED), CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED)),
                section("firstPerson",
                    row(scalar("handDepth", CausticaConfig.Rt.Visuals.HAND_DEPTH, .2f, 1f), CausticaConfig.Rt.Visuals.HAND_DEPTH),
                    row(toggle("caustica.enhanced.waterSelfReflection", CausticaConfig.Rt.Entities.WATER_SELF_REFLECTION), CausticaConfig.Rt.Entities.WATER_SELF_REFLECTION)),
                section("distantHorizons",
                    row(toggle("caustica.enhanced.dhLods", CausticaConfig.Rt.Visuals.DH_LODS), CausticaConfig.Rt.Visuals.DH_LODS),
                    row(toggle("caustica.enhanced.dhSecondary", CausticaConfig.Rt.Visuals.DH_SECONDARY), CausticaConfig.Rt.Visuals.DH_SECONDARY)));
            case "rt" -> List.of(
                section("tracing",
                    row(OptionInstance.createBoolean("caustica.enhanced.rtEnabled", tip("caustica.enhanced.rtEnabled.tooltip"), CausticaConfig.Rt.ENABLED.value(), CausticaConfig.Rt.ENABLED::set), CausticaConfig.Rt.ENABLED),
                    row(spp(), CausticaConfig.Rt.Composite.SPP),
                    row(maxBounces(), CausticaConfig.Rt.Composite.MAX_BOUNCES),
                    row(toggle("caustica.enhanced.radianceCache", CausticaConfig.Rt.Composite.RADIANCE_CACHE), CausticaConfig.Rt.Composite.RADIANCE_CACHE),
                    row(toggle("caustica.enhanced.mirrorPsr", CausticaConfig.Rt.Composite.MIRROR_PSR), CausticaConfig.Rt.Composite.MIRROR_PSR)),
                section("dlss",
                    row(dlssEnabled(), CausticaConfig.Rt.DlssRr.ENABLED),
                    row(dlssQuality(), CausticaConfig.Rt.DlssRr.QUALITY),
                    row(dlssFreeScale(), CausticaConfig.Rt.DlssRr.FREE_SCALE),
                    row(rrPreset(), CausticaConfig.Rt.DlssRr.PRESET),
                    row(srMode(), CausticaConfig.Rt.DlssRr.SR_ONLY),
                    row(srPreset(), CausticaConfig.Rt.DlssRr.SR_PRESET)),
                section("hdr",
                    row(hdrEnabled(), CausticaConfig.Rt.Hdr.ENABLED),
                    row(hdrPaperWhite(), CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS),
                    row(hdrPeak(), CausticaConfig.Rt.Hdr.PEAK_NITS)));
            case "fg" -> List.of(
                section("frameGeneration",
                    row(toggle("caustica.enhanced.streamline", CausticaConfig.Rt.Fg.STREAMLINE), CausticaConfig.Rt.Fg.STREAMLINE),
                    row(toggle("caustica.enhanced.fgUi", CausticaConfig.Rt.Fg.UI_RECOMPOSITION), CausticaConfig.Rt.Fg.UI_RECOMPOSITION),
                    row(toggle("caustica.enhanced.fgMenuDetection", CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION), CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION),
                    row(toggle("caustica.enhanced.reflex", CausticaConfig.Rt.Reflex.ENABLED), CausticaConfig.Rt.Reflex.ENABLED),
                    row(toggle("caustica.enhanced.reflexBoost", CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST), CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST),
                    // Must stay the last fg option: the regression harness drives it as categoryOptions("fg")[last].
                    row(frameGeneration())));
            default -> List.of(
                section("diagnostics",
                    row(debugView(), CausticaConfig.Rt.Composite.DEBUG_VIEW),
                    row(toggle("caustica.enhanced.frameStats", CausticaConfig.Rt.FrameStats.ENABLED), CausticaConfig.Rt.FrameStats.ENABLED),
                    row(toggle("caustica.enhanced.gpuTimings", CausticaConfig.Rt.Diagnostics.GPU_TIMINGS), CausticaConfig.Rt.Diagnostics.GPU_TIMINGS)));
        };
    }

    public static OptionInstance<?>[] categoryOptions(String category) {
        return sections(category).stream().flatMap(s -> s.rows().stream()).map(Row::option).toArray(OptionInstance<?>[]::new);
    }

    /** Every resettable setting on a page (the page's "restore defaults" button). */
    public static void resetPage(String category) {
        sections(category).forEach(s -> s.rows().forEach(Row::reset));
    }

    /** Tooltip only when the translation exists, so a missing entry never shows a raw key. */
    private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
        return !I18n.get(key).equals(key) ? OptionInstance.cachedConstantTooltip(Component.translatable(key)) : OptionInstance.noTooltip();
    }

    private static OptionInstance<Boolean> toggle(String key, BooleanSetting setting) {
        return OptionInstance.createBoolean(key, tip(key + ".tooltip"), setting.value(), setting::set);
    }

    private static OptionInstance<Integer> scalar(String name,FloatSetting setting,float min,float max) {
        int low=Math.round(min*100),high=Math.round(max*100);
        return new OptionInstance<>("caustica.enhanced.param."+name,
            tip("caustica.enhanced.param."+name+".tooltip"),
            (caption,value)->Options.genericValueLabel(caption,Component.literal(String.format(Locale.ROOT,max>=100f?"%.0f":"%.2f",value/100f))),
            new OptionInstance.IntRange(low,high),Math.clamp(Math.round(setting.value()*100),low,high),v->setting.set(v/100f));
    }

    /** Slider for small noise frequencies, shown with four decimals (1/10000 steps). */
    private static OptionInstance<Integer> milli(String name,FloatSetting setting,float min,float max) {
        int low=Math.round(min*10000),high=Math.round(max*10000);
        return new OptionInstance<>("caustica.enhanced.param."+name,
            tip("caustica.enhanced.param."+name+".tooltip"),
            (caption,value)->Options.genericValueLabel(caption,Component.literal(String.format(Locale.ROOT,"%.4f",value/10000f))),
            new OptionInstance.IntRange(low,high),Math.clamp(Math.round(setting.value()*10000),low,high),v->setting.set(v/10000f));
    }

    /** EV slider in tenths of a stop. */
    private static OptionInstance<Integer> ev(String key, FloatSetting setting, int lowTenths, int highTenths) {
        return new OptionInstance<>(key, tip(key + ".tooltip"),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%+.1f EV", tenths / 10f))),
            new OptionInstance.IntRange(lowTenths, highTenths),
            Math.clamp(Math.round(setting.value() * 10f), lowTenths, highTenths), tenths -> setting.set(tenths / 10f));
    }

    /** Angle slider for a radians-stored setting; {@code scale} 10 = tenths of a degree, 1 = whole degrees. */
    private static OptionInstance<Integer> degrees(String key, FloatSetting setting, int low, int high, int scale) {
        return new OptionInstance<>(key, tip(key + ".tooltip"),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(scale == 1 ? v + "°" : String.format(Locale.ROOT, "%.1f°", v / (double) scale))),
            new OptionInstance.IntRange(low, high),
            Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * scale), low, high),
            v -> setting.set(v / (float) scale));
    }

    /** One value of the "ITRP Select" preset (the user's tuned ITRP Alpha 0.8.x profile, mapped onto Caustica). */
    private record PresetValue(RuntimeSetting<?> setting, Object value) {}

    // ITRP Alpha option -> Caustica setting. Ratios are taken against the ITRP defaults; options with no
    // counterpart (volumetric cloud thickness, waving plants, voxel lanterns, SST parallax) are left out.
    private static final List<PresetValue> ITRP_SELECT = List.of(
        new PresetValue(CausticaConfig.Rt.Look.ITRP_TONE, 1),                 // TONEMAP_OPERATOR=GT
        new PresetValue(CausticaConfig.Rt.Look.VIGNETTE, .7f),                // VIGNETTE_FALLOFF=0.7
        new PresetValue(CausticaConfig.Rt.Look.BLOOM, 1f),                    // sun glow: ITRP draws its own sun halo, Caustica needs full bloom
        new PresetValue(CausticaConfig.Rt.Look.NIGHT_BRIGHTNESS, .36f),       // NIGHT_BRIGHTNESS 0.0005 -> 0.0003
        new PresetValue(CausticaConfig.Rt.Look.TORCH_G, 1f),                  // COLOR_TORCH_G=1.0
        new PresetValue(CausticaConfig.Rt.Look.TORCH_B, .62f),                // COLOR_TORCH_B=0.62
        new PresetValue(CausticaConfig.Rt.Look.TORCH_KELVIN, 4300f),          // same torch colour for held lights
        new PresetValue(CausticaConfig.Rt.Exposure.KEY, .1938f),              // AE_CURVE 0.65 -> 0.7 (key is the curve in ITRP look)
        new PresetValue(CausticaConfig.Rt.Exposure.ADAPT_UP, .171f),          // EXPOSURE_TIME 1.0 -> 0.7: adapts 1/0.7 faster
        new PresetValue(CausticaConfig.Rt.Exposure.ADAPT_DOWN, .5f),
        new PresetValue(CausticaConfig.Rt.Clouds.MODE, 3),                    // VOLUMETRIC_CLOUDS
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_ALTITUDE, 850f),         // CLOUD_CLEAR_ALTITUDE=850
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_THICKNESS, 1700f),       // CLOUD_CLEAR_THICKNESS=1700
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_COVERAGE, .34f),         // CLOUD_CLEAR_COVERY=0.34
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_RAIN_COVERAGE, .7f),     // CLOUD_RAIN_COVERY=0.7
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_DENSITY, .1f),           // CLOUD_CLEAR_DENSITY=0.1
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_SCALE, .4f),             // CLOUD_CLEAR_SCALE=0.4
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_SUNLIGHT, 5f),           // CLOUD_CLEAR_SUNLIGHTING=5
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_RAIN_SUNLIGHT, .5f),     // CLOUD_RAIN_SUNLIGHTING=0.5
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_RAIN_DENSITY, 1f),       // CLOUD_RAIN_DENSITY default 1.0 (10x clear)
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_RAIN_SCALE, .6f),        // CLOUD_RAIN_SCALE=0.6
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_BASE_NOISE, .0015f),     // CLOUD_BASE_NOISE_SCALE=0.0015
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_DETAIL_NOISE, .018f),    // CLOUD_DETAILED_NOISE_SCALE=0.018
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_DETAIL_STRENGTH, 2f),    // CLOUD_DETAILED_NOISE_STRENGTH=2
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_COVERAGE_OFFSET, .16f),  // CLOUD_COVERAGE_NOISE_OFFSET=0.16
        new PresetValue(CausticaConfig.Rt.Clouds.VOL_QUALITY, 2f),            // CLOUD_QUALITY=2
        // ITRP VFOG with NOISE_TYPE=1 (patchy) and DENSITY_BASE=0: no uniform haze, only fog patches in a band
        // from Y30 up to Y100, densest at the top - the sea of clouds. VFOG_FOG_DENSITY is its ambient light.
        new PresetValue(CausticaConfig.Rt.Visuals.FOG, 1f),
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_BASE, 0f),             // VFOG_DENSITY_BASE=0: no base fog, land haze only
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER, 1f),            // VFOG_DENSITY 0.01
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_BOTTOM, 30f),    // VFOG_HEIGHT_2=30
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_TOP, 72f),       // lowered on request (ITRP VFOG_HEIGHT=100)
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_FALLOFF, 16f),   // thinner band (ITRP VFOG_FALLOFF 40)
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_COVERAGE, .6f),  // VFOG_NOISE_COVERAGE 0.6
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_LIGHT, 3f),      // VFOG_FOG_DENSITY=3 (ambient light)
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_SUN, .9f),       // VFOG_SUNLIGHT_DENSITY=0.9
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_ABSORB, .1f),    // VFOG_SUNLIGHT_ABSORPTION=0.1
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_LAYER_VEIL, 0f),       // VFOG_DENSITY_BASE=0: no even fog in ITRP; far haze comes from the matched noise mean that blends the background
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_HEIGHT, 100f),          // VFOG_HEIGHT=100
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_RAIN, 2f),              // VFOG_RAIN_DENSITY_MUL 1.5 -> 3
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_HAZE, 1f),              // keep sky in-scatter: 0 left only absorption and turned far LODs black
        new PresetValue(CausticaConfig.Rt.Visuals.FOG_SUN, 1.5f),             // VFOG_SUNLIGHT_ABSORPTION 0.1
        new PresetValue(CausticaConfig.Rt.Visuals.NETHER_BRIGHTNESS, .3f),    // NETHER_BRIGHTNESS=0.3
        // HELDLIGHT_BRIGHTNESS=0.1 is not carried over: ITRP's held light is far stronger than Caustica's, and a
        // literal 0.1 left torches in hand nearly dark indoors. The held light stays at Caustica's 1.0.
        new PresetValue(CausticaConfig.Rt.Visuals.EMISSION, 8f),              // SPHERELIGHT_BRIGHTNESS=10
        // Sun size is NOT taken from ITRP (its disc is drawn differently); keep Caustica's default.
        new PresetValue(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS, CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.defaultValue()),
        new PresetValue(CausticaConfig.Rt.Visuals.END_NEBULA, false),           // the End without the nebula
        new PresetValue(CausticaConfig.Rt.Material.PARALLAX, 2));             // PARALLAX_MODE=2

    /** Water of the ITRP-PTGI preset: iterationRP-0.8.31-ptgi defaults (the setting defaults are ITRP Alpha's). */
    private static final List<PresetValue> PTGI_WATER = List.of(
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_ABSORB_R, .25f),       // WATER_ATTENUATION 0.25/0.04/0.01
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_ABSORB_G, .04f),
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_ABSORB_B, .01f),
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_FILL_R, .4f),          // WATER_SCATTERING 0.4/0.41/0.5
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_FILL_G, .41f),
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_FILL_B, .5f),
        new PresetValue(CausticaConfig.Rt.Visuals.WATER_FILL_DENSITY, 0f));    // WATER_SCATTERING_DENSITY=0: clear water, light comes from the sun shafts

    private static boolean same(Object a, Object b) {
        if (a instanceof Float x && b instanceof Float y) return Math.abs(x - y) <= 1e-4f * Math.max(1f, Math.abs(y));
        return a != null && a.equals(b);
    }

    private static boolean presetScope(RuntimeSetting<?> s) {
        return s.tomlPath().startsWith("look.") || s.tomlPath().startsWith("clouds.") || s.tomlPath().startsWith("exposure.");
    }

    private static boolean matchesItrpSelect() {
        if (!CausticaConfig.Rt.Look.ITRP.value()) return false;
        java.util.Set<RuntimeSetting<?>> touched = new java.util.HashSet<>();
        for (PresetValue p : ITRP_SELECT) { touched.add(p.setting()); if (!same(p.setting().get(), p.value())) return false; }
        return CausticaConfig.settings().stream().filter(s -> presetScope(s) && !touched.contains(s)
                && !s.tomlPath().equals("look.itrp") && !s.tomlPath().equals("clouds.mode"))
            .allMatch(s -> same(s.get(), s.defaultValue()));
    }

    public static String visualPresetName() {
        if (matchesItrpSelect()) return "itrp_select";
        boolean itrp=CausticaConfig.Rt.Look.ITRP.value();
        int cloud=CausticaConfig.Rt.Clouds.MODE.value();
        boolean defaults=CausticaConfig.settings().stream().filter(s->(s.tomlPath().startsWith("look.")&&!s.tomlPath().equals("look.itrp")) || (s.tomlPath().startsWith("clouds.")&&!s.tomlPath().equals("clouds.mode"))).allMatch(s->s.get().equals(s.defaultValue()));
        if(!defaults || !CausticaConfig.Rt.Exposure.MODE.get().equals("auto") || CausticaConfig.Rt.Exposure.MANUAL_EV.value()!=0f) return "custom";
        return itrp && cloud==2?"itrp":!itrp && cloud==0?"original":"custom";
    }

    public static void applyVisualPreset(boolean itrp) { applyVisualPreset(itrp?"itrp":"original"); }

    /** "itrp", "itrp_select" or "original". */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyVisualPreset(String preset) {
        boolean itrp=!preset.equals("original");
        // Settings outside look/clouds/exposure that ITRP Select changes go back to default with every preset,
        // so switching away from it really leaves its look behind.
        for (PresetValue p : ITRP_SELECT) p.setting().set(null);
        for (PresetValue p : PTGI_WATER) p.setting().set(null);
        // Look, cloud and exposure tuning back to defaults; a visual preset never changes the user's
        // bounce/SPP/DLSS/HDR output or FG choices.
        for (RuntimeSetting<?> s : CausticaConfig.settings())
            if (s.tomlPath().startsWith("look.") || s.tomlPath().startsWith("clouds.") || s.tomlPath().startsWith("exposure.")) s.set(null);
        CausticaConfig.Rt.Look.ITRP.set(itrp);
        CausticaConfig.Rt.Clouds.MODE.set(itrp?2:0);
        if (preset.equals("itrp_select")) for (PresetValue p : ITRP_SELECT) ((RuntimeSetting) p.setting()).set(p.value());
        if (preset.equals("itrp")) for (PresetValue p : PTGI_WATER) ((RuntimeSetting) p.setting()).set(p.value());
        CausticaConfig.save();
    }

    private static OptionInstance<Boolean> dlssEnabled() {
        return OptionInstance.createBoolean("caustica.enhanced.rr",OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.rr.tooltip")),CausticaConfig.Rt.DlssRr.ENABLED.value(),CausticaConfig.Rt.DlssRr.ENABLED::set);
    }

    private static OptionInstance<Integer> pbrMaxSize() {
        return new OptionInstance<>("caustica.enhanced.param.pbrMaxSize", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.enhanced.param.pbrMaxSize.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value == 0
                ? Component.translatable("caustica.enhanced.param.pbrMaxSize.original") : Component.literal(value + "x")),
            new OptionInstance.Enum<>(List.of(0, 128, 64, 32), Codec.INT),
            CausticaConfig.Rt.Material.PBR_MAX_SIZE.value(), CausticaConfig.Rt.Material.PBR_MAX_SIZE::set);
    }

    private static OptionInstance<Integer> smoothSteps() {
        return new OptionInstance<>("caustica.enhanced.param.smoothSteps",OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.param.smoothSteps.tooltip")),
            (caption,value)->Options.genericValueLabel(caption,Component.literal(Integer.toString(value))),new OptionInstance.IntRange(2,8),CausticaConfig.Rt.Material.SMOOTH_STEPS.value(),CausticaConfig.Rt.Material.SMOOTH_STEPS::set);
    }

    private static OptionInstance<Integer> clouds() {
        return new OptionInstance<>("caustica.options.rt.clouds", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.options.rt.clouds.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.clouds." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3), Codec.INT),
            Math.clamp(CausticaConfig.Rt.Clouds.MODE.value(), 0, 3), CausticaConfig.Rt.Clouds.MODE::set);
    }
    private static OptionInstance<Integer> frameGeneration() {
        var fg = dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE;
        fg.probeAvailabilityOnce();
        // The imported RTX40 route has real 3x/4x evidence. A reported ceiling alone does not
        // validate 5x/6x; preserve backend experiments but do not advertise them as usable here.
        int requested = CausticaConfig.Rt.Fg.ENABLED.value() ? CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value() : 0;
        var choices = new java.util.ArrayList<Integer>(); choices.add(0);
        // Unsupported choices remain visibly marked unavailable; never change the running multiplier.
        for(int generated=1;generated<=5;generated++)choices.add(generated);
        if (!choices.contains(requested)) choices.add(requested);
        return new OptionInstance<>("caustica.options.rt.fg", value -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
            (dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()?"caustica.options.rt.fg.tooltip.streamline":"caustica.options.rt.fg.tooltip"), fg.capabilitiesProbed() ? Integer.toString(fg.multiFrameCountMax()) : "?",
            dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()
                && dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.framesActuallyPresented()>1
                ? Integer.toString(dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.framesActuallyPresented()) : "-",
            Long.toString(dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()?dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.successfulOptionsSubmissions():fg.successfulEvaluations()), fg.failureReason()).append("\n").append(Component.literal(dev.comfyfluffy.caustica.streamline.ManagedMfg.status()))),
            (caption, value) -> value == 0 ? Component.translatable("caustica.options.rt.fg.off") :
                value>1 && dev.comfyfluffy.caustica.streamline.ManagedMfg.restartRequired()
                    ? Component.translatable("caustica.options.rt.fg.restartMfg", value+1) :
                dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized()
                    && dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.isActive()
                    && dev.comfyfluffy.caustica.rt.pipeline.RtStreamlineFg.INSTANCE.framesActuallyPresented()==value+1
                    ? Component.translatable("caustica.options.rt.fg.active",value+1) :
                Component.translatable(!fg.capabilitiesProbed() && !fg.hasFailed()
                    ? "caustica.options.rt.fg.pending" : dev.comfyfluffy.caustica.streamline.FrameGenerationRequestPolicy.canRequest(value,
                    fg.isAvailable(),fg.hasFailed(),fg.multiFrameCountMax(),dev.comfyfluffy.caustica.streamline.ManagedMfg.active())
                    ? "caustica.options.rt.fg.request" : "caustica.options.rt.fg.unavailable", value + 1),
            new OptionInstance.Enum<>(choices, Codec.INT), requested, value -> {
                if (value == 0) { dev.comfyfluffy.caustica.streamline.ManagedMfg.selectGeneratedCount(0); CausticaConfig.Rt.Fg.ENABLED.set(false); if(dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized())dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.synchronizeRequestedState(); return; }
                {
                    boolean canRequest=dev.comfyfluffy.caustica.streamline.FrameGenerationRequestPolicy.canRequest(value,
                        fg.isAvailable(),fg.hasFailed(),fg.multiFrameCountMax(),dev.comfyfluffy.caustica.streamline.ManagedMfg.active());
                    // Never install hooks or persist unverified multipliers as a side effect of selecting them.
                    if (value > 3 && !canRequest) return;
                    boolean needsExternal=value>1 && (dev.comfyfluffy.caustica.streamline.ManagedMfg.active() || value>fg.multiFrameCountMax());
                    boolean mfgReady=dev.comfyfluffy.caustica.streamline.ManagedMfg.selectGeneratedCount(needsExternal?value:0);
                    if (needsExternal && !mfgReady) {
                        if (dev.comfyfluffy.caustica.streamline.ManagedMfg.restartRequired()) {
                            CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(value); CausticaConfig.Rt.Fg.ENABLED.set(true);
                        }
                        return;
                    }
                }
                if (dev.comfyfluffy.caustica.streamline.FrameGenerationRequestPolicy.canRequest(value,
                        fg.isAvailable(),fg.hasFailed(),fg.multiFrameCountMax(),dev.comfyfluffy.caustica.streamline.ManagedMfg.active())) {
                    CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(value); CausticaConfig.Rt.Fg.ENABLED.set(true);
                    if(dev.comfyfluffy.caustica.streamline.StreamlineRuntime.initialized())dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.synchronizeRequestedState();
                }
            });
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> itrpTone() {
        return new OptionInstance<>("caustica.enhanced.itrpTone", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.enhanced.itrpTone.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable("caustica.enhanced.itrpTone." + value)),
            new OptionInstance.Enum<>(List.of(0, 1), Codec.INT),
            Math.clamp(CausticaConfig.Rt.Look.ITRP_TONE.value(), 0, 1), CausticaConfig.Rt.Look.ITRP_TONE::set);
    }

    private static OptionInstance<Integer> sunShape() {
        return new OptionInstance<>("caustica.enhanced.sunShape", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.enhanced.sunShape.tooltip")),
            (caption, value) -> Component.translatable("caustica.enhanced.sunShape." + value),
            new OptionInstance.Enum<>(List.of(0, 1), Codec.INT),
            Math.clamp(CausticaConfig.Rt.Look.SUN_SHAPE.value(), 0, 1), CausticaConfig.Rt.Look.SUN_SHAPE::set);
    }

    private static OptionInstance<Integer> lightSampling() {
        return new OptionInstance<>("caustica.enhanced.lightSampling", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.enhanced.lightSampling.tooltip")),
            (caption, value) -> Component.translatable("caustica.enhanced.lightSampling." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2), Codec.INT),
            Math.clamp(CausticaConfig.Rt.Lights.MODE.value(), 0, 2), CausticaConfig.Rt.Lights.MODE::set);
    }

    private static OptionInstance<String> parallax() {
        IntSetting setting = CausticaConfig.Rt.Material.PARALLAX;
        return new OptionInstance<>(
            "caustica.options.rt.parallax",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.parallax.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.parallax." + value),
            new OptionInstance.Enum<>(List.of("off", "parallax", "smooth"), Codec.STRING),
            switch (Math.clamp(setting.value(), 0, 2)) {
                case 1 -> "parallax";
                case 2 -> "smooth";
                default -> "off";
            },
            value -> setting.set(switch (value) {
                case "parallax" -> 1;
                case "smooth" -> 2;
                default -> 0;
            }));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Boolean> srMode() {
        return OptionInstance.createBoolean("caustica.enhanced.srMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.srMode.tooltip")),
            CausticaConfig.Rt.DlssRr.SR_ONLY.value(), CausticaConfig.Rt.DlssRr.SR_ONLY::set);
    }
    private static OptionInstance<Integer> srPreset() {
        var setting=CausticaConfig.Rt.DlssRr.SR_PRESET;
        var choices=List.of(0,10,11,12,13);
        return new OptionInstance<>("caustica.enhanced.srPreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.srPreset.tooltip")),
            (caption,value)->Options.genericValueLabel(caption,Component.literal(value==0?"Auto":String.valueOf((char)('A'+value-1)))),
            new OptionInstance.Enum<>(choices,Codec.INT), choices.contains(setting.value())?setting.value():0,setting::set);
    }

    private static OptionInstance<Integer> rrPreset() {
        var setting = CausticaConfig.Rt.DlssRr.PRESET;
        var choices = List.of(0, 4, 5, 6); // DLSSD presets: SR K/L/M are not RR presets.
        return new OptionInstance<>("caustica.enhanced.rrPreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.rrPreset.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                Component.literal(value == 0 ? "Auto" : String.valueOf((char)('A' + value - 1)))),
            new OptionInstance.Enum<>(choices, Codec.INT),
            choices.contains(setting.value()) ? setting.value() : 0, setting::set);
    }

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    /** Slider 19 = off (quality mode applies); 20..100 = free render resolution in percent. */
    private static OptionInstance<Integer> dlssFreeScale() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.FREE_SCALE;
        int initial = setting.value() <= 0 ? 19 : Math.max(20, Math.min(100, setting.value()));
        return new OptionInstance<>(
            "caustica.options.rt.dlssFreeScale",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssFreeScale.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value <= 19
                    ? Component.translatable("caustica.options.rt.dlssFreeScale.off")
                    : Component.literal(value + "%")),
            new OptionInstance.IntRange(19, 100),
            initial,
            value -> setting.set(value <= 19 ? 0 : value));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Boolean> itrpLook() {
        return bool("caustica.options.rt.itrpLook", CausticaConfig.Rt.Look.ITRP);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20), Codec.INT),
            Math.clamp(setting.value(), 0, 20),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            tip(captionKey + ".tooltip"),
            setting.value(),
            setting::set);
    }
}
