package com.minhmc2077.aniclijava.framegen;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * JNI bridge to the bundled ncnn frame-generation engines.
 *
 * FastFlowNet (1.37M params, 3.3 MB fp16 weights) estimates bidirectional
 * flow that feeds the existing GL occlusion-aware warp/blend. The model
 * ships as a standard ncnn graph - no custom layers - from assets/framegen.
 */
public final class NcnnFrameEngine {

    private static final String PARAM_ASSET = "framegen/ffn.ncnn.param";
    private static final String BIN_ASSET = "framegen/ffn.ncnn.bin";

    /** True if the native library + all model assets loaded successfully. */
    public final boolean available;

    public NcnnFrameEngine(Context context) {
        boolean ok = false;
        try {
            System.loadLibrary("framegen_ncnn");
            ok = nativeInit(context.getAssets());
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
            // Missing native lib (unsupported ABI) or assets: engine stays
            // unavailable and the controller keeps using the GL path.
        }
        available = ok;
    }

    /**
     * Cheap check for the settings UI: the engine is only offered when both
     * model files are in the APK. Loading the net is left to playback.
     */
    public static boolean modelBundled(Context context) {
        return isAsset(context, PARAM_ASSET) && isAsset(context, BIN_ASSET);
    }

    private static boolean isAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            return in.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Bidirectional FastFlowNet flow on the quarter-luma grid.
     *
     * @param rgbaA   previous frame, RGBA, w*h*4
     * @param rgbaC   next frame, RGBA, w*h*4
     * @param outFlow (w/4)*(h/4)*2 floats: per grid point (fx, fy) in
     *                FULL-resolution pixels (the same convention the GL
     *                warp shader expects after encoding)
     * @param outSad  (w/4)*(h/4) floats: alignment residual 0-1, feeding
     *                the GL pixelSad texture
     * @return true on success
     */
    public boolean ffnFlow(ByteBuffer rgbaA, ByteBuffer rgbaC, int w, int h,
                           float[] outFlow, float[] outSad) {
        if (!available) {
            return false;
        }
        return nativeFfnFlow(rgbaA, rgbaC, w, h, outFlow, outSad);
    }

    public void close() {
        if (available) {
            nativeClose();
        }
    }

    private static native boolean nativeInit(android.content.res.AssetManager assets);

    private static native boolean nativeFfnFlow(ByteBuffer rgbaA, ByteBuffer rgbaC,
                                                int w, int h, float[] outFlow,
                                                float[] outSad);

    private static native void nativeClose();
}
