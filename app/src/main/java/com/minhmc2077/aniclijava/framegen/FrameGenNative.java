package com.minhmc2077.aniclijava.framegen;

import java.nio.ByteBuffer;

/** JNI surface for the bundled RIFE 4.6 model. */
public final class FrameGenNative {
    private static volatile boolean resolved;
    private static volatile boolean loaded;

    private FrameGenNative() {
    }

    public static synchronized boolean load() {
        if (!resolved) {
            try {
                System.loadLibrary("framegen");
                loaded = true;
            } catch (UnsatisfiedLinkError error) {
                loaded = false;
            }
            resolved = true;
        }
        return loaded;
    }

    public static boolean isSupported() {
        return load() && nativeGpuCount() > 0;
    }

    static native int nativeGpuCount();

    static native boolean nativeInit(String modelDir);

    static native void nativeRelease();

    static native boolean nativeInterpolate(ByteBuffer frame0, ByteBuffer frame1,
                                            ByteBuffer output, int width, int height,
                                            float timestep);
}
