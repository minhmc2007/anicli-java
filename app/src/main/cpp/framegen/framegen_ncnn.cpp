// FastFlowNet flow for the GL warp engine. Input is a 6-channel BGR pair;
// output is quarter-luma flow in video pixels plus a SAD residual.
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "gpu.h"
#include "net.h"
#include "datareader.h"

namespace {

// Long-side cap for the network input; full-res saturates mobile GPUs.
const int MAX_NET_SIDE = 640;

float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Bilinear sample a single-channel float image.
float sampleBilinear(const float* img, int w, int h, float x, float y) {
    x = clampf(x, 0.f, (float)w - 1);
    y = clampf(y, 0.f, (float)h - 1);
    const int x0 = (int)x, y0 = (int)y;
    const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float fx = x - x0, fy = y - y0;
    const float v00 = img[(size_t)y0 * w + x0], v10 = img[(size_t)y0 * w + x1];
    const float v01 = img[(size_t)y1 * w + x0], v11 = img[(size_t)y1 * w + x1];
    return v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
           v01 * (1 - fx) * fy + v11 * fx * fy;
}

// Bilinear BGR sample from interleaved RGBA.
float sampleRgba(const unsigned char* img, int w, int h, int channel,
                 float x, float y) {
    x = clampf(x, 0.f, (float)w - 1);
    y = clampf(y, 0.f, (float)h - 1);
    const int x0 = (int)x, y0 = (int)y;
    const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float fx = x - x0, fy = y - y0;
    const size_t s = (size_t)y0 * w * 4 + (size_t)x0 * 4 + channel;
    const size_t o = (size_t)(x1 - x0) * 4;
    const size_t p = (size_t)(y1 - y0) * w * 4;
    const float v00 = img[s], v10 = img[s + o];
    const float v01 = img[s + p], v11 = img[s + o + p];
    return (v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
            v01 * (1 - fx) * fy + v11 * fx * fy) / 255.f;
}

// Bilinear luma (0-1) from interleaved RGBA.
float sampleLuma(const unsigned char* img, int w, int h, float x, float y) {
    return 0.299f * sampleRgba(img, w, h, 0, x, y) +
           0.587f * sampleRgba(img, w, h, 1, x, y) +
           0.114f * sampleRgba(img, w, h, 2, x, y);
}

// First Input blob and final top blob; pnnx may omit an Output layer.
void paramBlobNames(const std::vector<unsigned char>& param,
                    std::vector<std::string>& ins, std::string& outName) {
    std::string text((const char*)param.data(), param.size());
    size_t pos = 0;
    int lines = 0;
    std::string lastTop;
    while (pos < text.size()) {
        size_t eol = text.find('\n', pos);
        if (eol == std::string::npos) eol = text.size();
        std::string line = text.substr(pos, eol - pos);
        pos = eol + 1;
        if (++lines <= 2) continue;  // magic + layer count
        std::vector<std::string> tok;
        size_t p = 0;
        while (p < line.size() && tok.size() < 8) {
            size_t s = line.find_first_not_of(" \t\r", p);
            if (s == std::string::npos) break;
            size_t e = line.find_first_of(" \t\r", s);
            if (e == std::string::npos) e = line.size();
            tok.push_back(line.substr(s, e - s));
            p = e;
        }
        if (tok.size() < 4) continue;
        const std::string& type = tok[0];
        const int bottoms = std::atoi(tok[2].c_str());
        const int tops = std::atoi(tok[3].c_str());
        if (type == "Input") {
            ins.push_back(tok[4]);
        } else if (type == "Output" && outName.empty()) {
            outName = tok.back();
        }
        if (tops > 0 && tok.size() > (size_t)(4 + bottoms)) {
            lastTop = tok[4 + bottoms];
        }
    }
    if (outName.empty()) outName = lastTop;
}

void readAsset(JNIEnv* env, jobject assetManager, const std::string& path,
               std::vector<unsigned char>& out, bool* ok) {
    if (!*ok) return;
    AAssetManager* am = AAssetManager_fromJava(env, assetManager);
    if (!am) {
        *ok = false;
        return;
    }
    AAsset* asset = AAssetManager_open(am, path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        *ok = false;
        return;
    }
    const size_t size = (size_t)AAsset_getLength(asset);
    out.resize(size);
    memcpy(out.data(), AAsset_getBuffer(asset), size);
    AAsset_close(asset);
}

class FrameGenEngine {
public:
    bool init(JNIEnv* env, jobject assetManager) {
        if (inited) return true;
        bool ok = true;
        readAsset(env, assetManager, "framegen/ffn.ncnn.param", ffnParam, &ok);
        readAsset(env, assetManager, "framegen/ffn.ncnn.bin", ffnBin, &ok);
        if (!ok) return false;
        std::vector<std::string> ffnIns;
        paramBlobNames(ffnParam, ffnIns, ffnOut);
        if (ffnIns.empty() || ffnOut.empty()) return false;
        ffnIn = ffnIns[0];
        ffnNet.opt.num_threads = 2;
        const ncnn::VulkanDevice* vkdev =
            ncnn::get_gpu_count() > 0 ? ncnn::get_gpu_device(0) : 0;
        if (!vkdev || !vkdev->is_valid()) {
            __android_log_print(ANDROID_LOG_ERROR, "framegen",
                                "FastFlowNet: no Vulkan device");
            return false;
        }
        ffnNet.set_vulkan_device(vkdev);
        ffnNet.opt.use_vulkan_compute = true;
        // Match the RIFE engine: fp16 storage on, fp16 arithmetic off.
        ffnNet.opt.use_fp16_arithmetic = false;
        device = ncnn::get_gpu_info(0).device_name();
        __android_log_print(ANDROID_LOG_INFO, "framegen",
                            "FastFlowNet on Vulkan: %s", device.c_str());
        const unsigned char* ffp = ffnParam.data();
        const unsigned char* ffb = ffnBin.data();
        ncnn::DataReaderFromMemory ffnParamReader(ffp, ffnParam.size());
        ncnn::DataReaderFromMemory ffnBinReader(ffb, ffnBin.size());
        auto mark = std::chrono::steady_clock::now();
        if (ffnNet.load_param(ffnParamReader)) return fail();
        const double paramMs = std::chrono::duration<double, std::milli>(
                                   std::chrono::steady_clock::now() - mark)
                                   .count();
        mark = std::chrono::steady_clock::now();
        if (ffnNet.load_model(ffnBinReader)) return fail();
        const double modelMs = std::chrono::duration<double, std::milli>(
                                   std::chrono::steady_clock::now() - mark)
                                   .count();
        __android_log_print(ANDROID_LOG_INFO, "framegen",
                            "load param %.1f ms, model %.1f ms, %zu layers",
                            paramMs, modelMs, ffnNet.layers().size());
        inited = true;
        return true;
    }

    bool ready() const { return inited; }

    void shutdown() {
        if (!inited) return;
        ffnNet.clear();
        inited = false;
    }

    // Flow on the quarter-luma grid (video px) and SAD residual (0-1).
    bool ffnFlow(const unsigned char* rgbaA, const unsigned char* rgbaC,
                 int w, int h, std::vector<float>& flow,
                 std::vector<float>& sad) {
        const auto started = std::chrono::steady_clock::now();
        // Network input: capped long side, padded to the graph's 64 stride.
        const int longSide = std::max(w, h);
        int nw = w, nh = h;
        if (longSide > MAX_NET_SIDE) {            nw = std::max(1, (int)std::lround((double)w * MAX_NET_SIDE / longSide));
            nh = std::max(1, (int)std::lround((double)h * MAX_NET_SIDE / longSide));
        }
        const int iw = (nw + 63) / 64 * 64, ih = (nh + 63) / 64 * 64;
        // Pair mean per BGR channel, as in demo.py.
        double sum[3] = {0., 0., 0.};
        for (const unsigned char* img : {rgbaA, rgbaC}) {
            const size_t n = (size_t)w * h;
            for (size_t i = 0; i < n; i++) {
                for (int c = 0; c < 3; c++) sum[c] += img[i * 4 + (2 - c)];
            }
        }
        float mean[3];
        for (int c = 0; c < 3; c++) {
            mean[c] = (float)(sum[c] / (2.0 * w * h) / 255.0);
        }
        ncnn::Mat in;
        std::vector<float> fa;
        pairToPlanar(rgbaA, rgbaC, w, h, iw, ih, mean, in);
        if (!runFfn(in, fa)) return false;
        const int gw = iw / 4, gh = ih / 4;
        const int lumaW = w / 4, lumaH = h / 4;
        if (fa.size() != (size_t)gw * gh * 2) return false;
        const float kx = (float)w / iw, ky = (float)h / ih;
        // Split into x/y planes for bilinear resampling.
        std::vector<float> fx((size_t)gw * gh), fy((size_t)gw * gh);
        for (size_t i = 0; i < (size_t)gw * gh; i++) {
            fx[i] = fa[i * 2];
            fy[i] = fa[i * 2 + 1];
        }
        // Resample to the luma grid and rescale network pixels to video pixels.
        flow.resize((size_t)lumaW * lumaH * 2);
        for (int y = 0; y < lumaH; y++) {
            const float sy = lumaH == 1 ? 0.f
                                        : (float)y * (gh - 1) / (lumaH - 1);
            for (int x = 0; x < lumaW; x++) {
                const float sx = lumaW == 1 ? 0.f
                                            : (float)x * (gw - 1) / (lumaW - 1);
                float* dst = &flow[((size_t)y * lumaW + x) * 2];
                dst[0] = sampleBilinear(fx.data(), gw, gh, sx, sy) * kx;
                dst[1] = sampleBilinear(fy.data(), gw, gh, sx, sy) * ky;
            }
        }
        // Alignment residual |lumaA(g) - lumaC(g + f)|.
        sad.resize((size_t)lumaW * lumaH);
        for (int gy = 0; gy < lumaH; gy++) {
            for (int gx = 0; gx < lumaW; gx++) {
                const float dx = flow[((size_t)gy * lumaW + gx) * 2];
                const float dy = flow[((size_t)gy * lumaW + gx) * 2 + 1];
                const float a = sampleLuma(rgbaA, w, h,
                                           gx * 4.f + dx, gy * 4.f + dy);
                const float c = sampleLuma(rgbaC, w, h, gx * 4.f, gy * 4.f);
                sad[(size_t)gy * lumaW + gx] = std::fabs(a - c);
            }
        }
        netW = iw;
        netH = ih;
        lastMs = (float)std::chrono::duration<double, std::milli>(
                     std::chrono::steady_clock::now() - started).count();
        static int runs = 0;
        if (runs < 5) {
            __android_log_print(ANDROID_LOG_INFO, "framegen",
                                "ffn %dx%d -> %dx%d: %.1f ms", w, h, iw, ih,
                                lastMs);
            runs++;
        }
        return true;
    }

    float lastInferMs() const { return lastMs; }
    int netWidth() const { return netW; }
    int netHeight() const { return netH; }
    const std::string& deviceName() const { return device; }

private:
    bool fail() {
        ffnNet.clear();
        return false;
    }

    // 6-channel BGR pair (frames in 0-2 / 3-5), mean-subtracted and sampled
    // to the padded network size.
    static void pairToPlanar(const unsigned char* first,
                             const unsigned char* second, int w, int h,
                             int outW, int outH, const float* mean,
                             ncnn::Mat& m) {
        m.create(outW, outH, 6);
        const float kx = (float)w / outW, ky = (float)h / outH;
        const bool same = outW == w && outH == h;
        for (int c = 0; c < 3; c++) {
            const int src = 2 - c;
            float* d0 = m.channel(c);
            float* d1 = m.channel(c + 3);
            for (int y = 0; y < outH; y++) {
                const float sy = same ? (float)y : (y + 0.5f) * ky - 0.5f;
                for (int x = 0; x < outW; x++) {
                    const float sx = same ? (float)x : (x + 0.5f) * kx - 0.5f;
                    const size_t i = (size_t)y * outW + x;
                    d0[i] = sampleRgba(first, w, h, src, sx, sy) - mean[c];
                    d1[i] = sampleRgba(second, w, h, src, sx, sy) - mean[c];
                }
            }
        }
    }

    bool runFfn(const ncnn::Mat& in, std::vector<float>& out) {
        static int calls = 0;
        const bool log = calls++ < 10;
        const auto started = std::chrono::steady_clock::now();
        if (log) {
            __android_log_print(ANDROID_LOG_INFO, "framegen",
                                "extract start %dx%dx%d", in.w, in.h, in.c);
        }
        ncnn::Extractor ex = ffnNet.create_extractor();
        ex.input(ffnIn.c_str(), in);
        ncnn::Mat flow;
        if (ex.extract(ffnOut.c_str(), flow)) {
            __android_log_print(ANDROID_LOG_ERROR, "framegen", "extract failed");
            return false;
        }
        const double ms = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::now() - started)
                              .count();
        // Raw pnnx flow * 20 (demo.py); values are in padded-input px.
        out.resize((size_t)flow.w * flow.h * 2);
        for (int y = 0; y < flow.h; y++) {
            for (int x = 0; x < flow.w; x++) {
                out[((size_t)y * flow.w + x) * 2] =
                    flow.channel(0).row(y)[x] * 20.f;
                out[((size_t)y * flow.w + x) * 2 + 1] =
                    flow.channel(1).row(y)[x] * 20.f;
            }
        }
        if (log) {
            __android_log_print(ANDROID_LOG_INFO, "framegen",
                                "extract %dx%d -> %dx%d: %.1f ms", in.w, in.h,
                                flow.w, flow.h, ms);
        }
        return true;
    }

    bool inited = false;
    ncnn::Net ffnNet;
    std::vector<unsigned char> ffnParam, ffnBin;
    std::string ffnIn, ffnOut;
    std::string device;
    float lastMs = 0.f;
    int netW = 0, netH = 0;
};

FrameGenEngine g_engine;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeInit(
    JNIEnv* env, jclass, jobject assetManager) {
    return g_engine.init(env, assetManager) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeFfnFlow(
    JNIEnv* env, jclass, jobject rgbaA, jobject rgbaC, jint w, jint h,
    jfloatArray outFlow, jfloatArray outSad) {
    if (!g_engine.ready()) return JNI_FALSE;
    auto* pa = static_cast<const unsigned char*>(
        env->GetDirectBufferAddress(rgbaA));
    auto* pc = static_cast<const unsigned char*>(
        env->GetDirectBufferAddress(rgbaC));
    const jlong need = (jlong)w * h * 4;
    if (!pa || !pc || env->GetDirectBufferCapacity(rgbaA) < need ||
        env->GetDirectBufferCapacity(rgbaC) < need) {
        return JNI_FALSE;
    }
    const int lumaW = w / 4, lumaH = h / 4;
    std::vector<float> flow((size_t)lumaW * lumaH * 2);
    std::vector<float> sad((size_t)lumaW * lumaH);
    const bool ok = g_engine.ffnFlow(pa, pc, w, h, flow, sad);
    if (!ok) return JNI_FALSE;
    if ((jsize)flow.size() != env->GetArrayLength(outFlow) ||
        (jsize)sad.size() != env->GetArrayLength(outSad)) {
        return JNI_FALSE;
    }
    env->SetFloatArrayRegion(outFlow, 0, (jsize)flow.size(), flow.data());
    env->SetFloatArrayRegion(outSad, 0, (jsize)sad.size(), sad.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeLastInferMs(
    JNIEnv*, jclass) {
    return g_engine.lastInferMs();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeNetSize(
    JNIEnv* env, jclass) {
    jintArray out = env->NewIntArray(2);
    if (out) {
        const jint size[2] = {g_engine.netWidth(), g_engine.netHeight()};
        env->SetIntArrayRegion(out, 0, 2, size);
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeDeviceName(
    JNIEnv* env, jclass) {
    return env->NewStringUTF(g_engine.deviceName().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeClose(
    JNIEnv*, jclass) {
    g_engine.shutdown();
}
