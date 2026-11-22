package com.minhmc2077.aniclijava.framegen;

import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/** Frame generation presenter: Vulkan 1.3 first, GLES3 when the device lacks it. */
public interface FrameGenController {
    interface Listener {
        void onInputSurface(@NonNull Surface surface);

        void onFailure(@NonNull String reason);
    }

    void start();

    void setPaused(boolean value);

    void setTargetFps(int fps);

    void release();

    void setVideoSize(int width, int height);

    static FrameGenController create(@NonNull SurfaceView view, @NonNull Listener listener) {
        if (VulkanFrameGen.supported()) {
            return new VulkanFrameGenController(view, listener);
        }
        return new GlFrameGenController(view, listener);
    }
}
