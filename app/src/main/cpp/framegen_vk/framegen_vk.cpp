// Vulkan 1.3 frame generation: the GL flow/warp pipeline ported to compute-free
// dynamic-rendering passes. Decoder frames arrive through an EGL SurfaceTexture,
// are blitted into AHardwareBuffers, and are sampled by Vulkan with no copy.

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "framegen", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "framegen", __VA_ARGS__)

// ---------------------------------------------------------------------------
// Loader: libvulkan.so only exports 1.0 entry points, so everything goes
// through vkGetInstanceProcAddr/vkGetDeviceProcAddr.

#define VK_GLOBAL_FUNCS(X)                                                    \
    X(vkEnumerateInstanceVersion)                                             \
    X(vkCreateInstance)                                                       \
    X(vkEnumerateInstanceExtensionProperties)

#define VK_INSTANCE_FUNCS(X)                                                  \
    X(vkDestroyInstance)                                                      \
    X(vkEnumeratePhysicalDevices)                                             \
    X(vkGetPhysicalDeviceProperties)                                          \
    X(vkGetPhysicalDeviceFeatures2)                                           \
    X(vkGetPhysicalDeviceQueueFamilyProperties)                               \
    X(vkGetPhysicalDeviceMemoryProperties)                                    \
    X(vkGetPhysicalDeviceFormatProperties)                                    \
    X(vkEnumerateDeviceExtensionProperties)                                   \
    X(vkGetDeviceProcAddr)                                                    \
    X(vkCreateDevice)                                                         \
    X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR)                              \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR)                                   \
    X(vkGetPhysicalDeviceSurfacePresentModesKHR)                              \
    X(vkGetPhysicalDeviceSurfaceSupportKHR)                                   \
    X(vkDestroySurfaceKHR)                                                    \
    X(vkCreateAndroidSurfaceKHR)

#define VK_DEVICE_FUNCS(X)                                                    \
    X(vkDestroyDevice)                                                        \
    X(vkGetDeviceQueue)                                                       \
    X(vkDeviceWaitIdle)                                                       \
    X(vkQueueSubmit)                                                          \
    X(vkQueuePresentKHR)                                                      \
    X(vkCreateSwapchainKHR)                                                   \
    X(vkDestroySwapchainKHR)                                                  \
    X(vkGetSwapchainImagesKHR)                                                \
    X(vkAcquireNextImageKHR)                                                  \
    X(vkCreateSemaphore)                                                      \
    X(vkDestroySemaphore)                                                     \
    X(vkCreateFence)                                                          \
    X(vkDestroyFence)                                                         \
    X(vkWaitForFences)                                                        \
    X(vkResetFences)                                                          \
    X(vkCreateCommandPool)                                                    \
    X(vkDestroyCommandPool)                                                   \
    X(vkAllocateCommandBuffers)                                               \
    X(vkResetCommandBuffer)                                                   \
    X(vkBeginCommandBuffer)                                                   \
    X(vkEndCommandBuffer)                                                     \
    X(vkCreateShaderModule)                                                   \
    X(vkDestroyShaderModule)                                                  \
    X(vkCreateDescriptorSetLayout)                                            \
    X(vkDestroyDescriptorSetLayout)                                           \
    X(vkCreatePipelineLayout)                                                 \
    X(vkDestroyPipelineLayout)                                                \
    X(vkCreateGraphicsPipelines)                                              \
    X(vkDestroyPipeline)                                                      \
    X(vkCreateDescriptorPool)                                                 \
    X(vkDestroyDescriptorPool)                                                \
    X(vkAllocateDescriptorSets)                                               \
    X(vkResetDescriptorPool)                                                  \
    X(vkUpdateDescriptorSets)                                                 \
    X(vkCreateSampler)                                                        \
    X(vkDestroySampler)                                                       \
    X(vkCreateImage)                                                          \
    X(vkDestroyImage)                                                         \
    X(vkGetImageMemoryRequirements)                                           \
    X(vkAllocateMemory)                                                       \
    X(vkFreeMemory)                                                           \
    X(vkBindImageMemory)                                                      \
    X(vkCreateImageView)                                                      \
    X(vkDestroyImageView)                                                     \
    X(vkCmdPipelineBarrier)                                                   \
    X(vkCmdBeginRendering)                                                    \
    X(vkCmdEndRendering)                                                      \
    X(vkCmdBindPipeline)                                                      \
    X(vkCmdBindDescriptorSets)                                                \
    X(vkCmdPushConstants)                                                     \
    X(vkCmdDraw)                                                              \
    X(vkCmdSetViewport)                                                       \
    X(vkCmdSetScissor)                                                        \
    X(vkCmdClearColorImage)                                                   \
    X(vkGetAndroidHardwareBufferPropertiesANDROID)

#define VK_DECL(name) PFN_##name name = nullptr;
VK_GLOBAL_FUNCS(VK_DECL)
VK_INSTANCE_FUNCS(VK_DECL)
VK_DEVICE_FUNCS(VK_DECL)
#undef VK_DECL

static PFN_vkGetInstanceProcAddr gipa = nullptr;

static bool loadVulkan() {
    static bool loaded = false;
    static bool ok = false;
    if (loaded) {
        return ok;
    }
    loaded = true;
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        LOGE("libvulkan.so missing");
        return false;
    }
    gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!gipa) {
        LOGE("vkGetInstanceProcAddr missing");
        return false;
    }
#define VK_LOAD_GLOBAL(name) name = reinterpret_cast<PFN_##name>(gipa(nullptr, #name));
    VK_GLOBAL_FUNCS(VK_LOAD_GLOBAL)
#undef VK_LOAD_GLOBAL
    ok = vkCreateInstance && vkEnumerateInstanceVersion;
    return ok;
}

// ---------------------------------------------------------------------------
// Shared state.

static constexpr VkFormat RGBA = VK_FORMAT_R8G8B8A8_UNORM;

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    int width = 0;
    int height = 0;
    bool external = false;
    AHardwareBuffer* ahb = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    GLuint glTexture = 0;
};

struct Pass {
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
};

struct Engine {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorPool descPool = VK_NULL_HANDLE;
    VkDescriptorPool presentPool = VK_NULL_HANDLE;
    VkFormat presentFormat = VK_FORMAT_UNDEFINED;
    bool fencePending = false;
    std::map<std::string, std::vector<uint32_t>> shaders;

    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapFormat = RGBA;
    VkExtent2D swapExtent{0, 0};
    std::vector<VkImage> swapImages;
    std::vector<VkImageView> swapViews;
    std::vector<VkSemaphore> renderSemaphores;
    VkSemaphore acquireSemaphore = VK_NULL_HANDLE;
    bool swapBgra = false;
    int surfaceWidth = 0;
    int surfaceHeight = 0;

    int videoWidth = 0;
    int videoHeight = 0;
    int lumaWidth = 0;
    int lumaHeight = 0;
    int tileGridW = 0;
    int tileGridH = 0;
    float tileW = 0;
    float tileH = 0;

    Image frames[2];
    Image luma[2];
    Image search;
    Image blocks;
    Image pick;
    Image flow;
    Image tiles;
    Image pixelFlow;
    Image pixelSad;
    Image prevTile;
    Image prevGlobal;
    Image output;

    Pass pLuma;
    Pass pSearch;
    Pass pBlock;
    Pass pPick;
    Pass pRefine;
    Pass pTile;
    Pass pPixelFlow;
    Pass pPixelSad;
    Pass pWarp;
    Pass pCopy;
    Pass pPresent;

    VkDescriptorSet dLuma[2]{};
    VkDescriptorSet dSearch[2]{};
    VkDescriptorSet dBlock = VK_NULL_HANDLE;
    VkDescriptorSet dPick = VK_NULL_HANDLE;
    VkDescriptorSet dRefine = VK_NULL_HANDLE;
    VkDescriptorSet dTile[2]{};
    VkDescriptorSet dPixelFlow[2]{};
    VkDescriptorSet dPixelSad[2]{};
    VkDescriptorSet dCopyTiles = VK_NULL_HANDLE;
    VkDescriptorSet dCopyGlobal = VK_NULL_HANDLE;
    VkDescriptorSet dWarp[2]{};
    VkDescriptorSet dPresent = VK_NULL_HANDLE;
    VkDescriptorSet dRaw[2]{};
};

static Engine* gEngine = nullptr;

// Push constant blocks, matching the shader layouts (std430).
struct PcLuma {
    float texel[2];
};
struct PcSearch {
    float step[2];
    float pickRange;
    float stride;
    float inset;
};
struct PcPick {
    float stride;
    float range;
};
struct PcRefine {
    float pickRange;
    float stride;
};
struct PcTile {
    float lumaSize[2];
    float tileSize[2];
    float grid[2];
    float reg;
};
struct PcPixelFlow {
    float lumaSize[2];
    float tileSize[2];
    float grid[2];
};
struct PcPixelSad {
    float lumaSize[2];
};
struct PcWarp {
    float step[2];
    float bad[2];
    float dis[2];
    float timestep;
    float disW;
};

static_assert(sizeof(PcLuma) == 8, "luma push constant");
static_assert(sizeof(PcSearch) == 20, "search push constant");
static_assert(sizeof(PcTile) == 28, "tile push constant");
static_assert(sizeof(PcWarp) == 32, "warp push constant");

// ---------------------------------------------------------------------------
// Small Vulkan helpers.

static VkShaderModule shaderModule(Engine& e, const std::string& name) {
    auto it = e.shaders.find(name);
    if (it == e.shaders.end()) {
        LOGE("shader %s missing", name.c_str());
        return VK_NULL_HANDLE;
    }
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = it->second.size() * sizeof(uint32_t);
    ci.pCode = it->second.data();
    VkShaderModule module = VK_NULL_HANDLE;
    vkCreateShaderModule(e.device, &ci, nullptr, &module);
    return module;
}

static Pass createPass(Engine& e, const char* name, const std::vector<uint32_t>& bindings,
                       uint32_t pushSize, VkFormat format) {
    Pass pass;
    std::vector<VkDescriptorSetLayoutBinding> layoutBindings;
    for (uint32_t binding : bindings) {
        VkDescriptorSetLayoutBinding b{};
        b.binding = binding;
        b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        b.descriptorCount = 1;
        b.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        layoutBindings.push_back(b);
    }
    VkDescriptorSetLayoutCreateInfo sl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    sl.bindingCount = static_cast<uint32_t>(layoutBindings.size());
    sl.pBindings = layoutBindings.data();
    if (vkCreateDescriptorSetLayout(e.device, &sl, nullptr, &pass.setLayout) != VK_SUCCESS) {
        return pass;
    }
    VkPushConstantRange range{};
    range.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    range.offset = 0;
    range.size = pushSize;
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &pass.setLayout;
    if (pushSize) {
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &range;
    }
    if (vkCreatePipelineLayout(e.device, &pl, nullptr, &pass.layout) != VK_SUCCESS) {
        return pass;
    }

    VkShaderModule vert = shaderModule(e, "full");
    VkShaderModule frag = shaderModule(e, name);
    if (!vert || !frag) {
        return pass;
    }
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{
            VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;
    vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{
            VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{
            VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blend{};
    blend.colorWriteMask = 0xf;
    VkPipelineColorBlendStateCreateInfo cb{
            VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &blend;
    VkDynamicState dynamic[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dynamic;

    VkPipelineRenderingCreateInfo rendering{
            VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
    rendering.colorAttachmentCount = 1;
    rendering.pColorAttachmentFormats = &format;

    VkGraphicsPipelineCreateInfo gp{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gp.pNext = &rendering;
    gp.stageCount = 2;
    gp.pStages = stages;
    gp.pVertexInputState = &vi;
    gp.pInputAssemblyState = &ia;
    gp.pViewportState = &vp;
    gp.pRasterizationState = &rs;
    gp.pMultisampleState = &ms;
    gp.pColorBlendState = &cb;
    gp.pDynamicState = &ds;
    gp.layout = pass.layout;
    vkCreateGraphicsPipelines(e.device, VK_NULL_HANDLE, 1, &gp, nullptr, &pass.pipeline);

    vkDestroyShaderModule(e.device, vert, nullptr);
    vkDestroyShaderModule(e.device, frag, nullptr);
    if (!pass.pipeline) {
        LOGE("pipeline %s failed", name);
    }
    return pass;
}

static uint32_t memoryType(Engine& e, uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(e.physical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) {
            return i;
        }
    }
    return UINT32_MAX;
}

static void destroyImage(Engine& e, Image& image) {
    if (image.view) {
        vkDestroyImageView(e.device, image.view, nullptr);
    }
    if (image.image) {
        vkDestroyImage(e.device, image.image, nullptr);
    }
    if (image.memory) {
        vkFreeMemory(e.device, image.memory, nullptr);
    }
    if (image.glTexture) {
        glDeleteTextures(1, &image.glTexture);
    }
    if (image.eglImage != EGL_NO_IMAGE_KHR) {
        auto destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                eglGetProcAddress("eglDestroyImageKHR"));
        if (destroy) {
            destroy(eglGetCurrentDisplay(), image.eglImage);
        }
    }
    if (image.ahb) {
        AHardwareBuffer_release(image.ahb);
    }
    image = Image{};
}

static bool createImage(Engine& e, Image& image, int width, int height) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = RGBA;
    ci.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
    if (vkCreateImage(e.device, &ci, nullptr, &image.image) != VK_SUCCESS) {
        LOGE("image %dx%d failed", width, height);
        return false;
    }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(e.device, image.image, &req);
    uint32_t type = memoryType(e, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) {
        type = memoryType(e, req.memoryTypeBits, 0);
    }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(e.device, &ai, nullptr, &image.memory) != VK_SUCCESS
            || vkBindImageMemory(e.device, image.image, image.memory, 0) != VK_SUCCESS) {
        LOGE("image memory %dx%d failed", width, height);
        return false;
    }
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = image.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = RGBA;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(e.device, &vi, nullptr, &image.view) != VK_SUCCESS) {
        return false;
    }
    image.width = width;
    image.height = height;
    return true;
}

static bool createExternalImage(Engine& e, Image& image, int width, int height) {
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
            | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (AHardwareBuffer_allocate(&desc, &image.ahb) != 0) {
        LOGE("AHardwareBuffer %dx%d failed", width, height);
        return false;
    }

    auto getBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto createImageKhr = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!getBuffer || !createImageKhr || !imageTarget) {
        LOGE("EGL AHB entry points missing");
        return false;
    }
    EGLClientBuffer buffer = getBuffer(image.ahb);
    EGLint attrs[] = {EGL_NONE};
    image.eglImage = createImageKhr(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                    EGL_NATIVE_BUFFER_ANDROID, buffer, attrs);
    if (image.eglImage == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed 0x%x", eglGetError());
        return false;
    }
    glGenTextures(1, &image.glTexture);
    glBindTexture(GL_TEXTURE_2D, image.glTexture);
    imageTarget(GL_TEXTURE_2D, image.eglImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    VkAndroidHardwareBufferFormatPropertiesANDROID format{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &format;
    if (vkGetAndroidHardwareBufferPropertiesANDROID(e.device, image.ahb, &props) != VK_SUCCESS) {
        LOGE("AHB properties failed");
        return false;
    }
    if (!(format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) {
        LOGE("AHB not sampleable (0x%x)", format.formatFeatures);
        return false;
    }

    VkExternalMemoryImageCreateInfo external{
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    external.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.pNext = &external;
    ci.imageType = VK_IMAGE_TYPE_2D;
    VkFormat ahbFormat = format.format != VK_FORMAT_UNDEFINED ? format.format : RGBA;
    ci.format = ahbFormat;
    ci.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
    if (vkCreateImage(e.device, &ci, nullptr, &image.image) != VK_SUCCESS) {
        LOGE("AHB image create failed");
        return false;
    }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(e.device, image.image, &req);
    VkMemoryDedicatedAllocateInfo dedicated{
            VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = image.image;
    VkImportAndroidHardwareBufferInfoANDROID import{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.buffer = image.ahb;
    import.pNext = &dedicated;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.pNext = &import;
    ai.allocationSize = req.size;
    uint32_t type = memoryType(e, props.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) {
        type = memoryType(e, props.memoryTypeBits, 0);
    }
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(e.device, &ai, nullptr, &image.memory) != VK_SUCCESS
            || vkBindImageMemory(e.device, image.image, image.memory, 0) != VK_SUCCESS) {
        LOGE("AHB bind failed");
        return false;
    }
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = image.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ahbFormat;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(e.device, &vi, nullptr, &image.view) != VK_SUCCESS) {
        return false;
    }
    image.width = width;
    image.height = height;
    image.external = true;
    return true;
}

static VkDescriptorSet allocSet(Engine& e, const Pass& pass,
                                VkDescriptorPool pool = VK_NULL_HANDLE) {
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = pool ? pool : e.descPool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &pass.setLayout;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(e.device, &ai, &set) != VK_SUCCESS) {
        LOGE("descriptor set failed");
    }
    return set;
}

static void writeSet(Engine& e, VkDescriptorSet set, const std::vector<VkImageView>& views) {
    if (!set) {
        return;
    }
    std::vector<VkDescriptorImageInfo> infos(views.size());
    std::vector<VkWriteDescriptorSet> writes(views.size());
    for (size_t i = 0; i < views.size(); i++) {
        infos[i].sampler = e.sampler;
        infos[i].imageView = views[i];
        infos[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = set;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[i].pImageInfo = &infos[i];
    }
    vkUpdateDescriptorSets(e.device, static_cast<uint32_t>(writes.size()), writes.data(), 0,
                           nullptr);
}

// ---------------------------------------------------------------------------
// Commands.

static void memoryBarrier(VkCommandBuffer cmd, VkPipelineStageFlags src,
                          VkAccessFlags srcAccess, VkPipelineStageFlags dst,
                          VkAccessFlags dstAccess) {
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    vkCmdPipelineBarrier(cmd, src, dst, 0, 1, &barrier, 0, nullptr, 0, nullptr);
}

/** Takes Vulkan ownership of both imported slots after GL has written them. */
static void externalAcquire(VkCommandBuffer cmd, Engine& e) {
    VkImageMemoryBarrier barriers[2]{};
    for (int i = 0; i < 2; i++) {
        if (!e.frames[i].image) {
            return;
        }
        barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        barriers[i].dstQueueFamilyIndex = e.queueFamily;
        barriers[i].image = e.frames[i].image;
        barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 2,
                         barriers);
}

/** Hands the slots back to the decoder/GL side so the next pair can be written. */
static void externalRelease(VkCommandBuffer cmd, Engine& e) {
    VkImageMemoryBarrier barriers[2]{};
    for (int i = 0; i < 2; i++) {
        if (!e.frames[i].image) {
            return;
        }
        barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barriers[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].srcQueueFamilyIndex = e.queueFamily;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        barriers[i].image = e.frames[i].image;
        barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 2,
                         barriers);
}

static void passSwitch(VkCommandBuffer cmd) {
    memoryBarrier(cmd,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                  VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                  VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
}

static void beginRender(VkCommandBuffer cmd, VkImageView view, int width, int height,
                        bool swapchain) {
    VkRenderingAttachmentInfo color{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
    color.imageView = view;
    color.imageLayout = swapchain ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                                  : VK_IMAGE_LAYOUT_GENERAL;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.clearValue = {{{0.f, 0.f, 0.f, 1.f}}};
    VkRenderingInfo info{VK_STRUCTURE_TYPE_RENDERING_INFO};
    info.renderArea = {{0, 0}, {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}};
    info.layerCount = 1;
    info.colorAttachmentCount = 1;
    info.pColorAttachments = &color;
    vkCmdBeginRendering(cmd, &info);
    VkViewport viewport{0.f, 0.f, static_cast<float>(width), static_cast<float>(height), 0.f, 1.f};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    VkRect2D scissor{{0, 0}, {static_cast<uint32_t>(width), static_cast<uint32_t>(height)}};
    vkCmdSetScissor(cmd, 0, 1, &scissor);
}

struct Target {
    VkImageView view;
    int width;
    int height;
    VkImage image;
    bool swap;
};

static void runPass(Engine& e, VkCommandBuffer cmd, const Pass& pass, VkDescriptorSet set,
                    const void* pc, uint32_t pcSize, const Target& target) {
    beginRender(cmd, target.view, target.width, target.height, target.swap);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.layout, 0, 1,
                            &set, 0, nullptr);
    if (pcSize) {
        vkCmdPushConstants(cmd, pass.layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcSize, pc);
    }
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRendering(cmd);
    passSwitch(cmd);
}

// ---------------------------------------------------------------------------
// Swapchain.

static void destroySwapchain(Engine& e) {
    if (!e.device) {
        return;
    }
    vkDeviceWaitIdle(e.device);
    for (VkImageView view : e.swapViews) {
        vkDestroyImageView(e.device, view, nullptr);
    }
    e.swapViews.clear();
    e.swapImages.clear();
    for (VkSemaphore semaphore : e.renderSemaphores) {
        vkDestroySemaphore(e.device, semaphore, nullptr);
    }
    e.renderSemaphores.clear();
    if (e.swapchain) {
        vkDestroySwapchainKHR(e.device, e.swapchain, nullptr);
        e.swapchain = VK_NULL_HANDLE;
    }
}

static bool createSwapchain(Engine& e) {
    if (!e.surface || !e.device) {
        return false;
    }
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(e.physical, e.surface, &caps) != VK_SUCCESS) {
        return false;
    }
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX || extent.height == UINT32_MAX) {
        extent = {static_cast<uint32_t>(e.surfaceWidth), static_cast<uint32_t>(e.surfaceHeight)};
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;
    }

    uint32_t count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(e.physical, e.surface, &count, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(e.physical, e.surface, &count, formats.data());
    VkSurfaceFormatKHR chosen = formats.empty() ? VkSurfaceFormatKHR{RGBA,
            VK_COLOR_SPACE_SRGB_NONLINEAR_KHR} : formats[0];
    for (const auto& format : formats) {
        if (format.format == VK_FORMAT_R8G8B8A8_UNORM) {
            chosen = format;
            break;
        }
        if (format.format == VK_FORMAT_B8G8R8A8_UNORM) {
            chosen = format;
        }
    }
    e.swapFormat = chosen.format;
    e.swapBgra = chosen.format == VK_FORMAT_B8G8R8A8_UNORM;
    e.swapExtent = extent;

    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & alpha)) {
        alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    }
    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = e.surface;
    ci.minImageCount = imageCount;
    ci.imageFormat = chosen.format;
    ci.imageColorSpace = chosen.colorSpace;
    ci.imageExtent = extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = caps.currentTransform;
    ci.compositeAlpha = alpha;
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    ci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(e.device, &ci, nullptr, &e.swapchain) != VK_SUCCESS) {
        LOGE("swapchain create failed");
        e.swapchain = VK_NULL_HANDLE;
        return false;
    }
    uint32_t images = 0;
    vkGetSwapchainImagesKHR(e.device, e.swapchain, &images, nullptr);
    e.swapImages.resize(images);
    vkGetSwapchainImagesKHR(e.device, e.swapchain, &images, e.swapImages.data());
    e.swapViews.resize(images);
    e.renderSemaphores.resize(images);
    for (uint32_t i = 0; i < images; i++) {
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = e.swapImages[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = e.swapFormat;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCreateImageView(e.device, &vi, nullptr, &e.swapViews[i]);
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        vkCreateSemaphore(e.device, &si, nullptr, &e.renderSemaphores[i]);
    }
    e.surfaceWidth = static_cast<int>(extent.width);
    e.surfaceHeight = static_cast<int>(extent.height);
    LOGI("swapchain %ux%u %s x%u present",
         extent.width, extent.height, e.swapBgra ? "BGRA" : "RGBA", images);
    return true;
}

static VkImageMemoryBarrier swapBarrier(VkImage image, VkImageLayout from, VkImageLayout to,
                                        VkAccessFlags src, VkAccessFlags dst) {
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = src;
    barrier.dstAccessMask = dst;
    barrier.oldLayout = from;
    barrier.newLayout = to;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return barrier;
}

// ---------------------------------------------------------------------------
// Setup.

static void destroyDescriptors(Engine& e) {
    if (e.descPool) {
        vkDestroyDescriptorPool(e.device, e.descPool, nullptr);
        e.descPool = VK_NULL_HANDLE;
    }
    std::memset(e.dLuma, 0, sizeof(e.dLuma));
    e.dBlock = e.dPick = e.dRefine = VK_NULL_HANDLE;
    std::memset(e.dSearch, 0, sizeof(e.dSearch));
    std::memset(e.dTile, 0, sizeof(e.dTile));
    std::memset(e.dPixelFlow, 0, sizeof(e.dPixelFlow));
    std::memset(e.dPixelSad, 0, sizeof(e.dPixelSad));
    e.dCopyTiles = e.dCopyGlobal = VK_NULL_HANDLE;
    std::memset(e.dWarp, 0, sizeof(e.dWarp));
}

static void createDescriptors(Engine& e) {
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 96};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 32;
    pi.poolSizeCount = 1;
    pi.pPoolSizes = &size;
    if (vkCreateDescriptorPool(e.device, &pi, nullptr, &e.descPool) != VK_SUCCESS) {
        LOGE("descriptor pool failed");
        return;
    }
    for (int i = 0; i < 2; i++) {
        e.dLuma[i] = allocSet(e, e.pLuma);
    }
    for (int i = 0; i < 2; i++) {
        e.dSearch[i] = allocSet(e, e.pSearch);
    }
    e.dBlock = allocSet(e, e.pBlock);
    e.dPick = allocSet(e, e.pPick);
    e.dRefine = allocSet(e, e.pRefine);
    for (int i = 0; i < 2; i++) {
        e.dTile[i] = allocSet(e, e.pTile);
        e.dPixelFlow[i] = allocSet(e, e.pPixelFlow);
        e.dPixelSad[i] = allocSet(e, e.pPixelSad);
    }
    e.dCopyTiles = allocSet(e, e.pCopy);
    e.dCopyGlobal = allocSet(e, e.pCopy);
    for (int i = 0; i < 2; i++) {
        e.dWarp[i] = allocSet(e, e.pWarp);
    }

    for (int i = 0; i < 2; i++) {
        writeSet(e, e.dLuma[i], {e.frames[i].view});
        writeSet(e, e.dTile[i], {e.luma[i].view, e.luma[1 - i].view, e.flow.view,
                                 e.prevTile.view, e.prevGlobal.view});
        writeSet(e, e.dPixelFlow[i], {e.luma[i].view, e.luma[1 - i].view, e.tiles.view});
        writeSet(e, e.dPixelSad[i], {e.luma[i].view, e.luma[1 - i].view, e.pixelFlow.view});
        writeSet(e, e.dWarp[i], {e.frames[i].view, e.frames[1 - i].view,
                                 e.pixelFlow.view, e.pixelSad.view});
    }
    for (int i = 0; i < 2; i++) {
        writeSet(e, e.dSearch[i], {e.luma[i].view, e.luma[1 - i].view, e.pick.view});
    }
    writeSet(e, e.dBlock, {e.search.view});
    writeSet(e, e.dPick, {e.blocks.view});
    writeSet(e, e.dRefine, {e.search.view, e.blocks.view, e.pick.view});
    writeSet(e, e.dCopyTiles, {e.tiles.view});
    writeSet(e, e.dCopyGlobal, {e.flow.view});
}

static void createPresentDescriptors(Engine& e) {
    if (!e.output.view || !e.pPresent.setLayout || !e.presentPool) {
        return;
    }
    if (!e.dPresent) {
        e.dPresent = allocSet(e, e.pPresent, e.presentPool);
        for (int i = 0; i < 2; i++) {
            e.dRaw[i] = allocSet(e, e.pPresent, e.presentPool);
        }
    }
    writeSet(e, e.dPresent, {e.output.view});
    if (e.frames[0].view) {
        for (int i = 0; i < 2; i++) {
            writeSet(e, e.dRaw[i], {e.frames[i].view});
        }
    }
}

static bool createPresentPass(Engine& e) {
    if (e.presentPool) {
        vkDestroyDescriptorPool(e.device, e.presentPool, nullptr);
        e.presentPool = VK_NULL_HANDLE;
    }
    e.dPresent = VK_NULL_HANDLE;
    std::memset(e.dRaw, 0, sizeof(e.dRaw));
    if (e.pPresent.pipeline) {
        vkDestroyPipeline(e.device, e.pPresent.pipeline, nullptr);
    }
    if (e.pPresent.layout) {
        vkDestroyPipelineLayout(e.device, e.pPresent.layout, nullptr);
    }
    if (e.pPresent.setLayout) {
        vkDestroyDescriptorSetLayout(e.device, e.pPresent.setLayout, nullptr);
    }
    e.pPresent = createPass(e, "present", {0}, 0, e.swapFormat);
    e.presentFormat = e.swapFormat;
    if (!e.pPresent.pipeline) {
        return false;
    }
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 4;
    pi.poolSizeCount = 1;
    pi.pPoolSizes = &size;
    if (vkCreateDescriptorPool(e.device, &pi, nullptr, &e.presentPool) != VK_SUCCESS) {
        LOGE("present pool failed");
        return false;
    }
    createPresentDescriptors(e);
    return true;
}

static bool createOutput(Engine& e) {
    destroyImage(e, e.output);
    if (e.surfaceWidth <= 0 || e.surfaceHeight <= 0) {
        return false;
    }
    if (!createImage(e, e.output, e.surfaceWidth, e.surfaceHeight)) {
        return false;
    }
    if (e.presentFormat != e.swapFormat || !e.pPresent.pipeline) {
        return createPresentPass(e);
    }
    createPresentDescriptors(e);
    return true;
}

static void clearImages(Engine& e) {
    vkResetCommandBuffer(e.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(e.cmd, &bi);
    VkClearColorValue zero{};
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    Image* images[] = {&e.pick, &e.flow, &e.prevTile, &e.prevGlobal};
    for (Image* image : images) {
        vkCmdClearColorImage(e.cmd, image->image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
    }
    memoryBarrier(e.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    vkEndCommandBuffer(e.cmd);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &e.cmd;
    vkQueueSubmit(e.queue, 1, &submit, VK_NULL_HANDLE);
    vkDeviceWaitIdle(e.device);
}

static bool createVideoResources(Engine& e) {
    if (e.device) {
        vkDeviceWaitIdle(e.device);
    }
    destroyDescriptors(e);
    for (int i = 0; i < 2; i++) {
        destroyImage(e, e.frames[i]);
        destroyImage(e, e.luma[i]);
    }
    Image* others[] = {&e.search, &e.blocks, &e.pick, &e.flow, &e.tiles, &e.pixelFlow,
                       &e.pixelSad, &e.prevTile, &e.prevGlobal};
    for (Image* image : others) {
        destroyImage(e, *image);
    }

    e.lumaWidth = std::max(1, e.videoWidth / 4);
    e.lumaHeight = std::max(1, e.videoHeight / 4);
    int tiles = 16;
    e.tileGridW = std::max(1, (e.lumaWidth + tiles - 1) / tiles);
    e.tileGridH = std::max(1, (e.lumaHeight + tiles - 1) / tiles);
    e.tileW = e.lumaWidth / static_cast<float>(e.tileGridW);
    e.tileH = e.lumaHeight / static_cast<float>(e.tileGridH);

    bool ok = true;
    for (int i = 0; i < 2; i++) {
        ok = ok && createExternalImage(e, e.frames[i], e.videoWidth, e.videoHeight);
        ok = ok && createImage(e, e.luma[i], e.lumaWidth, e.lumaHeight);
    }
    ok = ok && createImage(e, e.search, 49, 49);
    ok = ok && createImage(e, e.blocks, 7, 7);
    ok = ok && createImage(e, e.pick, 1, 1);
    ok = ok && createImage(e, e.flow, 1, 1);
    ok = ok && createImage(e, e.tiles, e.tileGridW, e.tileGridH);
    ok = ok && createImage(e, e.pixelFlow, e.lumaWidth, e.lumaHeight);
    ok = ok && createImage(e, e.pixelSad, e.lumaWidth, e.lumaHeight);
    ok = ok && createImage(e, e.prevTile, e.tileGridW, e.tileGridH);
    ok = ok && createImage(e, e.prevGlobal, 1, 1);
    if (!ok) {
        return false;
    }
    createDescriptors(e);
    createPresentDescriptors(e);
    clearImages(e);
    LOGI("video %dx%d luma %dx%d tiles %dx%d", e.videoWidth, e.videoHeight,
         e.lumaWidth, e.lumaHeight, e.tileGridW, e.tileGridH);
    return e.dSearch[0] != VK_NULL_HANDLE && e.dWarp[0] != VK_NULL_HANDLE;
}

// ---------------------------------------------------------------------------
// Frame work.

/** Waits for the last submit; called before the decoder writes an imported slot. */
static void syncFence(Engine& e) {
    if (!e.fencePending) {
        return;
    }
    vkWaitForFences(e.device, 1, &e.fence, VK_TRUE, UINT64_MAX);
    vkResetFences(e.device, 1, &e.fence);
    e.fencePending = false;
}

static void submitAndWait(Engine& e) {
    vkEndCommandBuffer(e.cmd);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &e.cmd;
    vkQueueSubmit(e.queue, 1, &submit, e.fence);
    e.fencePending = true;
    syncFence(e);
}

static bool beginCommand(Engine& e) {
    syncFence(e);
    vkResetCommandBuffer(e.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    return vkBeginCommandBuffer(e.cmd, &bi) == VK_SUCCESS;
}

static bool runFlow(Engine& e, int prev, int next) {
    if (!e.videoWidth || !beginCommand(e)) {
        return false;
    }
    VkCommandBuffer cmd = e.cmd;
    externalAcquire(cmd, e);
    Target lumaPrev{e.luma[prev].view, e.lumaWidth, e.lumaHeight, e.luma[prev].image, false};
    Target lumaNext{e.luma[next].view, e.lumaWidth, e.lumaHeight, e.luma[next].image, false};
    PcLuma pcLuma{{1.f / e.videoWidth, 1.f / e.videoHeight}};
    runPass(e, cmd, e.pLuma, e.dLuma[prev], &pcLuma, sizeof(pcLuma), lumaPrev);
    runPass(e, cmd, e.pLuma, e.dLuma[next], &pcLuma, sizeof(pcLuma), lumaNext);

    PcSearch coarse{{1.f / e.lumaWidth, 1.f / e.lumaHeight}, 0.f, 2.f, 0.20f};
    PcSearch fine{{1.f / e.lumaWidth, 1.f / e.lumaHeight}, 48.f, 0.5f, 0.24f};
    Target search{e.search.view, 49, 49, e.search.image, false};
    Target blocks{e.blocks.view, 7, 7, e.blocks.image, false};
    Target pick{e.pick.view, 1, 1, e.pick.image, false};
    Target flow{e.flow.view, 1, 1, e.flow.image, false};
    runPass(e, cmd, e.pSearch, e.dSearch[prev], &coarse, sizeof(coarse), search);
    runPass(e, cmd, e.pBlock, e.dBlock, nullptr, 0, blocks);
    PcPick coarsePick{2.f, 48.f};
    runPass(e, cmd, e.pPick, e.dPick, &coarsePick, sizeof(coarsePick), pick);
    runPass(e, cmd, e.pSearch, e.dSearch[prev], &fine, sizeof(fine), search);
    runPass(e, cmd, e.pBlock, e.dBlock, nullptr, 0, blocks);
    PcRefine refine{48.f, 0.5f};
    runPass(e, cmd, e.pRefine, e.dRefine, &refine, sizeof(refine), flow);

    PcTile pcTile{{static_cast<float>(e.lumaWidth), static_cast<float>(e.lumaHeight)},
                  {e.tileW, e.tileH},
                  {static_cast<float>(e.tileGridW), static_cast<float>(e.tileGridH)},
                  0.002f};
    Target tiles{e.tiles.view, e.tileGridW, e.tileGridH, e.tiles.image, false};
    runPass(e, cmd, e.pTile, e.dTile[prev], &pcTile, sizeof(pcTile), tiles);

    PcPixelFlow pcPixel{{static_cast<float>(e.lumaWidth), static_cast<float>(e.lumaHeight)},
                        {e.tileW, e.tileH},
                        {static_cast<float>(e.tileGridW), static_cast<float>(e.tileGridH)}};
    Target pixelFlow{e.pixelFlow.view, e.lumaWidth, e.lumaHeight, e.pixelFlow.image, false};
    runPass(e, cmd, e.pPixelFlow, e.dPixelFlow[prev], &pcPixel, sizeof(pcPixel), pixelFlow);

    PcPixelSad pcSad{{static_cast<float>(e.lumaWidth), static_cast<float>(e.lumaHeight)}};
    Target pixelSad{e.pixelSad.view, e.lumaWidth, e.lumaHeight, e.pixelSad.image, false};
    runPass(e, cmd, e.pPixelSad, e.dPixelSad[prev], &pcSad, sizeof(pcSad), pixelSad);

    Target prevTiles{e.prevTile.view, e.tileGridW, e.tileGridH, e.prevTile.image, false};
    Target prevGlobal{e.prevGlobal.view, 1, 1, e.prevGlobal.image, false};
    runPass(e, cmd, e.pCopy, e.dCopyTiles, nullptr, 0, prevTiles);
    runPass(e, cmd, e.pCopy, e.dCopyGlobal, nullptr, 0, prevGlobal);
    externalRelease(cmd, e);
    submitAndWait(e);
    return true;
}

static bool drawFrame(Engine& e, bool warp, int prev, int next, float timestep, int raw) {
    if (!e.swapchain || !e.device || e.swapImages.empty()) {
        return false;
    }
    uint32_t index = 0;
    VkResult acquired = vkAcquireNextImageKHR(e.device, e.swapchain, UINT64_MAX,
                                             e.acquireSemaphore, VK_NULL_HANDLE, &index);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        destroySwapchain(e);
        createSwapchain(e);
        createOutput(e);
        return true;
    }
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        return false;
    }
    if (!beginCommand(e)) {
        return false;
    }
    VkCommandBuffer cmd = e.cmd;

    VkImageMemoryBarrier toColor = swapBarrier(e.swapImages[index], VK_IMAGE_LAYOUT_UNDEFINED,
                                               VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
                                               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &toColor);

    Target output{e.output.view, e.surfaceWidth, e.surfaceHeight, e.output.image, false};
    Target swap{e.swapViews[index], e.surfaceWidth, e.surfaceHeight, e.swapImages[index], true};
    if (warp && e.output.view && e.dPresent && e.dWarp[prev]) {
        externalAcquire(cmd, e);
        PcWarp pc{{1.f / e.videoWidth, 1.f / e.videoHeight}, {0.05f, 0.14f}, {0.03f, 0.10f},
                  timestep, 0.85f};
        runPass(e, cmd, e.pWarp, e.dWarp[prev], &pc, sizeof(pc), output);
        runPass(e, cmd, e.pPresent, e.dPresent, nullptr, 0, swap);
        externalRelease(cmd, e);
    } else if (e.dRaw[raw & 1]) {
        int slot = raw & 1;
        externalAcquire(cmd, e);
        runPass(e, cmd, e.pPresent, e.dRaw[slot], nullptr, 0, swap);
        externalRelease(cmd, e);
    }

    VkImageMemoryBarrier toPresent = swapBarrier(e.swapImages[index],
                                                 VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                                                 VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                                                 VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &toPresent);
    vkEndCommandBuffer(cmd);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &e.acquireSemaphore;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &e.renderSemaphores[index];
    if (vkQueueSubmit(e.queue, 1, &submit, e.fence) != VK_SUCCESS) {
        return false;
    }
    e.fencePending = true;
    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &e.renderSemaphores[index];
    present.swapchainCount = 1;
    present.pSwapchains = &e.swapchain;
    present.pImageIndices = &index;
    VkResult result = vkQueuePresentKHR(e.queue, &present);
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        destroySwapchain(e);
        createSwapchain(e);
        createOutput(e);
    }
    return true;
}

// ---------------------------------------------------------------------------
// Init / teardown.

static bool createDevice(Engine& e) {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(e.instance, &count, nullptr) != VK_SUCCESS || !count) {
        LOGE("no Vulkan device");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(e.instance, &count, devices.data());
    for (VkPhysicalDevice device : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(device, &props);
        if (props.apiVersion < VK_API_VERSION_1_3) {
            LOGI("skip %s: Vulkan %u.%u", props.deviceName,
                 VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion));
            continue;
        }
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> exts(extCount);
        vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, exts.data());
        bool swapchain = false;
        bool ahb = false;
        for (const auto& ext : exts) {
            swapchain = swapchain || !std::strcmp(ext.extensionName,
                                                  VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            ahb = ahb || !std::strcmp(
                    ext.extensionName,
                    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        }
        if (!swapchain || !ahb) {
            continue;
        }
        VkPhysicalDeviceVulkan13Features features{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        query.pNext = &features;
        vkGetPhysicalDeviceFeatures2(device, &query);
        if (!features.dynamicRendering) {
            LOGI("skip %s: no dynamic rendering", props.deviceName);
            continue;
        }
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, nullptr);
        std::vector<VkQueueFamilyProperties> queues(families);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, queues.data());
        uint32_t chosen = UINT32_MAX;
        for (uint32_t i = 0; i < families; i++) {
            if (!(queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                continue;
            }
            if (e.surface) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, e.surface, &present);
                if (!present) {
                    continue;
                }
            }
            chosen = i;
            break;
        }
        if (chosen == UINT32_MAX) {
            continue;
        }
        e.physical = device;
        e.queueFamily = chosen;
        float priority = 1.f;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = chosen;
        qi.queueCount = 1;
        qi.pQueuePriorities = &priority;
        const char* enabled[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                                 VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME};
        VkPhysicalDeviceVulkan13Features enabledFeatures{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        enabledFeatures.dynamicRendering = VK_TRUE;
        VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        ci.pNext = &enabledFeatures;
        ci.queueCreateInfoCount = 1;
        ci.pQueueCreateInfos = &qi;
        ci.enabledExtensionCount = 2;
        ci.ppEnabledExtensionNames = enabled;
        if (vkCreateDevice(device, &ci, nullptr, &e.device) != VK_SUCCESS) {
            e.device = VK_NULL_HANDLE;
            continue;
        }
        vkGetDeviceQueue(e.device, chosen, 0, &e.queue);
        LOGI("device %s, API %u.%u.%u, driver %u", props.deviceName,
             VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
             VK_VERSION_PATCH(props.apiVersion), props.driverVersion);
        return true;
    }
    LOGE("no Vulkan 1.3 device with AHB + swapchain");
    return false;
}

static bool createInstance(VkInstance* instance) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "anicli";
    app.apiVersion = VK_API_VERSION_1_3;
    const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = extensions;
    return vkCreateInstance(&ci, nullptr, instance) == VK_SUCCESS;
}

static void destroyEngine(Engine* e) {
    if (!e) {
        return;
    }
    if (e->device) {
        vkDeviceWaitIdle(e->device);
    }
    destroySwapchain(*e);
    for (int i = 0; i < 2; i++) {
        destroyImage(*e, e->frames[i]);
        destroyImage(*e, e->luma[i]);
    }
    Image* others[] = {&e->search, &e->blocks, &e->pick, &e->flow, &e->tiles,
                       &e->pixelFlow, &e->pixelSad, &e->prevTile, &e->prevGlobal, &e->output};
    for (Image* image : others) {
        destroyImage(*e, *image);
    }
    destroyDescriptors(*e);
    if (e->presentPool) {
        vkDestroyDescriptorPool(e->device, e->presentPool, nullptr);
        e->presentPool = VK_NULL_HANDLE;
    }
    Pass* passes[] = {&e->pLuma, &e->pSearch, &e->pBlock, &e->pPick, &e->pRefine,
                      &e->pTile, &e->pPixelFlow, &e->pPixelSad, &e->pWarp, &e->pCopy,
                      &e->pPresent};
    for (Pass* pass : passes) {
        if (pass->pipeline) {
            vkDestroyPipeline(e->device, pass->pipeline, nullptr);
        }
        if (pass->layout) {
            vkDestroyPipelineLayout(e->device, pass->layout, nullptr);
        }
        if (pass->setLayout) {
            vkDestroyDescriptorSetLayout(e->device, pass->setLayout, nullptr);
        }
    }
    if (e->fence) {
        vkDestroyFence(e->device, e->fence, nullptr);
    }
    if (e->sampler) {
        vkDestroySampler(e->device, e->sampler, nullptr);
    }
    if (e->acquireSemaphore) {
        vkDestroySemaphore(e->device, e->acquireSemaphore, nullptr);
    }
    if (e->cmdPool) {
        vkDestroyCommandPool(e->device, e->cmdPool, nullptr);
    }
    if (e->surface) {
        vkDestroySurfaceKHR(e->instance, e->surface, nullptr);
    }
    if (e->window) {
        ANativeWindow_release(e->window);
    }
    if (e->device) {
        vkDestroyDevice(e->device, nullptr);
    }
    if (e->instance) {
        vkDestroyInstance(e->instance, nullptr);
    }
    delete e;
}

static bool setupPasses(Engine& e) {
    e.pLuma = createPass(e, "luma", {0}, sizeof(PcLuma), RGBA);
    e.pSearch = createPass(e, "search", {0, 1, 2}, sizeof(PcSearch), RGBA);
    e.pBlock = createPass(e, "block", {0}, 0, RGBA);
    e.pPick = createPass(e, "pick", {0}, sizeof(PcPick), RGBA);
    e.pRefine = createPass(e, "refine", {0, 1, 2}, sizeof(PcRefine), RGBA);
    e.pTile = createPass(e, "tile", {0, 1, 2, 3, 4}, sizeof(PcTile), RGBA);
    e.pPixelFlow = createPass(e, "pixel_flow", {0, 1, 2}, sizeof(PcPixelFlow), RGBA);
    e.pPixelSad = createPass(e, "pixel_sad", {0, 1, 2}, sizeof(PcPixelSad), RGBA);
    e.pWarp = createPass(e, "warp", {0, 1, 2, 3}, sizeof(PcWarp), RGBA);
    e.pCopy = createPass(e, "copy", {0}, 0, RGBA);
    if (!createPresentPass(e)) {
        return false;
    }
    return e.pLuma.pipeline && e.pSearch.pipeline && e.pBlock.pipeline && e.pPick.pipeline
            && e.pRefine.pipeline && e.pTile.pipeline && e.pPixelFlow.pipeline
            && e.pPixelSad.pipeline && e.pWarp.pipeline && e.pCopy.pipeline;
}

static Engine* createEngine() {
    if (!loadVulkan()) {
        return nullptr;
    }
    auto* e = new Engine();
    if (!createInstance(&e->instance)) {
        LOGE("instance failed");
        delete e;
        return nullptr;
    }
#define VK_LOAD_INSTANCE(name) \
    name = reinterpret_cast<PFN_##name>(gipa(e->instance, #name));
    VK_INSTANCE_FUNCS(VK_LOAD_INSTANCE)
#undef VK_LOAD_INSTANCE
    if (!createDevice(*e)) {
        destroyEngine(e);
        return nullptr;
    }
#define VK_LOAD_DEVICE(name) \
    name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(e->device, #name));
    VK_DEVICE_FUNCS(VK_LOAD_DEVICE)
#undef VK_LOAD_DEVICE

    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler.magFilter = VK_FILTER_LINEAR;
    sampler.minFilter = VK_FILTER_LINEAR;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(e->device, &sampler, nullptr, &e->sampler) != VK_SUCCESS) {
        destroyEngine(e);
        return nullptr;
    }
    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool.queueFamilyIndex = e->queueFamily;
    vkCreateCommandPool(e->device, &pool, nullptr, &e->cmdPool);
    VkCommandBufferAllocateInfo cb{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cb.commandPool = e->cmdPool;
    cb.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cb.commandBufferCount = 1;
    vkAllocateCommandBuffers(e->device, &cb, &e->cmd);
    VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateFence(e->device, &fence, nullptr, &e->fence);
    VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    vkCreateSemaphore(e->device, &semaphore, nullptr, &e->acquireSemaphore);
    return e;
}

// ---------------------------------------------------------------------------
// JNI.

static void addShader(JNIEnv* env, Engine& e, jstring name, jbyteArray code) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    jsize size = env->GetArrayLength(code);
    std::vector<uint32_t> data(static_cast<size_t>(size) / 4);
    env->GetByteArrayRegion(code, 0, size, reinterpret_cast<jbyte*>(data.data()));
    e.shaders[chars] = std::move(data);
    env->ReleaseStringUTFChars(name, chars);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_nativeSupported(JNIEnv*, jclass) {
    if (!loadVulkan()) {
        return JNI_FALSE;
    }
    uint32_t version = 0;
    vkEnumerateInstanceVersion(&version);
    if (version < VK_API_VERSION_1_3) {
        LOGI("Vulkan %u.%u < 1.3, frame gen stays on GL",
             VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version));
        return JNI_FALSE;
    }
    VkInstance instance = VK_NULL_HANDLE;
    if (!createInstance(&instance)) {
        return JNI_FALSE;
    }
#define VK_LOAD_INSTANCE(name) \
    name = reinterpret_cast<PFN_##name>(gipa(instance, #name));
    VK_INSTANCE_FUNCS(VK_LOAD_INSTANCE)
#undef VK_LOAD_INSTANCE
    if (!vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties
            || !vkEnumerateDeviceExtensionProperties) {
        LOGE("missing Vulkan instance entry points");
        if (vkDestroyInstance) {
            vkDestroyInstance(instance, nullptr);
        }
        return JNI_FALSE;
    }
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    bool ok = false;
    for (VkPhysicalDevice device : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(device, &props);
        if (props.apiVersion < VK_API_VERSION_1_3) {
            continue;
        }
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> exts(extCount);
        vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, exts.data());
        bool swapchain = false;
        bool ahb = false;
        for (const auto& ext : exts) {
            swapchain = swapchain || !std::strcmp(ext.extensionName,
                                                  VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            ahb = ahb || !std::strcmp(
                    ext.extensionName,
                    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        }
        ok = swapchain && ahb;
        if (ok) {
            break;
        }
    }
    if (vkDestroyInstance) {
        vkDestroyInstance(instance, nullptr);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_create(
        JNIEnv* env, jclass, jobjectArray names, jobjectArray code) {
    if (gEngine) {
        destroyEngine(gEngine);
        gEngine = nullptr;
    }
    Engine* e = createEngine();
    if (!e) {
        return 0;
    }
    const jsize count = env->GetArrayLength(names);
    for (jsize i = 0; i < count; i++) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(code, i));
        addShader(env, *e, name, data);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(data);
    }
    if (!setupPasses(*e)) {
        LOGE("pipeline setup failed");
        destroyEngine(e);
        return 0;
    }
    gEngine = e;
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_resize(
        JNIEnv*, jclass, jlong handle, jint width, jint height) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || width <= 0 || height <= 0) {
        return 0;
    }
    if (e->videoWidth == width && e->videoHeight == height && e->frames[0].image) {
        return 2;
    }
    e->videoWidth = width;
    e->videoHeight = height;
    if (!createVideoResources(*e)) {
        LOGE("video resources failed");
        return 0;
    }
    return 2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_slotTexture(
        JNIEnv*, jclass, jlong handle, jint index) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || index < 0 || index > 1) {
        return 0;
    }
    return static_cast<jint>(e->frames[index].glTexture);
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_setSurface(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->device) {
        return;
    }
    vkDeviceWaitIdle(e->device);
    destroySwapchain(*e);
    if (e->surface) {
        vkDestroySurfaceKHR(e->instance, e->surface, nullptr);
        e->surface = VK_NULL_HANDLE;
    }
    if (e->window) {
        ANativeWindow_release(e->window);
        e->window = nullptr;
    }
    if (!surface) {
        return;
    }
    e->window = ANativeWindow_fromSurface(env, surface);
    e->surfaceWidth = width;
    e->surfaceHeight = height;
    if (!e->window) {
        return;
    }
    VkAndroidSurfaceCreateInfoKHR ci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    ci.window = e->window;
    if (vkCreateAndroidSurfaceKHR(e->instance, &ci, nullptr, &e->surface) != VK_SUCCESS) {
        LOGE("VkSurface failed");
        return;
    }
    if (createSwapchain(*e)) {
        createOutput(*e);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_sync(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e) {
        syncFence(*e);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_flow(
        JNIEnv*, jclass, jlong handle, jint prev, jint next) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e) {
        runFlow(*e, prev & 1, next & 1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_warp(
        JNIEnv*, jclass, jlong handle, jint prev, jint next, jfloat timestep) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e) {
        drawFrame(*e, true, prev & 1, next & 1, timestep, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_raw(
        JNIEnv*, jclass, jlong handle, jint index) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e) {
        drawFrame(*e, false, 0, 0, 0.f, index & 1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_VulkanFrameGen_destroy(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) {
        return;
    }
    if (gEngine == e) {
        gEngine = nullptr;
    }
    destroyEngine(e);
}
