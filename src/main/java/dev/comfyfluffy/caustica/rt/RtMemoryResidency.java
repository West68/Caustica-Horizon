package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryPriorityFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT;

import java.util.List;

/**
 * Lossless VRAM residency policy for 8 GB GPUs.
 *
 * <p>Without VK_EXT_pageable_device_local_memory, a device-local allocation that does not fit makes
 * VMA's AUTO_PREFER_DEVICE silently fall back to host memory, and that allocation stays in system
 * memory for its whole lifetime (BLAS/TLAS, terrain, material tables, frame targets). While DLSS-G
 * holds its buffers, newly created RT resources can land there and keep path tracing slow even after
 * frame generation is turned off. With pageable device-local memory the driver may migrate
 * allocations in both directions, and VK_EXT_memory_priority lets it keep RT resources resident
 * first. No content, format, size, sample count or shading changes.</p>
 *
 * <p>Disable with {@code -Dcaustica.memory.residency=false}.</p>
 */
public final class RtMemoryResidency {
    private static final String BUDGET = "VK_EXT_memory_budget";
    private static final String PRIORITY = "VK_EXT_memory_priority";
    private static final String PAGEABLE = "VK_EXT_pageable_device_local_memory";
    /** VMA/driver default is 0.5; core RT data is kept above other device-local allocations. */
    public static final float RT_PRIORITY = 1.0f;

    private static boolean budgetEnabled;
    private static boolean priorityEnabled;
    private static boolean pageableEnabled;

    private RtMemoryResidency() {}

    private static boolean requested() {
        return !"false".equalsIgnoreCase(System.getProperty("caustica.memory.residency", "true"));
    }

    /** Called while vanilla's device extension list is being built. */
    public static void addExtensions(List<String> extensions, VulkanPhysicalDevice physicalDevice) {
        budgetEnabled = priorityEnabled = pageableEnabled = false;
        if (!requested()) {
            CausticaMod.LOGGER.info("[Residency] disabled by caustica.memory.residency=false");
            return;
        }
        boolean priorityFeature = false;
        boolean pageableFeature = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryPriorityFeaturesEXT priority = VkPhysicalDeviceMemoryPriorityFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT pageable = VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            if (physicalDevice.hasDeviceExtension(PRIORITY)) {
                priority.pNext(features.pNext());
                features.pNext(priority.address());
            }
            if (physicalDevice.hasDeviceExtension(PAGEABLE)) {
                pageable.pNext(features.pNext());
                features.pNext(pageable.address());
            }
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);
            priorityFeature = physicalDevice.hasDeviceExtension(PRIORITY) && priority.memoryPriority();
            pageableFeature = physicalDevice.hasDeviceExtension(PAGEABLE) && pageable.pageableDeviceLocalMemory();
        } catch (Throwable throwable) {
            CausticaMod.LOGGER.warn("[Residency] feature query failed; keeping default allocation behaviour", throwable);
            return;
        }
        if (physicalDevice.hasDeviceExtension(BUDGET)) {
            budgetEnabled = add(extensions, BUDGET);
        }
        if (priorityFeature) {
            priorityEnabled = add(extensions, PRIORITY);
            // The pageable extension requires VK_EXT_memory_priority.
            if (pageableFeature) pageableEnabled = add(extensions, PAGEABLE);
        }
        CausticaMod.LOGGER.info("[Residency] memory_budget={}, memory_priority={}, pageable_device_local_memory={}",
                budgetEnabled, priorityEnabled, pageableEnabled);
    }

    private static boolean add(List<String> extensions, String name) {
        if (!extensions.contains(name)) extensions.add(name);
        return true;
    }

    /** Chain the feature structs just before vkCreateDevice. */
    public static void attachFeatures(VkDeviceCreateInfo createInfo, MemoryStack stack) {
        if (priorityEnabled) {
            VkPhysicalDeviceMemoryPriorityFeaturesEXT priority = VkPhysicalDeviceMemoryPriorityFeaturesEXT.calloc(stack)
                    .sType$Default().memoryPriority(true).pNext(createInfo.pNext());
            createInfo.pNext(priority.address());
        }
        if (pageableEnabled) {
            VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT pageable = VkPhysicalDevicePageableDeviceLocalMemoryFeaturesEXT.calloc(stack)
                    .sType$Default().pageableDeviceLocalMemory(true).pNext(createInfo.pNext());
            createInfo.pNext(pageable.address());
        }
    }

    /** Extra VMA allocator flags for the RT allocator. */
    public static int vmaAllocatorFlags() {
        int flags = 0;
        if (budgetEnabled) flags |= Vma.VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT;
        if (priorityEnabled) flags |= Vma.VMA_ALLOCATOR_CREATE_EXT_MEMORY_PRIORITY_BIT;
        return flags;
    }

    public static boolean pageableEnabled() {
        return pageableEnabled;
    }
}
