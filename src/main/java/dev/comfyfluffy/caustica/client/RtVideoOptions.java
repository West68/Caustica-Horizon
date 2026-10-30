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

    public static final List<String> PAGES = dev.comfyfluffy.caustica.CausticaBuild.RELEASE
            ? List.of("color", "clouds", "material", "lighting", "rt", "fg")
            : List.of("color", "clouds", "material", "lighting", "rt", "fg", "advanced");

    public static List<Section> sections(String category) {
        return switch (category) {
            case "color" -> List.of(
                section("tone",
                    row(itrpLook(), CausticaConfig.Rt.Look.ITRP),
                    row(scalar("bloom", CausticaConfig.Rt.Look.BLOOM, 0f, 3f), CausticaConfig.Rt.Look.BLOOM),
                    row(toggle("caustica.enhanced.itrpStars", CausticaConfig.Rt.Look.STARS), CausticaConfig.Rt.Look.STARS),
                    row(scalar("detailSharpen", CausticaConfig.Rt.Look.DETAIL_SHARPEN, 0f, 3f), CausticaConfig.Rt.Look.DETAIL_SHARPEN),
                    row(scalar("sunRims", CausticaConfig.Rt.Look.SUN_RIMS, 0f, 3f), CausticaConfig.Rt.Look.SUN_RIMS),
                    row(scalar("brightness", CausticaConfig.Rt.Look.BRIGHTNESS, .25f, 2f), CausticaConfig.Rt.Look.BRIGHTNESS),
                    row(scalar("contrast", CausticaConfig.Rt.Look.CONTRAST, .5f, 1.5f), CausticaConfig.Rt.Look.CONTRAST),
                    row(scalar("saturation", CausticaConfig.Rt.Look.SATURATION, 0f, 2f), CausticaConfig.Rt.Look.SATURATION),
                    row(scalar("temperature", CausticaConfig.Rt.Look.TEMPERATURE, -1f, 1f), CausticaConfig.Rt.Look.TEMPERATURE),
                    row(scalar("gamma", CausticaConfig.Rt.Look.GAMMA, .5f, 2f), CausticaConfig.Rt.Look.GAMMA),
                    row(scalar("shadowLift", CausticaConfig.Rt.Look.SHADOW_LIFT, 0f, .25f), CausticaConfig.Rt.Look.SHADOW_LIFT),
                    row(scalar("highlightSoftness", CausticaConfig.Rt.Look.HIGHLIGHT_SOFTNESS, 0f, 2f), CausticaConfig.Rt.Look.HIGHLIGHT_SOFTNESS)),
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
                    row(degrees("caustica.enhanced.sunTilt", CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT, -60, 60, 1), CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT)),
                section("clouds",
                    row(clouds(), CausticaConfig.Rt.Clouds.MODE),
                    row(scalar("cloudCoverage", CausticaConfig.Rt.Clouds.COVERAGE, -0.3f, 0.3f), CausticaConfig.Rt.Clouds.COVERAGE),
                    row(scalar("cloudDensity", CausticaConfig.Rt.Clouds.DENSITY, 0.25f, 2f), CausticaConfig.Rt.Clouds.DENSITY),
                    row(scalar("cloudSize", CausticaConfig.Rt.Clouds.SIZE, 0.5f, 2f), CausticaConfig.Rt.Clouds.SIZE),
                    row(scalar("cloudHeight", CausticaConfig.Rt.Clouds.HEIGHT, 500f, 1500f), CausticaConfig.Rt.Clouds.HEIGHT),
                    row(scalar("cloudBrightness", CausticaConfig.Rt.Clouds.BRIGHTNESS, 0.25f, 2f), CausticaConfig.Rt.Clouds.BRIGHTNESS),
                    row(scalar("cloudBottom", CausticaConfig.Rt.Clouds.BOTTOM, 0.25f, 2f), CausticaConfig.Rt.Clouds.BOTTOM),
                    row(scalar("cloudSunlight", CausticaConfig.Rt.Clouds.SUNLIGHT, 0f, 2f), CausticaConfig.Rt.Clouds.SUNLIGHT),
                    row(scalar("cloudSpeed", CausticaConfig.Rt.Clouds.SPEED, 0f, 3f), CausticaConfig.Rt.Clouds.SPEED),
                    row(scalar("cloudEdge", CausticaConfig.Rt.Clouds.EDGE, 0f, 2f), CausticaConfig.Rt.Clouds.EDGE),
                    row(scalar("cloudCirrus", CausticaConfig.Rt.Clouds.CIRRUS, 0f, 2f), CausticaConfig.Rt.Clouds.CIRRUS),
                    row(scalar("cloudRain", CausticaConfig.Rt.Clouds.RAIN, 0f, 1f), CausticaConfig.Rt.Clouds.RAIN)));
            case "material" -> List.of(
                section("parallax",
                    row(parallax(), CausticaConfig.Rt.Material.PARALLAX),
                    row(scalar("parallaxStrength", CausticaConfig.Rt.Material.PARALLAX_STRENGTH, 0f, 2f), CausticaConfig.Rt.Material.PARALLAX_STRENGTH),
                    row(smoothSteps(), CausticaConfig.Rt.Material.SMOOTH_STEPS),
                    row(toggle("caustica.enhanced.smoothHeightNormal", CausticaConfig.Rt.Material.SMOOTH_HEIGHT_NORMAL), CausticaConfig.Rt.Material.SMOOTH_HEIGHT_NORMAL),
                    row(toggle("caustica.enhanced.smoothShadow", CausticaConfig.Rt.Material.SMOOTH_SHADOW), CausticaConfig.Rt.Material.SMOOTH_SHADOW),
                    row(scalar("reliefShadow", CausticaConfig.Rt.Visuals.RELIEF_SHADOW, 0f, 1f), CausticaConfig.Rt.Visuals.RELIEF_SHADOW)),
                section("surface",
                    row(scalar("specular", CausticaConfig.Rt.Visuals.SPECULAR, 0f, 1f), CausticaConfig.Rt.Visuals.SPECULAR),
                    row(scalar("normalStrength", CausticaConfig.Rt.Visuals.NORMAL, 0f, 2f), CausticaConfig.Rt.Visuals.NORMAL),
                    row(scalar("zeroF0Fallback", CausticaConfig.Rt.Visuals.ZERO_F0_FALLBACK, 0f, .08f), CausticaConfig.Rt.Visuals.ZERO_F0_FALLBACK)),
                section("weather",
                    row(waterWaves(), CausticaConfig.Rt.Composite.WATER_WAVES),
                    row(scalar("waterScatter", CausticaConfig.Rt.Visuals.WATER_SCATTER, 0f, 4f), CausticaConfig.Rt.Visuals.WATER_SCATTER),
                    row(scalar("fogDensity", CausticaConfig.Rt.Visuals.FOG, 0f, 4f), CausticaConfig.Rt.Visuals.FOG),
                    row(scalar("wetness", CausticaConfig.Rt.Visuals.WETNESS, 0f, 1f), CausticaConfig.Rt.Visuals.WETNESS),
                    row(scalar("puddle", CausticaConfig.Rt.Visuals.PUDDLE, 0f, 1f), CausticaConfig.Rt.Visuals.PUDDLE),
                    row(scalar("ripples", CausticaConfig.Rt.Visuals.RIPPLES, 0f, 2f), CausticaConfig.Rt.Visuals.RIPPLES)));
            case "lighting" -> List.of(
                section("blockLights",
                    row(scalar("emission", CausticaConfig.Rt.Visuals.EMISSION, 0f, 16f), CausticaConfig.Rt.Visuals.EMISSION),
                    row(scalar("torchBrightness", CausticaConfig.Rt.Visuals.TORCH_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.TORCH_BRIGHTNESS),
                    row(scalar("torchKelvin", CausticaConfig.Rt.Look.TORCH_KELVIN, 1000f, 12000f), CausticaConfig.Rt.Look.TORCH_KELVIN),
                    row(toggle("caustica.enhanced.itrpLightColors", CausticaConfig.Rt.Visuals.ITRP_LIGHT_COLORS), CausticaConfig.Rt.Visuals.ITRP_LIGHT_COLORS),
                    row(toggle("caustica.enhanced.entityEmission", CausticaConfig.Rt.Visuals.VANILLA_ENTITY_EMISSION), CausticaConfig.Rt.Visuals.VANILLA_ENTITY_EMISSION)),
                section("heldLights",
                    row(toggle("caustica.enhanced.heldLights", CausticaConfig.Rt.Visuals.HELD_LIGHTS), CausticaConfig.Rt.Visuals.HELD_LIGHTS),
                    row(scalar("heldRadius", CausticaConfig.Rt.Visuals.HELD_RADIUS, 1f, 32f), CausticaConfig.Rt.Visuals.HELD_RADIUS),
                    row(scalar("heldBrightness", CausticaConfig.Rt.Visuals.HELD_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.HELD_BRIGHTNESS),
                    row(scalar("heldSoftness", CausticaConfig.Rt.Visuals.HELD_SOFTNESS, 0f, .5f), CausticaConfig.Rt.Visuals.HELD_SOFTNESS),
                    row(scalar("heldShadowFloor", CausticaConfig.Rt.Visuals.HELD_SHADOW_FLOOR, 0f, 1f), CausticaConfig.Rt.Visuals.HELD_SHADOW_FLOOR),
                    row(scalar("handDepth", CausticaConfig.Rt.Visuals.HAND_DEPTH, .2f, 1f), CausticaConfig.Rt.Visuals.HAND_DEPTH)),
                section("dimensions",
                    row(scalar("netherBrightness", CausticaConfig.Rt.Visuals.NETHER_BRIGHTNESS, 0f, 8f), CausticaConfig.Rt.Visuals.NETHER_BRIGHTNESS),
                    row(scalar("portal", CausticaConfig.Rt.Visuals.PORTAL, 0f, 4f), CausticaConfig.Rt.Visuals.PORTAL),
                    row(scalar("endLight", CausticaConfig.Rt.Visuals.END_LIGHT, 0f, 8f), CausticaConfig.Rt.Visuals.END_LIGHT),
                    row(scalar("endStars", CausticaConfig.Rt.Visuals.END_STARS, 0f, 10f), CausticaConfig.Rt.Visuals.END_STARS),
                    row(scalar("endSky", CausticaConfig.Rt.Visuals.END_SKY, 0f, 4f), CausticaConfig.Rt.Visuals.END_SKY),
                    row(scalar("endCycle", CausticaConfig.Rt.Visuals.END_CYCLE, 1f, 60f), CausticaConfig.Rt.Visuals.END_CYCLE),
                    row(scalar("endHalo", CausticaConfig.Rt.Visuals.END_HALO, 0f, 4f), CausticaConfig.Rt.Visuals.END_HALO)));
            case "rt" -> List.of(
                section("tracing",
                    row(OptionInstance.createBoolean("caustica.enhanced.rtEnabled", tip("caustica.enhanced.rtEnabled.tooltip"), CausticaConfig.Rt.ENABLED.value(), CausticaConfig.Rt.ENABLED::set), CausticaConfig.Rt.ENABLED),
                    row(spp(), CausticaConfig.Rt.Composite.SPP),
                    row(maxBounces(), CausticaConfig.Rt.Composite.MAX_BOUNCES),
                    row(toggle("caustica.enhanced.radianceCache", CausticaConfig.Rt.Composite.RADIANCE_CACHE), CausticaConfig.Rt.Composite.RADIANCE_CACHE),
                    row(toggle("caustica.enhanced.mirrorPsr", CausticaConfig.Rt.Composite.MIRROR_PSR), CausticaConfig.Rt.Composite.MIRROR_PSR)),
                section("scene",
                    row(entities(), CausticaConfig.Rt.Entities.ENABLED),
                    row(particles(), CausticaConfig.Rt.Entities.PARTICLES_ENABLED),
                    row(toggle("caustica.options.rt.glow", CausticaConfig.Rt.Entities.GLOW_ENABLED), CausticaConfig.Rt.Entities.GLOW_ENABLED),
                    row(toggle("caustica.enhanced.nameTags", CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED), CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED),
                    row(toggle("caustica.enhanced.blockOutline", CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED), CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED),
                    row(toggle("caustica.enhanced.dhLods", CausticaConfig.Rt.Visuals.DH_LODS), CausticaConfig.Rt.Visuals.DH_LODS),
                    row(toggle("caustica.enhanced.dhSecondary", CausticaConfig.Rt.Visuals.DH_SECONDARY), CausticaConfig.Rt.Visuals.DH_SECONDARY)),
                section("dlss",
                    row(dlssEnabled(), CausticaConfig.Rt.DlssRr.ENABLED),
                    row(dlssQuality(), CausticaConfig.Rt.DlssRr.QUALITY),
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

    public static String visualPresetName() {
        boolean itrp=CausticaConfig.Rt.Look.ITRP.value();
        int cloud=CausticaConfig.Rt.Clouds.MODE.value();
        boolean defaults=CausticaConfig.settings().stream().filter(s->(s.tomlPath().startsWith("look.")&&!s.tomlPath().equals("look.itrp")) || (s.tomlPath().startsWith("clouds.")&&!s.tomlPath().equals("clouds.mode"))).allMatch(s->s.get().equals(s.defaultValue()));
        if(!defaults || !CausticaConfig.Rt.Exposure.MODE.get().equals("auto") || CausticaConfig.Rt.Exposure.MANUAL_EV.value()!=0f) return "custom";
        return itrp && cloud==2?"itrp":!itrp && cloud==0?"original":"custom";
    }

    public static void applyVisualPreset(boolean itrp) { applyVisualPreset(itrp?"itrp":"original"); }

    /** "itrp" or "original". */
    public static void applyVisualPreset(String preset) {
        boolean itrp=!preset.equals("original");
        // Look, cloud and exposure tuning back to defaults; a visual preset never changes the user's
        // bounce/SPP/DLSS/HDR output or FG choices.
        for (RuntimeSetting<?> s : CausticaConfig.settings())
            if (s.tomlPath().startsWith("look.") || s.tomlPath().startsWith("clouds.") || s.tomlPath().startsWith("exposure.")) s.set(null);
        CausticaConfig.Rt.Look.ITRP.set(itrp);
        CausticaConfig.Rt.Clouds.MODE.set(itrp?2:0);
        CausticaConfig.save();
    }

    private static OptionInstance<Boolean> dlssEnabled() {
        return OptionInstance.createBoolean("caustica.enhanced.rr",OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.rr.tooltip")),CausticaConfig.Rt.DlssRr.ENABLED.value(),CausticaConfig.Rt.DlssRr.ENABLED::set);
    }

    private static OptionInstance<Integer> smoothSteps() {
        return new OptionInstance<>("caustica.enhanced.param.smoothSteps",OptionInstance.cachedConstantTooltip(Component.translatable("caustica.enhanced.param.smoothSteps.tooltip")),
            (caption,value)->Options.genericValueLabel(caption,Component.literal(Integer.toString(value))),new OptionInstance.IntRange(2,8),CausticaConfig.Rt.Material.SMOOTH_STEPS.value(),CausticaConfig.Rt.Material.SMOOTH_STEPS::set);
    }

    private static OptionInstance<Integer> clouds() {
        return new OptionInstance<>("caustica.options.rt.clouds", OptionInstance.cachedConstantTooltip(
            Component.translatable("caustica.options.rt.clouds.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.clouds." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2), Codec.INT),
            Math.clamp(CausticaConfig.Rt.Clouds.MODE.value(), 0, 2), CausticaConfig.Rt.Clouds.MODE::set);
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
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), Codec.INT),
            Math.clamp(setting.value(), 0, 12),
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
