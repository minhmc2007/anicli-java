// framegen_ncnn.cpp - neural frame-generation engines for anicli-java.
//
// FastFlowNet (1.37M params): estimates bidirectional flow A->C / C->A,
// combines symmetrically f = (f_AC - f_CA)/2 (the benchmark's +4.9 dB win),
// and returns it on the quarter-luma grid in FULL-RES pixel units plus a
// per-grid-point alignment residual (SAD 0-1) for the occlusion fallback.
// The existing GL warp/blend shader consumes both unchanged.
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "gpu.h"
#include "net.h"
#include "datareader.h"

namespace {

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

// Bilinear sample one BGR channel from an interleaved RGBA byte image.
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

// Parse the first Input blob name and the final output blob name from a
// pnnx-generated .param. Some exports have no explicit Output layer, so fall
// back to the last layer's first top blob.
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
        // Samsung Xclipse miscompiles fp16 arithmetic shaders (same workaround
        // as the RIFE engine); fp16 storage stays on.
        ffnNet.opt.use_fp16_arithmetic = false;
        __android_log_print(ANDROID_LOG_INFO, "framegen",
                            "FastFlowNet on Vulkan: %s",
                            ncnn::get_gpu_info(0).device_name());
        const unsigned char* ffp = ffnParam.data();
        const unsigned char* ffb = ffnBin.data();
        ncnn::DataReaderFromMemory ffnParamReader(ffp, ffnParam.size());
        ncnn::DataReaderFromMemory ffnBinReader(ffb, ffnBin.size());
        if (ffnNet.load_param(ffnParamReader)) return fail();
        if (ffnNet.load_model(ffnBinReader)) return fail();
        inited = true;
        return true;
    }

    bool ready() const { return inited; }

    void shutdown() {
        if (!inited) return;
        ffnNet.clear();
        inited = false;
    }

    // FFN: rgbaA/rgbaC (w*h*4) -> flow ((w/4)*(h/4)*2, FULL-res px units,
    // sampled on the quarter-luma grid) and sad ((w/4)*(h/4), 0-1 residual).
    bool ffnFlow(const unsigned char* rgbaA, const unsigned char* rgbaC,
                 int w, int h, std::vector<float>& flow,
                 std::vector<float>& sad) {
        const int ih = (h + 63) / 64 * 64, iw = (w + 63) / 64 * 64;
        // Per-channel mean over the concatenated pair in BGR order, matching
        // the channel layout fed to the network (demo.py centralize).
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
        std::vector<float> fa, fc;  // padded/4 grid, padded-px units
        pairToPlanar(rgbaA, rgbaC, w, h, iw, ih, mean, in);
        if (!runFfn(in, fa)) return false;
        pairToPlanar(rgbaC, rgbaA, w, h, iw, ih, mean, in);
        if (!runFfn(in, fc)) return false;
        const int gw = iw / 4, gh = ih / 4;
        const int lumaW = w / 4, lumaH = h / 4;
        const size_t gridElems = (size_t)gw * gh * 2;
        if (fa.size() != gridElems || fc.size() != gridElems) return false;
        const float kx = (float)w / iw, ky = (float)h / ih;
        // De-interleave into x/y planes so grid resampling is a plain
        // bilinear lookup.
        std::vector<float> fax((size_t)gw * gh), fay((size_t)gw * gh);
        std::vector<float> fcx((size_t)gw * gh), fcy((size_t)gw * gh);
        for (size_t i = 0; i < (size_t)gw * gh; i++) {
            fax[i] = fa[i * 2];
            fay[i] = fa[i * 2 + 1];
            fcx[i] = fc[i * 2];
            fcy[i] = fc[i * 2 + 1];
        }
        // Symmetric combination on the padded grid, resampled to the luma
        // grid, rescaled from padded pixels to original pixels.
        flow.resize((size_t)lumaW * lumaH * 2);
        for (int y = 0; y < lumaH; y++) {
            const float sy = lumaH == 1 ? 0.f
                                        : (float)y * (gh - 1) / (lumaH - 1);
            for (int x = 0; x < lumaW; x++) {
                const float sx = lumaW == 1 ? 0.f
                                            : (float)x * (gw - 1) / (lumaW - 1);
                float* dst = &flow[((size_t)y * lumaW + x) * 2];
                dst[0] =
                    0.5f *
                    (sampleBilinear(fax.data(), gw, gh, sx, sy) -
                     sampleBilinear(fcx.data(), gw, gh, sx, sy)) * kx;
                dst[1] =
                    0.5f *
                    (sampleBilinear(fay.data(), gw, gh, sx, sy) -
                     sampleBilinear(fcy.data(), gw, gh, sx, sy)) * ky;
            }
        }
        // Alignment residual at grid points: |lumaA(g) - lumaC(g + f)|.
        std::vector<float> la((size_t)w * h), lc((size_t)w * h);
        lumaOf(rgbaA, w, h, la.data());
        lumaOf(rgbaC, w, h, lc.data());
        sad.resize((size_t)lumaW * lumaH);
        for (int gy = 0; gy < lumaH; gy++) {
            for (int gx = 0; gx < lumaW; gx++) {
                const float fx = flow[((size_t)gy * lumaW + gx) * 2];
                const float fy = flow[((size_t)gy * lumaW + gx) * 2 + 1];
                const float la2 = sampleBilinear(la.data(), w, h,
                                                 gx * 4.f + fx, gy * 4.f + fy);
                const float lc2 = lc[(size_t)(gy * 4) * w + gx * 4];
                sad[(size_t)gy * lumaW + gx] = std::fabs(la2 - lc2) / 255.f;
            }
        }
        return true;
    }

private:
    bool fail() {
        ffnNet.clear();
        return false;
    }

    // RGBA pair -> 6-channel planar float Mat in OpenCV BGR order: channels
    // 0-2 are the first frame, 3-5 the second. FastFlowNet concatenates the
    // pair along channels and subtracts the pair mean per channel
    // (demo.py centralize). Samples straight to the padded network size.
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
        ncnn::Extractor ex = ffnNet.create_extractor();
        ex.input(ffnIn.c_str(), in);
        ncnn::Mat flow;
        if (ex.extract(ffnOut.c_str(), flow)) return false;
        // pnnx graph output is the RAW flow (demo.py multiplies by 20 after
        // upsampling); grid = padded size / 4; values in padded-input px.
        out.resize((size_t)flow.w * flow.h * 2);
        for (int y = 0; y < flow.h; y++) {
            for (int x = 0; x < flow.w; x++) {
                out[((size_t)y * flow.w + x) * 2] =
                    flow.channel(0).row(y)[x] * 20.f;
                out[((size_t)y * flow.w + x) * 2 + 1] =
                    flow.channel(1).row(y)[x] * 20.f;
            }
        }
        return true;
    }

    static void lumaOf(const unsigned char* rgba, int w, int h, float* out) {
        const size_t n = (size_t)w * h;
        for (size_t i = 0; i < n; i++) {
            out[i] = 0.299f * rgba[i * 4] + 0.587f * rgba[i * 4 + 1] +
                     0.114f * rgba[i * 4 + 2];
        }
    }

    bool inited = false;
    ncnn::Net ffnNet;
    std::vector<unsigned char> ffnParam, ffnBin;
    std::string ffnIn, ffnOut;
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

extern "C" JNIEXPORT void JNICALL
Java_com_minhmc2077_aniclijava_framegen_NcnnFrameEngine_nativeClose(
    JNIEnv*, jclass) {
    g_engine.shutdown();
}
