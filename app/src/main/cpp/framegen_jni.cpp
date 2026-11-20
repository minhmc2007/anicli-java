#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <mutex>
#include <string>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#include "gpu.h"
#include "rife.h"

#define LOG_TAG "AniCliFrameGen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
RIFE* g_rife = nullptr;

void rgbaToRgb(const uint8_t* src, uint8_t* dst, size_t pixels) {
    size_t i = 0;
#if defined(__aarch64__)
    for (; i + 16 <= pixels; i += 16) {
        uint8x16x4_t rgba = vld4q_u8(src + i * 4);
        uint8x16x3_t rgb;
        rgb.val[0] = rgba.val[0];
        rgb.val[1] = rgba.val[1];
        rgb.val[2] = rgba.val[2];
        vst3q_u8(dst + i * 3, rgb);
    }
#endif
    for (; i < pixels; ++i) {
        dst[i * 3] = src[i * 4];
        dst[i * 3 + 1] = src[i * 4 + 1];
        dst[i * 3 + 2] = src[i * 4 + 2];
    }
}

inline size_t alignedRgbBytes(size_t pixels) {
    return (pixels * 3 + 15) & ~static_cast<size_t>(15);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_minhmc2077_aniclijava_framegen_FrameGenNative_nativeGpuCount(
        JNIEnv*, jclass) {
    return ncnn::get_gpu_count();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_FrameGenNative_nativeInit(
        JNIEnv* env, jclass, jstring modelDir) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_rife != nullptr) {
        return JNI_TRUE;
    }
    if (ncnn::get_gpu_count() <= 0) {
        LOGE("no Vulkan device available");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(modelDir, nullptr);
    if (path == nullptr) {
        return JNI_FALSE;
    }
    std::string directory(path);
    env->ReleaseStringUTFChars(modelDir, path);

    RIFE* rife = new RIFE(/*gpuid=*/0,
                         /*tta_mode=*/false,
                         /*tta_temporal_mode=*/false,
                         /*uhd_mode=*/false,
                         /*num_threads=*/1,
                         /*rife_v2=*/false,
                         /*rife_v4=*/true);
    int result = rife->load(directory);
    if (result != 0) {
        LOGE("rife load failed: %d", result);
        delete rife;
        return JNI_FALSE;
    }
    g_rife = rife;
    LOGI("rife 4.6 ready (%s)", directory.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_FrameGenNative_nativeRelease(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(g_mutex);
    delete g_rife;
    g_rife = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_FrameGenNative_nativePackRgb(
        JNIEnv* env, jclass, jobject rgba, jobject rgb, jint width, jint height) {
    if (width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgba));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgb));
    size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (src == nullptr || dst == nullptr
            || env->GetDirectBufferCapacity(rgba) < static_cast<jlong>(pixels * 4)
            || env->GetDirectBufferCapacity(rgb) < static_cast<jlong>(pixels * 3)) {
        return JNI_FALSE;
    }
    rgbaToRgb(src, dst, pixels);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_FrameGenNative_nativeInterpolate(
        JNIEnv* env, jclass, jobject frame0, jobject frame1, jobject output,
        jint width, jint height, jfloat timestep) {
    if (width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    auto* src0 = static_cast<uint8_t*>(env->GetDirectBufferAddress(frame0));
    auto* src1 = static_cast<uint8_t*>(env->GetDirectBufferAddress(frame1));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    if (src0 == nullptr || src1 == nullptr || dst == nullptr) {
        return JNI_FALSE;
    }
    size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    size_t bytes = alignedRgbBytes(pixels);
    if (env->GetDirectBufferCapacity(frame0) < static_cast<jlong>(bytes)
            || env->GetDirectBufferCapacity(frame1) < static_cast<jlong>(bytes)
            || env->GetDirectBufferCapacity(output) < static_cast<jlong>(bytes)) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_rife == nullptr) {
        return JNI_FALSE;
    }

    ncnn::Mat in0(width, height, src0, static_cast<size_t>(3), 1);
    ncnn::Mat in1(width, height, src1, static_cast<size_t>(3), 1);
    ncnn::Mat out(width, height, dst, static_cast<size_t>(3), 1);

    return g_rife->process_v4(in0, in1, timestep, out) == 0 ? JNI_TRUE : JNI_FALSE;
}
