package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import java.nio.file.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

/**
 * Optional 80 KiB user-owned cloud and End noise data. One device upload, no per-frame I/O. The installer
 * copies the two textures out of the user's own SEUS PTGI and iterationRP archives into the built jar
 * ({@link #INSTALLED_BLUE_NOISE}, {@link #INSTALLED_END_NOISE}); without them it falls back to a
 * user-placed ITRP-PTGI pack, and then to the procedural approximation.
 */
public final class ItrpCloudNoise {
    static final String INSTALLED_BLUE_NOISE = "/assets/caustica/external/seus_blue_noise.png";
    static final String INSTALLED_END_NOISE = "/assets/caustica/external/itrp_noise.png";
    private RtBuffer buffer;
    private boolean attempted;
    public long address(RtContext ctx) {
        if (attempted) return buffer == null ? 0L : buffer.deviceAddress;
        attempted = true;
        byte[] installedBlue = resource(INSTALLED_BLUE_NOISE), installedEnd = resource(INSTALLED_END_NOISE);
        if (installedBlue != null) return upload(ctx, installedBlue, installedEnd, "installer");
        String configured = CausticaConfig.Assets.ITRP_PACK.get();
        if (configured.isBlank()) {
            // Optional user installation, never bundled; also accept the user-provided Downloads location.
            Path candidate=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("shaderpacks/iterationRP-0.8.31-ptgi.zip");
            if (!Files.isRegularFile(candidate)) candidate=Path.of(System.getProperty("user.home"),"Downloads","itrp-ptgi","iterationRP-0.8.31-ptgi.zip");
            if (!Files.isRegularFile(candidate)) return 0L;
            configured=candidate.toString();
        }
        try (var zip = new ZipFile(Path.of(configured).toFile())) {
            var entry = zip.getEntry("shaders/texture/PTGI_BlueNoise.png");
            if (entry == null || entry.getSize() > 1_048_576) throw new IllegalArgumentException("Missing/oversized PTGI noise");
            byte[] bytes, end = null;
            try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes(1_048_577); }
            var endEntry=zip.getEntry("shaders/texture/noise.png");
            if(endEntry!=null && endEntry.getSize()<=1_048_576)try(var in=zip.getInputStream(endEntry)) { end=in.readNBytes(1_048_577); }
            return upload(ctx, bytes, end, Path.of(configured).toAbsolutePath()+"!"+entry.getName());
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("External ITRP cloud noise unavailable; using procedural approximation",e);
            return 0L;
        }
    }

    private static byte[] resource(String path) {
        try (var in = ItrpCloudNoise.class.getResourceAsStream(path)) {
            return in == null ? null : in.readNBytes(1_048_577);
        } catch (Exception e) {
            return null;
        }
    }

    private long upload(RtContext ctx, byte[] bytes, byte[] endBytes, String source) {
        RtBuffer staging = null;
        try {
            if (bytes.length > 1_048_576) throw new IllegalArgumentException("Oversized PTGI noise");
            var png = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (png == null || png.getWidth() != 64 || png.getHeight() != 64) throw new IllegalArgumentException("Expected 64x64 RGBA noise");
            staging = ctx.createBuffer(81936L, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, "external cloud staging");
            MemoryUtil.memSet(staging.mapped,0,81936L);
            var dst = MemoryUtil.memIntBuffer(staging.mapped,20484);
            for (int y=0;y<64;y++) for (int x=0;x<64;x++) {
                int argb=png.getRGB(x,y);
                dst.put(((argb>>16)&255) | (argb&0xff00) | ((argb&255)<<16) | (argb&0xff000000));
            }
            // Fixed second region: header at byte16384, 128x128 RGBA8 at byte16400.
            // Marker guards the shader fallback.
            if(endBytes!=null && endBytes.length<=1_048_576) {
                var end=ImageIO.read(new java.io.ByteArrayInputStream(endBytes));
                if(end!=null && end.getWidth()==128 && end.getHeight()==128) {
                    dst.put(4096,0x454e4431);dst.put(4097,128);dst.put(4098,128);
                    for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                        int c=end.getRGB(x,y);dst.put(4100+y*128+x,((c>>16)&255)|(c&0xff00)|((c&255)<<16)|(c&0xff000000));
                    }
                }
            }
            staging.flush();
            buffer = ctx.createBuffer(81936L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "external cloud noise");
            final long src=staging.handle, destination=buffer.handle;
            ctx.submitSync(cmd->{try(var stack=MemoryStack.stackPush()) {
                var copy=VkBufferCopy.calloc(1,stack).size(81936L);VK10.vkCmdCopyBuffer(cmd,src,destination,copy);
                com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd,stack);
            }});
            String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            CausticaMod.LOGGER.info("External ITRP cloud noise: {} sha256={} deviceBytes=81936",source,sha);
        } catch (Exception e) {
            if(buffer!=null){buffer.destroy();buffer=null;}
            CausticaMod.LOGGER.warn("External ITRP cloud noise unavailable; using procedural approximation",e);
        } finally { if(staging!=null)staging.destroy(); }
        return buffer==null?0L:buffer.deviceAddress;
    }
    public void destroy(){if(buffer!=null){buffer.destroy();buffer=null;}attempted=false;}
}
