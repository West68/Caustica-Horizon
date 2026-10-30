package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class InvocationReorderPolicyTest {
    private static final int DRIVER = (591 << 22) | (86 << 14);
    @Test void limitsAutomaticWorkaroundToTestedConfiguration() {
        assertTrue(InvocationReorderPolicy.disable(0x10de, "NVIDIA GeForce RTX 4060 Laptop GPU", DRIVER, null));
        assertFalse(InvocationReorderPolicy.disable(0x10de, "NVIDIA GeForce RTX 4060", DRIVER, null));
        assertFalse(InvocationReorderPolicy.disable(0x10de, "NVIDIA GeForce RTX 4070 Laptop GPU", DRIVER, null));
        assertFalse(InvocationReorderPolicy.disable(0x1002, "RTX 4060 Laptop GPU", DRIVER, null));
        assertFalse(InvocationReorderPolicy.disable(0x10de, "RTX 4060 Laptop GPU", (592 << 22) | (86 << 14), null));
        assertFalse(InvocationReorderPolicy.disable(0x10de, "RTX 4060 Laptop GPU", (591 << 22) | (85 << 14), null));
    }
    @Test void explicitPropertyCanRestoreOrRequestDiagnosticPath() {
        assertFalse(InvocationReorderPolicy.disable(0x10de, "RTX 4060 Laptop GPU", DRIVER, "false"));
        assertTrue(InvocationReorderPolicy.disable(0x10de, "Other GPU", 0, "true"));
    }
}
