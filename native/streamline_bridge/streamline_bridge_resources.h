#pragma once

#include "streamline_bridge.h"

namespace slbridge::detail {
// Current renderer supplies the eight documented colour/guide/output resources. The legacy
// reference also supplies two optional hints. Neither hint is required for the eight-buffer route.
inline constexpr bool supportsDlssdResourceCount(uint32_t count) noexcept {
    return count == 8 || count == 10;
}


/** Official Vulkan texture tags require VkImage + VkImageView, not VkDeviceMemory ownership. */
inline bool isCompleteDlssdVulkanTexture(const slbridge_resource_desc& descriptor) noexcept {
    return descriptor.valid != 0
            && descriptor.image != 0
            && descriptor.view != 0
            && descriptor.width != 0
            && descriptor.height != 0;
}

} // namespace slbridge::detail
