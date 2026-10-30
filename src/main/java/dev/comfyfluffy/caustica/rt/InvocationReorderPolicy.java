package dev.comfyfluffy.caustica.rt;

/** Narrow, provisional workaround for the tested streaming-crash configuration, not a driver bug claim. */
final class InvocationReorderPolicy {
    static boolean disable(int vendorId, String deviceName, int driverVersion, String override) {
        if (override != null) return Boolean.parseBoolean(override);
        // NVIDIA encodes driver version as 10/8/8/6 bits. Do not extend this to other GPUs/drivers
        // without the same foreground regression tests. HitObject traversal/invocation stay enabled.
        return vendorId == 0x10de && deviceName.contains("RTX 4060 Laptop GPU")
                && (driverVersion >>> 22) == 591 && ((driverVersion >>> 14) & 0xff) == 86;
    }
    private InvocationReorderPolicy() {}
}
