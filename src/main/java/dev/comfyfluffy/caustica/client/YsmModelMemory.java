package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Better Player Model 1.1.5 forgets the selected model when a world is rejoined (the second join syncs the
 * catalog but never re-requests the player's model, so the default one shows). Remember every model switch
 * the client sends, and once the model sync of a new session is idle, replay the same selection through
 * Better Player Model's own button logic (upload first for a local-only model).
 */
public final class YsmModelMemory {
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("better_player_model");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("caustica-ysm-model.properties");
    private static String modelId, textureId;
    private static boolean restored, replaying, failed;
    private static int idleTicks;

    public static void init() {
        if (!INSTALLED) return;
        load();
        ClientTickEvents.END_CLIENT_TICK.register(YsmModelMemory::tick);
    }

    /** From the NetworkHandler.sendToServer mixin, for every outgoing packet. */
    public static void onSend(Object packet) {
        if (replaying || packet == null || !packet.getClass().getName().endsWith(".C2SRequestSwitchModelPacket")) return;
        try {
            String model = (String) field(packet.getClass(), "modelId").get(packet);
            String texture = (String) field(packet.getClass(), "textureId").get(packet);
            if (model == null || model.isEmpty()) return;
            modelId = model;
            textureId = texture;
            restored = true; // the player chose a model in this session: nothing to replay
            save();
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("YSM model memory: could not read the model switch", t);
        }
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { restored = false; idleTicks = 0; return; }
        if (restored || failed || modelId == null) return;
        restore(mc);
    }

    /** Better Player Model has the server catalog and no model transfer in flight. */
    private static boolean syncIdle() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.elfmcys.yesstevemodel.client.ClientModelManager");
        Object status = manager.getMethod("getSyncStatus").invoke(null);
        Object state = status.getClass().getMethod("getCurrentState").invoke(status);
        return "IDLE".equals(String.valueOf(state)) && (boolean) manager.getMethod("hasServerCatalog").invoke(null);
    }

    private static void restore(Minecraft mc) {
        try {
            Class<?> manager = Class.forName("com.elfmcys.yesstevemodel.client.ClientModelManager");
            // Let the session settle (the first IDLE is reported before the catalog is applied).
            if (!syncIdle()) { idleTicks = 0; return; }
            if (++idleTicks < 40) return;
            restored = true;
            replay(mc, manager);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.warn("YSM model memory: restore disabled", t);
        }
    }

    /** The same decisions as ModelButton's press handler. */
    private static void replay(Minecraft mc, Class<?> manager) throws ReflectiveOperationException {
        Class<?> button = Class.forName("com.elfmcys.yesstevemodel.client.gui.button.ModelButton");
        Method sendSwitch = button.getDeclaredMethod("sendSwitchModel", String.class, String.class);
        sendSwitch.setAccessible(true);
        String requested = modelId;
        String modelId = (String) manager.getMethod("resolveKnownServerModelId", String.class).invoke(null, requested);
        if (modelId == null) modelId = requested;
        if (!modelId.equals(requested))
            manager.getMethod("markLocalModelUploaded", String.class, String.class).invoke(null, requested, modelId);
        // The client copy of the player's model state is what renders; the button sets it before asking
        // the server, which does not answer a request for the model it already holds.
        Class<?> capability = Class.forName("com.elfmcys.yesstevemodel.capability.PlayerCapability");
        Object holder = ((java.util.Optional<?>) capability.getMethod("get", net.minecraft.world.entity.player.Player.class)
                .invoke(null, mc.player)).orElse(null);
        if (holder != null) capability.getMethod("initModelWithTexture", String.class, String.class).invoke(holder, modelId, textureId);
        boolean localOnly = (boolean) manager.getMethod("isLocalOnlyModel", String.class).invoke(null, modelId);
        replaying = true;
        try {
            if (!localOnly) {
                sendSwitch.invoke(null, modelId, textureId);
            } else if ((boolean) manager.getMethod("isOysmServer").invoke(null)) {
                Object error = manager.getMethod("uploadLocalModelForSelection", String.class).invoke(null, modelId);
                if (error != null) { CausticaMod.LOGGER.warn("YSM model memory: upload refused: {}", error); return; }
                if (!(boolean) manager.getMethod("isLocalOnlyModel", String.class).invoke(null, modelId)) {
                    sendSwitch.invoke(null, modelId, textureId);
                } else {
                    Method afterUpload = button.getDeclaredMethod("sendSwitchAfterUpload", String.class, String.class,
                            net.minecraft.client.player.LocalPlayer.class);
                    afterUpload.setAccessible(true);
                    afterUpload.invoke(null, modelId, textureId, mc.player);
                }
            }
            CausticaMod.LOGGER.info("YSM model memory: restored model {} ({})", modelId, textureId);
        } finally {
            replaying = false;
        }
    }

    /** True when {@code assembly} is the entry the current model catalog holds (see YsmLazyModelSessionMixin). */
    public static boolean listedInCurrentCatalog(Object assembly) {
        try {
            Class<?> manager = Class.forName("com.elfmcys.yesstevemodel.client.ClientModelManager");
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) manager.getMethod("getModelAssemblyMap").invoke(null);
            for (Object value : map.values()) if (value == assembly) return true;
            // The catalog may hold a wrapper of the same model: match it by id as well.
            Object id = field(assembly.getClass(), "modelId").get(assembly);
            return id != null && map.containsKey(id);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try (Reader in = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(in);
            modelId = p.getProperty("model");
            textureId = p.getProperty("texture", "");
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("YSM model memory: could not read {}", FILE, e);
        }
    }

    private static void save() {
        try (Writer out = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.setProperty("model", modelId);
            p.setProperty("texture", textureId == null ? "" : textureId);
            p.store(out, "Last Better Player Model selection, replayed by Caustica on world join");
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("YSM model memory: could not write {}", FILE, e);
        }
    }

    private YsmModelMemory() {}
}
