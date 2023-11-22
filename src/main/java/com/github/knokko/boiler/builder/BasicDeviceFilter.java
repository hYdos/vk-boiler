package com.github.knokko.boiler.builder;

import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.github.knokko.boiler.exceptions.VulkanFailureException.assertVkSuccess;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;

class BasicDeviceFilter {

    private static Set<String> getSupportedDeviceExtensions(VkPhysicalDevice vkPhysicalDevice) {
        try (var stack = stackPush()) {
            var pNumExtensions = stack.callocInt(1);
            assertVkSuccess(vkEnumerateDeviceExtensionProperties(
                    vkPhysicalDevice, (ByteBuffer) null, pNumExtensions, null
            ), "EnumerateDeviceExtensionProperties", "BasicDeviceFilter count");
            int numExtensions = pNumExtensions.get(0);

            // NOTE: Do NOT allocate this on the stack because this array can be dangerously large for the
            // small default LWJGL stack
            var pExtensions = VkExtensionProperties.calloc(numExtensions);
            assertVkSuccess(vkEnumerateDeviceExtensionProperties(
                    vkPhysicalDevice, (ByteBuffer) null, pNumExtensions, pExtensions
            ), "EnumerateDeviceExtensionProperties", "BasicDeviceFilter extensions");

            var extensions = new HashSet<String>(numExtensions);
            for (int index = 0; index < numExtensions; index++) {
                extensions.add(pExtensions.get(index).extensionNameString());
            }
            pExtensions.free();
            return extensions;
        }
    }

    private static boolean supportsRequiredFeatures(VkPhysicalDevice device, BoilerBuilder builder) {
        try (var stack = stackPush()) {
            var supportedFeatures = SupportedFeatures.query(
                    stack, device, builder.apiVersion,
                    !builder.vkRequiredFeatures10.isEmpty(), !builder.vkRequiredFeatures11.isEmpty(),
                    !builder.vkRequiredFeatures12.isEmpty(), !builder.vkRequiredFeatures13.isEmpty()
            );
            if (!builder.vkRequiredFeatures10.stream().allMatch(required -> required.supportsRequiredFeatures(supportedFeatures.features10()))) {
                return false;
            }
            if (!builder.vkRequiredFeatures11.stream().allMatch(required -> required.supportsRequiredFeatures(supportedFeatures.features11()))) {
                return false;
            }
            if (!builder.vkRequiredFeatures12.stream().allMatch(required -> required.supportsRequiredFeatures(supportedFeatures.features12()))) {
                return false;
            }
            return builder.vkRequiredFeatures13.stream().allMatch(required -> required.supportsRequiredFeatures(supportedFeatures.features13()));
        }
    }

    static VkPhysicalDevice[] getCandidates(
            BoilerBuilder builder, VkInstance vkInstance, long windowSurface
    ) {
        try (var stack = stackPush()) {
            var pNumDevices = stack.callocInt(1);
            assertVkSuccess(vkEnumeratePhysicalDevices(
                    vkInstance, pNumDevices, null
            ), "EnumeratePhysicalDevices", "BasicDeviceFilter count");
            int numDevices = pNumDevices.get(0);

            var pDevices = stack.callocPointer(numDevices);
            assertVkSuccess(vkEnumeratePhysicalDevices(
                    vkInstance, pNumDevices, pDevices
            ), "EnumeratePhysicalDevices", "BasicDeviceFilter devices");

            var devices = new ArrayList<VkPhysicalDevice>(numDevices);

            int desiredMajorVersion = VK_API_VERSION_MAJOR(builder.apiVersion);
            int desiredMinorVersion = VK_API_VERSION_MINOR(builder.apiVersion);

            deviceLoop:
            for (int index = 0; index < numDevices; index++) {
                var device = new VkPhysicalDevice(pDevices.get(index), vkInstance);
                var properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(device, properties);

                int supportedMajorVersion = VK_API_VERSION_MAJOR(properties.apiVersion());
                int supportedMinorVersion = VK_API_VERSION_MINOR(properties.apiVersion());
                if (supportedMajorVersion < desiredMajorVersion ||
                        (supportedMajorVersion == desiredMajorVersion && supportedMinorVersion < desiredMinorVersion)
                ) {
                    continue;
                }

                if (!supportsRequiredFeatures(device, builder)) continue;

                var supportedExtensions = getSupportedDeviceExtensions(device);
                for (String extension : builder.requiredVulkanDeviceExtensions) {
                    if (!supportedExtensions.contains(extension)) continue deviceLoop;
                }

                boolean hasPresentQueueFamily = false;
                boolean hasGraphicsQueueFamily = false;

                var pNumQueueFamilies = stack.callocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(device, pNumQueueFamilies, null);
                int numQueueFamilies = pNumQueueFamilies.get(0);
                var pQueueFamilies = VkQueueFamilyProperties.calloc(numQueueFamilies, stack);
                vkGetPhysicalDeviceQueueFamilyProperties(device, pNumQueueFamilies, pQueueFamilies);

                for (int queueFamilyIndex = 0; queueFamilyIndex < numQueueFamilies; queueFamilyIndex++) {
                    if (windowSurface != 0L) {
                        var pPresentSupport = stack.callocInt(1);
                        assertVkSuccess(vkGetPhysicalDeviceSurfaceSupportKHR(
                                device, queueFamilyIndex, windowSurface, pPresentSupport
                        ), "GetPhysicalDeviceSurfaceSupportKHR", "BasicDeviceFilter");
                        if (pPresentSupport.get(0) == VK_TRUE) hasPresentQueueFamily = true;
                    } else hasPresentQueueFamily = true;
                    if ((pQueueFamilies.get(queueFamilyIndex).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        hasGraphicsQueueFamily = true;
                    }
                }

                if (!hasPresentQueueFamily || !hasGraphicsQueueFamily) continue;

                if (!builder.extraDeviceRequirements.stream().allMatch(
                        requirements -> requirements.satisfiesRequirements(device, windowSurface, stack)
                )) {
                    continue;
                }

                devices.add(device);
            }

            return devices.toArray(new VkPhysicalDevice[0]);
        }
    }
}
