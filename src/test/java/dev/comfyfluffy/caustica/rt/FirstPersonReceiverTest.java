package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.entity.LightOwnerMask;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstPersonReceiverTest {
    @Test void handRaysKeepWallsAndOtherEntitiesButExcludeOnlyCameraBody() {
        int handRay = 8;
        assertNotEquals(0, handRay & 255); // terrain and DH
        assertNotEquals(0, handRay & LightOwnerMask.receiver(255, false));
        assertEquals(0, handRay & LightOwnerMask.receiver(1, true)); // first-person body
        assertEquals(0, handRay & 2); // separately captured hand is not its own blocker
        assertNotEquals(0, 1 & LightOwnerMask.receiver(1, true)); // world shadows/GI retain body
        assertNotEquals(0, handRay & LightOwnerMask.receiver(255, true)); // third-person body retained
    }

    @Test void receiverTagPreservesEveryPositiveFiniteHalfIorAndTransmissionBits() {
        // IEEE half: bit 15 is unused by positive IOR; upper 16 bits carry transmission.
        for (int ior = 1; ior < 0x7c00; ior++) {
            for (int transmission : new int[]{0, 0x3800, 0x3c00}) {
                int packed = ior | (transmission << 16);
                int marked = packed | 0x8000;
                assertEquals(packed, marked & ~0x8000);
                assertEquals(transmission, marked >>> 16);
            }
        }
    }

    @Test void reflectionGuideAndRadianceUseTheSameReceiverPolicy() throws Exception {
        String ray = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String hit = Files.readString(Path.of("shaders/world/world.rchit.slang"));
        assertTrue(hit.contains("g.reserved.y!=0u"));
        assertTrue(ray.contains("secondaryReceiverMask=handHit ? CULL_HAND_WORLD : CULL_SECONDARY"));
        assertTrue(ray.contains("firstPersonReceiver ? CULL_HAND_WORLD : CULL_SECONDARY, surfacePos"));
        assertTrue(ray.contains("firstPersonReceiver ? CULL_HAND_WORLD : CULL_SECONDARY, ro, RAY_TMIN, refractedDir"));
        assertTrue(ray.contains("payload.iorTransmission & ~FIRST_PERSON_RECEIVER"));
    }
}
