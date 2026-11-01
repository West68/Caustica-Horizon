package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dcaustica.*} system property, then the {@code config/caustica.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED, Rt.Fg.ENABLED,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED,
            Rt.Hdr.ENABLED, Assets.ITRP_PACK, Rt.Look.ITRP, Rt.Clouds.MODE, Rt.Material.PARALLAX, Rt.Visuals.HELD_LIGHTS, Rt.Fg.MULTI_FRAME_COUNT, Ngx.PATH,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("visuals", " Held-light, wetness and bounded single-scattering controls. New values do not replace saved RT/DLSS choices.");
        FILE.setComment("enabled",
                " Caustica RT renderer configuration.\n"
                        + " A matching -Dcaustica.* system property overrides the value below.");
        FILE.setComment("terrain",
                " Render-thread terrain work is bounded by dispatch/result counts per streaming pass.\n"
                        + " Buffer fill and BLAS/OMM preparation run on workers. max-inflight-sections bounds\n"
                        + " the complete snapshot -> worker -> GPU build -> publication lifecycle.");
        FILE.setComment("frame-generation",
                " DLSS Frame Generation. Default off; gated additionally by hardware/driver availability.\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...), clamped\n"
                        + " at runtime to the driver's reported DLSSG.MultiFrameCountMax.");
        FILE.setComment("reflex",
                " NVIDIA Reflex (VK_NV_low_latency2). Default off; gated additionally by device support.\n"
                        + " minimum-interval-us: 0 = no framerate cap (Reflex just paces submission).");
        FILE.setComment("lights",
                " RIS direct lighting from block emitters (torches, glowstone, lava, ...): per diffuse\n"
                        + " vertex, resample ris-candidates power-weighted proposals and spend one shadow ray on\n"
                        + " the survivor. ris-candidates = 0 disables it entirely (emitters just gather on direct\n"
                        + " hit, same as with no NEE). Power-weighted sampling and the local per-section light\n"
                        + " grid are always active whenever RIS is on. min-fill-ratio drops emissive footprints\n"
                        + " below that fraction of their bounding rectangle (speckle/sparse crossed planes), so\n"
                        + " only reasonably compact glows become lights. stats/dump/dump-radius are debug logging.");
        FILE.setComment("hdr",
                " HDR display output (ST.2084/PQ). When enabled the swapchain is created in PQ automatically\n"
                        + " (falls back to SDR if the surface doesn't advertise it). paper-white-nits / peak-nits\n"
                        + " drive the scene-HDR -> display mapping.");
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica.toml");
        }
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        migrateLegacyFrameGenerationBackend(config);
        return config;
    }

    /**
   * Older builds defaulted to the legacy NGX frame generator. Measured on this project's own hardware
   * (RTX 4060 Laptop, 1080p, 4 bounces, same scene): FG off 72.2 fps, NGX 2x 49.3 fps - it cost real
   * frame rate and could not expose multi-frame generation, while the packaged Streamline 2.14.16
   * backend can. A config that still holds the old default is upgraded once; the marker makes the
   * choice permanent so a user who later selects NGX keeps it.
   */
  private static void migrateLegacyFrameGenerationBackend(CommentedFileConfig config) {
  final String marker="frame-generation.backend-version";
  if (config.contains(marker)) return;
  try {
  Object current=config.contains("frame-generation.streamline")?config.get("frame-generation.streamline"):null;
  if (current instanceof Boolean enabled && !enabled) {
  config.set("frame-generation.streamline",true);
  config.save();
  LOGGER.info("Caustica config migrated to the Streamline frame-generation backend (was legacy NGX)");
  }
  } catch (Exception e) {
  LOGGER.warn("Could not migrate frame-generation backend: {}",e.toString());
  }
  config.set(marker,2);
  }

  private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize.applyAsInt(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.value = defaultValue;
            } else {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(value);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return sanitize.apply(prop);
            }
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW = intValue("caustica.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            /** World radiance cache (SHaRC-style) terminating eligible indirect paths with cached radiance. */
            public static final BooleanSetting RADIANCE_CACHE =
                    bool("caustica.rt.radianceCache", "composite.radiance-cache", true);
            /** Primary surface replacement for smooth metal mirrors: DLSS-RR sees the reflected scene as primary. */
            public static final BooleanSetting MIRROR_PSR =
                    bool("caustica.rt.mirrorPsr", "composite.mirror-psr", true);
            /** Per-frame sky radiance LUT for diffuse continuation sky misses. Off: no measurable gain in the
             *  2026-10-24 same-session A/B (PT 12.1/10.4 off vs 11.7/10.6 ms on). */
            public static final BooleanSetting SKY_LUT =
                    bool("caustica.rt.skyLut", "composite.sky-lut", false);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("caustica.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);
            public static final BooleanSetting BLAS_COMPACTION =
                    bool("caustica.rt.blasCompaction", "terrain.blas-compaction", true);

            private Terrain() {
            }
        }

        /** RIS block-emitter lights. {@code ris-candidates = 0} disables everything. */
        public static final class Lights {
            /**
             * Block-light sampling (RIS): 0 = off (lights gathered only when a path hits them, the previous
             * behaviour and cost), 1 = light (the camera-visible surface only, 4 candidates), 2 = full
             * (two shaded surfaces, ris-candidates). Off by default: it costs frame time.
             */
            public static final IntSetting MODE = clampedInt("caustica.rt.risMode", "lights.mode", 0, 0, 2);
            public static final IntSetting RIS_CANDIDATES =
                    intAtLeast("caustica.rt.risCandidates", "lights.ris-candidates", 8, 0);
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
            public static final BooleanSetting STATS = bool("caustica.rt.lightStats", "lights.stats", false);
            public static final BooleanSetting DUMP = bool("caustica.rt.lightDump", "lights.dump", false);
            public static final IntSetting DUMP_RADIUS =
                    intAtLeast("caustica.rt.lightDumpRadius", "lights.dump-radius", 12, 1);

            private Lights() {
            }
        }

        public static final class Visuals {
            public static final BooleanSetting VANILLA_ENTITY_EMISSION=bool("caustica.rt.entityEmission","visuals.entity-emission",true);
            public static final FloatSetting HELD_SOFTNESS=clampedFloat("caustica.rt.heldSoftness","visuals.held-softness",.12f,0f,.5f);
            public static final FloatSetting HELD_SHADOW_FLOOR=clampedFloat("caustica.rt.heldShadowFloor","visuals.held-shadow-floor",.15f,0f,1f);
            public static final BooleanSetting ITRP_LIGHT_COLORS=bool("caustica.rt.itrpLightColors","visuals.itrp-light-colors",true);
            public static final FloatSetting HAND_DEPTH=clampedFloat("caustica.rt.handDepth","visuals.hand-depth",.5f,.2f,1f);
            public static final FloatSetting ZERO_F0_FALLBACK=clampedFloat("caustica.rt.zeroF0Fallback","visuals.zero-f0-fallback",.02f,0f,.08f);
            public static final FloatSetting END_HALO=clampedFloat("caustica.rt.endHalo","visuals.end-halo",1f,0f,4f);
            public static final BooleanSetting DH_SECONDARY=bool("caustica.compat.dhSecondary","compat.dh-secondary-depth",true);
            public static final BooleanSetting DH_LODS=bool("caustica.compat.dh","compat.distant-horizons",true);
            public static final FloatSetting TORCH_BRIGHTNESS=clampedFloat("caustica.rt.torchBrightness","visuals.torch-brightness",2f,0f,8f);
            public static final FloatSetting EMISSION=clampedFloat("caustica.rt.emissionBrightness","visuals.emission",4f,0f,16f);
            public static final FloatSetting END_LIGHT=clampedFloat("caustica.rt.endLight","visuals.end-light",1.5f,0f,8f);
            public static final FloatSetting END_STARS=clampedFloat("caustica.rt.endStars","visuals.end-stars",3f,0f,10f);
            public static final FloatSetting END_SKY=clampedFloat("caustica.rt.endSky","visuals.end-sky",1f,0f,4f);
            public static final FloatSetting SPECULAR=clampedFloat("caustica.rt.specular","visuals.specular",1f,0f,1f);
            public static final FloatSetting NORMAL=clampedFloat("caustica.rt.normalStrength","visuals.normal-strength",1f,0f,2f);
            public static final FloatSetting PORTAL=clampedFloat("caustica.rt.portalBrightness","visuals.portal",1f,0f,4f);
            public static final FloatSetting RELIEF_SHADOW=clampedFloat("caustica.rt.reliefShadow","visuals.relief-shadow",1f,0f,1f);
            public static final FloatSetting END_CYCLE=clampedFloat("caustica.rt.endCycle","visuals.end-cycle",15f,1f,60f);
            public static final FloatSetting NETHER_BRIGHTNESS=clampedFloat("caustica.rt.netherBrightness","visuals.nether-brightness",1f,0f,8f);
            public static final FloatSetting PUDDLE=clampedFloat("caustica.rt.puddle","visuals.puddle",1f,0f,1f);
            public static final FloatSetting RIPPLES=clampedFloat("caustica.rt.ripples","visuals.ripples",1f,0f,2f);
            public static final BooleanSetting HELD_LIGHTS=bool("caustica.rt.heldLights","visuals.held-lights",true);
            public static final FloatSetting HELD_RADIUS=clampedFloat("caustica.rt.heldRadius","visuals.held-radius",15f,1f,32f);
            public static final FloatSetting HELD_BRIGHTNESS=clampedFloat("caustica.rt.heldBrightness","visuals.held-brightness",1f,0f,8f);
            public static final FloatSetting WETNESS=clampedFloat("caustica.rt.wetness","visuals.wetness",1f,0f,1f);
            public static final FloatSetting FOG=clampedFloat("caustica.rt.fogDensity","visuals.fog-density",1f,0f,4f);
            public static final FloatSetting WATER_SCATTER=clampedFloat("caustica.rt.waterScatter","visuals.water-scatter",1f,0f,4f);
        }

        public static final class Material {
            /**
             * Parallax depth: 0 = OFF (official Caustica behaviour, no height sampling and no UV offset),
             * 1 = Parallax (one height lookup and a single local-UV offset), 2 = Smooth Parallax (a few
             * adaptive steps with one refinement). Only materials whose _n alpha actually carries a height
             * map take part, so a height-less pack renders exactly as it does with parallax off.
             */
            public static final IntSetting PARALLAX =
                    clampedInt("caustica.rt.parallax", "material.parallax", 0, 0, 2);

            public static final FloatSetting PARALLAX_STRENGTH = clampedFloat("caustica.rt.parallaxStrength", "material.parallax-strength", 1f, 0f, 2f);
            public static final IntSetting SMOOTH_STEPS = clampedInt("caustica.rt.smoothSteps", "material.smooth-steps", 3, 2, 8);
            public static final BooleanSetting SMOOTH_HEIGHT_NORMAL = bool("caustica.rt.smoothHeightNormal", "material.smooth-height-normal", true);
            public static final BooleanSetting SMOOTH_SHADOW = bool("caustica.rt.smoothShadow", "material.smooth-shadow", true);

            private Material() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", true);
            public static final IntSetting SUBDIVISION =
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 6);
            public static final BooleanSetting STATS = bool("caustica.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            /**
             * Keep the skin under armour lit. Every armour piece sits 1-2 texels off the body, so its own
             * geometry is the first thing a body vertex's sun/moon shadow ray meets: wearing only boots
             * turned the whole upper body black. With this on, an entity surface starts its light rays
             * outside that armour shell instead of on it. Turn it off to get the old hard self-shadowing.
             */
            public static final BooleanSetting ARMOR_SKIN_LIT =
                    bool("caustica.rt.armorSkinLit", "entities.armor-skin-lit", true);
            /** Debug-only: render each model submission twice and require bitwise-identical CPU captures. */
            public static final BooleanSetting CAPTURE_PARITY =
                    bool("caustica.rt.entityCaptureParity", "entities.debug.capture-parity", false);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 64, 0);
            public static final BooleanSetting REFIT_ENABLED =
                    bool("caustica.rt.entityRefit", "entities.refit.enabled", true);

            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("caustica.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("caustica.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dlssRr", "dlss-rr.enabled", true);
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);
            public static final BooleanSetting SR_ONLY = bool("caustica.rt.dlssSr", "dlss-sr.enabled", false);
            public static final IntSetting SR_PRESET = intValue("caustica.rt.dlssSr.preset", "dlss-sr.preset", 0);
            public static final IntSetting QUALITY = intValue("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting STREAMLINE=bool("caustica.rt.fg.streamline","frame-generation.streamline",true);
            public static final FloatSetting DYNAMIC_TARGET_FPS=clampedFloat("caustica.rt.fg.dynamicTargetFps","frame-generation.dynamic-target-fps",0f,0f,1000f);
            public static final BooleanSetting UI_RECOMPOSITION=bool("caustica.rt.fg.uiRecomposition","frame-generation.ui-recomposition",true);
            public static final BooleanSetting FULLSCREEN_MENU_DETECTION=bool("caustica.rt.fg.fullscreenMenuDetection","frame-generation.fullscreen-menu-detection",true);
            public static final BooleanSetting SHOW_ONLY_INTERPOLATED=bool("caustica.rt.fg.showOnlyInterpolated","frame-generation.show-only-interpolated",false);
            public static boolean requested(){return ENABLED.value();}
            public static String mode(){return ENABLED.value()?"fixed":"off";}

            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * Phase 0 (extension + capability probe only, see {@code RtDeviceBringup}/{@code RtReflex}) — the
         * per-frame sleep call + latency markers + the swapchain {@code VkSwapchainLatencyCreateInfoNV} the
         * spec requires for {@code vkSetLatencySleepModeNV} to take effect land in a later phase.
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("caustica.rt.reflex", "reflex.enabled", false);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        /** ITRP-PTGI style blocky cloud deck drawn into the sky miss path. OFF costs nothing. */
        public static final class Clouds {
            /** No cloud work at all; the sky is bit-identical to the official one. */
            public static final int OFF = 0;
            /** ITRP shape and colours with the cheap step counts. */
            public static final int ITRP_PERFORMANCE = 1;
            /** Same shape and colours, denser light marches for softer interiors and edges. */
            public static final int ITRP_FIDELITY = 2;

            public static final IntSetting MODE =
                    intValue("caustica.rt.clouds", "clouds.mode", ITRP_FIDELITY);

            public static final FloatSetting COVERAGE = clampedFloat("caustica.rt.clouds.coverage", "clouds.coverage", 0f, -0.3f, 0.3f);
            public static final FloatSetting DENSITY = clampedFloat("caustica.rt.clouds.density", "clouds.density", 1f, 0.25f, 2f);
            public static final FloatSetting SIZE = clampedFloat("caustica.rt.clouds.size", "clouds.size", 1f, 0.5f, 2f);
            public static final FloatSetting HEIGHT = clampedFloat("caustica.rt.clouds.height", "clouds.height", 940f, 500f, 1500f);
            public static final FloatSetting BRIGHTNESS = clampedFloat("caustica.rt.clouds.brightness", "clouds.brightness", 1f, 0.25f, 2f);
            public static final FloatSetting BOTTOM = clampedFloat("caustica.rt.clouds.bottom", "clouds.bottom", 1f, 0.25f, 2f);
            public static final FloatSetting SUNLIGHT = clampedFloat("caustica.rt.clouds.sunlight", "clouds.sunlight", 1f, 0f, 2f);
            public static final FloatSetting SPEED = clampedFloat("caustica.rt.clouds.speed", "clouds.speed", 1f, 0f, 3f);
            public static final FloatSetting EDGE = clampedFloat("caustica.rt.clouds.edge", "clouds.edge", 1f, 0f, 2f);
            public static final FloatSetting CIRRUS = clampedFloat("caustica.rt.clouds.cirrus", "clouds.cirrus", 1f, 0f, 2f);
            public static final FloatSetting RAIN = clampedFloat("caustica.rt.clouds.rain", "clouds.rain", 1f, 0f, 1f);

            private Clouds() {
            }
        }

        public static final class Look {
            public static final FloatSetting BLOOM=clampedFloat("caustica.rt.look.bloom","look.bloom",1f,0f,3f);
            public static final BooleanSetting STARS=bool("caustica.rt.look.stars","look.stars",false);
            /** ITRP DETAIL_SHARPEN_AMOUNT: post-tonemap micro-contrast on the overworld ITRP look; 0 disables. */
            public static final FloatSetting DETAIL_SHARPEN=clampedFloat("caustica.rt.look.detailSharpen","look.detail-sharpen",1f,0f,3f);
            /** ITRP SUNLIGHT_RIMS strength: sun rim light on silhouettes facing away from the sun; 0 disables. */
            public static final FloatSetting SUN_RIMS=clampedFloat("caustica.rt.look.sunRims","look.sun-rims",1f,0f,3f);
            public static final FloatSetting SUN_DISC_SIZE=clampedFloat("caustica.rt.look.sunDiscSize","look.sun-disc-size",1f,.2f,3f);
            public static final FloatSetting NIGHT_BRIGHTNESS=clampedFloat("caustica.rt.look.nightBrightness","look.night-brightness",.6f,.1f,2f);
            public static final FloatSetting SUN_GLOW=clampedFloat("caustica.rt.look.sunGlow","look.sun-glow",1f,0f,3f);
            public static final BooleanSetting NEBULA=bool("caustica.rt.look.nebula","look.nebula",true);
            public static final BooleanSetting CLOUD_SHADOW=bool("caustica.rt.look.cloudShadow","look.cloud-shadow",true);
            public static final IntSetting SUN_SHAPE=clampedInt("caustica.rt.look.sunShape","look.sun-shape",0,0,1);
            public static final FloatSetting TORCH_KELVIN=clampedFloat("caustica.rt.look.torchKelvin","look.torch-kelvin",3000f,1000f,12000f);
            /**
             * View transform: false keeps the official AgX display mapping, true switches the display pass to
             * the ITRP-PTGI 0.8.31 default curve (SEUSTonemap: Lottes cone-overlap, TONE_CURVE_POWER 2.5,
             * GAMMA 2.2). Exposure is applied before either curve, exactly as before.
             */
            public static final BooleanSetting ITRP =
                    bool("caustica.rt.itrpLook", "look.itrp", true);

            public static final FloatSetting BRIGHTNESS=clampedFloat("caustica.rt.look.brightness","look.brightness",1f,0.25f,2f);
            public static final FloatSetting CONTRAST=clampedFloat("caustica.rt.look.contrast","look.contrast",1f,0.5f,1.5f);
            public static final FloatSetting SATURATION=clampedFloat("caustica.rt.look.saturation","look.saturation",1f,0f,2f);
            public static final FloatSetting TEMPERATURE=clampedFloat("caustica.rt.look.temperature","look.temperature",0f,-1f,1f);
            public static final FloatSetting GAMMA=clampedFloat("caustica.rt.look.gamma","look.gamma",1f,0.5f,2f);
            public static final FloatSetting SHADOW_LIFT=clampedFloat("caustica.rt.look.shadowLift","look.shadowLift",0f,0f,0.25f);
            public static final FloatSetting HIGHLIGHT_SOFTNESS=clampedFloat("caustica.rt.look.highlightSoftness","look.highlightSoftness",0f,0f,2f);
            private Look() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE =
                    string("caustica.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EV =
                    finiteFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev", 0.0f);
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 4.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 0.12f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 0.35f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {
            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("caustica.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            /** Per-frame GPU timestamps and CPU phase timings for the composite and present paths.
             * See {@code EnhancedGpuTimings}. Off by default: each timestamp is a queue-level
             * serialisation point, so the cost is small but not zero. Enable while measuring. */
            public static final BooleanSetting GPU_TIMINGS =
                    bool("caustica.rt.gpuTimings", "diagnostics.gpu-timings", false);

            private Diagnostics() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 5000.0f);

            // Snapshot of ENABLED as resolved at startup (system property / config file), before any
            // in-session edit from the options screen. The swapchain's pixel format (PQ vs SDR) is fixed
            // at surface-creation time, so flipping ENABLED later cannot change what's actually presented
            // until a restart — every runtime/rendering check reads this frozen value via enabled(),
            // never ENABLED directly, so the live toggle is a no-op for the current session.
            private static final boolean ENABLED_AT_STARTUP = ENABLED.value();

            private Hdr() {
            }

            /** Whether the HDR display path (world HDR + PQ swapchain + UI overlay) is active this session. */
            public static boolean enabled() {
                return ENABLED_AT_STARTUP;
            }

            /** Whether {@link #ENABLED} has been changed since startup and needs a restart to take effect. */
            public static boolean pendingRestart() {
                return ENABLED.value() != ENABLED_AT_STARTUP;
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }
        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    public static final class Assets {
        /** User-owned pack, read externally. No ITRP texture is included in this JAR. Restart after changing. */
        public static final StringSetting ITRP_PACK = string("caustica.assets.itrpPack", "assets.itrp-pack", "", String::trim);
        private Assets() {}
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
