package com.minhmc2077.aniclijava.framegen;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.InputStream;

/** JNI bridge to the Vulkan 1.3 frame generator. */
final class VulkanFrameGen {
    private static final String TAG = "framegen";
    private static final boolean LOADED;
    private static final String[] SHADERS = {
            "full", "luma", "search", "block", "pick", "refine", "tile",
            "pixel_flow", "pixel_sad", "warp", "copy", "present"
    };

    static {
        boolean loaded;
        try {
            System.loadLibrary("framegen_vk");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private VulkanFrameGen() {
    }

    static boolean supported() {
        return LOADED && nativeSupported();
    }

    static long open(Context context) {
        String[] names = new String[SHADERS.length];
        byte[][] code = new byte[SHADERS.length][];
        try {
            for (int i = 0; i < SHADERS.length; i++) {
                names[i] = SHADERS[i];
                try (InputStream in = context.getAssets()
                        .open("shaders/" + SHADERS[i] + ".spv")) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] block = new byte[8192];
                    for (int read; (read = in.read(block)) > 0; ) {
                        out.write(block, 0, read);
                    }
                    code[i] = out.toByteArray();
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "shader asset missing: " + error.getMessage());
            return 0;
        }
        return create(names, code);
    }

    private static native boolean nativeSupported();

    private static native long create(String[] names, byte[][] code);

    static native int resize(long handle, int width, int height);

    static native int slotTexture(long handle, int index);

    static native void setSurface(long handle, Surface surface, int width, int height);

    static native void sync(long handle);

    static native void flow(long handle, int prev, int next);

    static native void warp(long handle, int prev, int next, float timestep);

    static native void raw(long handle, int index);

    static native void destroy(long handle);
}
