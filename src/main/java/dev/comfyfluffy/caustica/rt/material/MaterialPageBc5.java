package dev.comfyfluffy.caustica.rt.material;

import org.lwjgl.stb.STBDXT;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits an RGBA8 material page into two BC5 pages (RG and BA). Every material channel is an
 * independent scalar (roughness, metalness, normal X/Y, AO, height, F0, ...), which is exactly what
 * BC5's two BC4 halves encode; the page drops from 4 to 2 bytes per texel. The RT closest hit samples
 * both halves and reassembles the RGBA value (world.rchit.slang samplePage*).
 */
final class MaterialPageBc5 {
    /** Encodes channels {@code first} and {@code first+1} of every mip level. */
    static List<byte[]> encode(List<byte[]> rgbaLevels, int width, int height, int first) {
        List<byte[]> result = new ArrayList<>(rgbaLevels.size());
        for (int level = 0; level < rgbaLevels.size(); level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            result.add(encodeLevel(rgbaLevels.get(level), w, h, first));
        }
        return result;
    }

    private static byte[] encodeLevel(byte[] rgba, int w, int h, int first) {
        int bw = (w + 3) / 4, bh = (h + 3) / 4;
        byte[] out = new byte[bw * bh * 16];
        IntStream.range(0, bh).parallel().forEach(by -> {
            long src = MemoryUtil.nmemAlloc(32), dst = MemoryUtil.nmemAlloc(16);
            try {
                for (int bx = 0; bx < bw; bx++) {
                    for (int p = 0; p < 16; p++) {
                        // Edge blocks of the small mips repeat their last texel.
                        int x = Math.min(bx * 4 + (p & 3), w - 1), y = Math.min(by * 4 + (p >> 2), h - 1);
                        int i = (y * w + x) * 4 + first;
                        MemoryUtil.memPutByte(src + p * 2L, rgba[i]);
                        MemoryUtil.memPutByte(src + p * 2L + 1, rgba[i + 1]);
                    }
                    STBDXT.nstb_compress_bc5_block(dst, src);
                    MemoryUtil.memByteBuffer(dst, 16).get(out, (by * bw + bx) * 16, 16);
                }
            } finally {
                MemoryUtil.nmemFree(src);
                MemoryUtil.nmemFree(dst);
            }
        });
        return out;
    }

    private MaterialPageBc5() {}
}
