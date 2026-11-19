package com.minhmc2077.aniclijava.framegen;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Captures ExoPlayer frames through an offscreen SurfaceTexture, interpolates with
 * RIFE, and presents to a SurfaceView. Output runs one source frame behind audio.
 */
public final class FrameGenController {
    private static final int RING = 3;
    private static final int MAX_QUALITY = 2;
    private static final long DISCONTINUITY_NS = 1_000_000_000L;
    private static final float SCENE_CUT_DIFF = 22f;
    private static final float DUPLICATE_DIFF = 1.5f;
    private static final String MODEL_DIR = "rife-v4.6";
    private static final String PARAM_ASSET = MODEL_DIR + "/flownet.param";
    private static final String BIN_ASSET = MODEL_DIR + "/flownet.bin";
    private static final long PARAM_BYTES = 16_532L;
    private static final long BIN_BYTES = 10_614_320L;

    public interface Listener {
        /** Called on the main thread with the surface ExoPlayer should render into. */
        void onReady(Surface offscreenSurface);

        /** Called on the main thread when frame generation cannot start. */
        void onUnavailable(String reason);

        /** Called on the main thread when the runtime lowered quality or the target rate. */
        void onAdjusted(int fps, int quality);
    }

    public static final class Stats {
        public volatile int targetFps;
        public volatile int outputFps;
        public volatile int processWidth;
        public volatile int processHeight;
        public volatile float inferenceMs;
        public volatile boolean interpolating;
        public volatile int quality;
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Stats stats = new Stats();
    private final HandlerThread thread;
    private final Handler handler;
    private final float displayHz;
    private final float[] stMatrix = new float[16];

    private Choreographer choreographer;
    private volatile boolean released;
    private volatile boolean playing;
    private volatile boolean interpolateEnabled = true;
    private boolean frameCallbackPosted;
    private boolean nativeReady;

    private android.opengl.EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface eglPbuffer = EGL14.EGL_NO_SURFACE;
    private android.opengl.EGLSurface eglWindow = EGL14.EGL_NO_SURFACE;
    private android.opengl.EGLConfig eglConfig;
    private int displayWidth;
    private int displayHeight;

    private SurfaceTexture surfaceTexture;
    private Surface exoSurface;
    private int oesTexture;
    private int oesProgram;
    private int texProgram;
    private int oesMatrixHandle;
    private int oesSamplerHandle;
    private int texSamplerHandle;
    private FloatBuffer quad;

    private int videoWidth;
    private int videoHeight;
    private int processWidth;
    private int processHeight;
    private int quality;
    private int effectiveFps;
    private int nextSlot;
    private final int[] frameTexture = new int[RING];
    private final int[] frameFramebuffer = new int[RING];
    private final ByteBuffer[] frameRgba = new ByteBuffer[RING];
    private final long[] framePts = new long[RING];
    private final boolean[] frameCut = new boolean[RING];
    private final boolean[] frameDuplicate = new boolean[RING];
    private int generatedTexture;
    private ByteBuffer generatedRgb;

    private long lastFramePts;
    private long intervalNs;
    private long nextPresentNs;
    private long lastFpsSampleNs;
    private long lastQualityCheckNs;
    private int presentedInSample;
    private int inferredInSample;
    private float inferenceMs;

    public FrameGenController(Context context, int requestedFps, int displayHz,
                              int initialQuality, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.displayHz = Math.max(24f, displayHz);
        this.effectiveFps = Math.max(15, Math.min(requestedFps, (int) displayHz));
        this.quality = Math.max(0, Math.min(MAX_QUALITY, initialQuality));
        this.stats.targetFps = this.effectiveFps;
        this.stats.quality = this.quality;
        this.thread = new HandlerThread("framegen");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public Stats stats() {
        return stats;
    }

    public void prepare() {
        handler.post(this::prepareInternal);
    }

    public void setDisplaySurface(Surface surface) {
        handler.post(() -> setDisplaySurfaceInternal(surface));
    }

    public void setVideoSize(int width, int height) {
        handler.post(() -> {
            if (released || width <= 0 || height <= 0) {
                return;
            }
            if (width == videoWidth && height == videoHeight) {
                return;
            }
            videoWidth = width;
            videoHeight = height;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(width, height);
            }
            recreateProcessBuffers();
        });
    }

    public void setPlaying(boolean value) {
        handler.post(() -> {
            if (released) {
                return;
            }
            playing = value;
            if (value) {
                startFrameLoop();
            } else {
                stopFrameLoop();
            }
        });
    }

    public void setInterpolateEnabled(boolean enabled) {
        interpolateEnabled = enabled;
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        handler.post(() -> {
            stopFrameLoop();
            if (nativeReady) {
                nativeReady = false;
                FrameGenNative.nativeRelease();
            }
            releaseGl();
            thread.quitSafely();
        });
    }

    private void startFrameLoop() {
        if (choreographer == null) {
            return;
        }
        if (!frameCallbackPosted) {
            frameCallbackPosted = true;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    private void stopFrameLoop() {
        if (choreographer != null && frameCallbackPosted) {
            choreographer.removeFrameCallback(frameCallback);
        }
        frameCallbackPosted = false;
        nextPresentNs = 0;
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (released || !playing) {
                frameCallbackPosted = false;
                return;
            }
            choreographer.postFrameCallback(this);
            render(frameTimeNanos);
        }
    };

    private void prepareInternal() {
        choreographer = Choreographer.getInstance();
        try {
            String modelDir = ensureModel(context).getAbsolutePath();
            if (!FrameGenNative.load()) {
                fail("library");
                return;
            }
            if (!initEgl()) {
                fail("egl");
                return;
            }
            if (!createPrograms() || !createQuad()) {
                fail("shader");
                return;
            }
            if (!createSurfaceTexture()) {
                fail("surface");
                return;
            }
            if (!FrameGenNative.nativeInit(modelDir)) {
                fail("model");
                return;
            }
            nativeReady = true;
            recreateProcessBuffers();
            Surface surface = exoSurface;
            mainHandler.post(() -> {
                if (!released) {
                    listener.onReady(surface);
                }
            });
        } catch (IOException error) {
            fail("assets");
        }
    }

    private void fail(String reason) {
        mainHandler.post(() -> {
            if (!released) {
                listener.onUnavailable(reason);
            }
        });
    }

    private static File ensureModel(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), MODEL_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("model directory");
        }
        copyAsset(context, PARAM_ASSET, new File(dir, "flownet.param"), PARAM_BYTES);
        copyAsset(context, BIN_ASSET, new File(dir, "flownet.bin"), BIN_BYTES);
        return dir;
    }

    private static void copyAsset(Context context, String asset, File target, long expected)
            throws IOException {
        if (target.length() == expected) {
            return;
        }
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream input = context.getAssets().open(asset);
             OutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
        if (temp.length() != expected || !temp.renameTo(target)) {
            temp.delete();
            throw new IOException("asset " + asset);
        }
    }

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return false;
        }
        return createEgl(EGLExt.EGL_OPENGL_ES3_BIT_KHR, 3)
                || createEgl(EGL14.EGL_OPENGL_ES2_BIT, 2);
    }

    private boolean createEgl(int renderableType, int clientVersion) {
        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        int[] configCount = new int[1];
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1,
                configCount, 0) || configCount[0] == 0) {
            return false;
        }
        eglConfig = configs[0];
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion,
                EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (eglPbuffer == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglPbuffer);
            eglPbuffer = EGL14.EGL_NO_SURFACE;
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
            return false;
        }
        return true;
    }

    private boolean createSurfaceTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTexture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable, handler);
        if (videoWidth > 0 && videoHeight > 0) {
            surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        }
        Matrix.setIdentityM(stMatrix, 0);
        exoSurface = new Surface(surfaceTexture);
        return true;
    }

    private void setDisplaySurfaceInternal(Surface surface) {
        if (released) {
            return;
        }
        if (eglWindow != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
            EGL14.eglDestroySurface(eglDisplay, eglWindow);
            eglWindow = EGL14.EGL_NO_SURFACE;
        }
        displayWidth = 0;
        displayHeight = 0;
        if (surface == null || !surface.isValid()) {
            return;
        }
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglWindow = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface,
                surfaceAttribs, 0);
        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            return;
        }
        int[] value = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_WIDTH, value, 0);
        displayWidth = value[0];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_HEIGHT, value, 0);
        displayHeight = value[0];
        if (!EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglWindow);
            eglWindow = EGL14.EGL_NO_SURFACE;
            displayWidth = 0;
            displayHeight = 0;
            EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
            return;
        }
        nextPresentNs = 0;
        renderCurrentFrame();
    }

    private void onFrameAvailable(SurfaceTexture texture) {
        if (released) {
            return;
        }
        texture.updateTexImage();
        if (!nativeReady || processWidth <= 0 || processHeight <= 0) {
            return;
        }
        texture.getTransformMatrix(stMatrix);
        long pts = texture.getTimestamp();
        if (pts == 0) {
            pts = System.nanoTime();
        }
        if (lastFramePts != 0) {
            long delta = pts - lastFramePts;
            if (delta <= 0 || delta > DISCONTINUITY_NS) {
                invalidateFrames();
                intervalNs = 0;
            } else {
                intervalNs = intervalNs == 0 ? delta
                        : (long) (intervalNs * 0.85f + delta * 0.15f);
            }
        }
        lastFramePts = pts;

        int previous = newestFrame();
        nextSlot = (nextSlot + 1) % RING;
        drawOesToFrame(nextSlot);
        readFrame(nextSlot);
        framePts[nextSlot] = pts;
        if (previous >= 0) {
            float difference = frameDifference(frameRgba[previous], frameRgba[nextSlot],
                    processWidth * processHeight);
            frameCut[nextSlot] = difference >= SCENE_CUT_DIFF;
            frameDuplicate[nextSlot] = difference <= DUPLICATE_DIFF;
        } else {
            frameCut[nextSlot] = false;
            frameDuplicate[nextSlot] = false;
        }
        if (!playing) {
            renderCurrentFrame();
        }
    }

    private static float frameDifference(ByteBuffer a, ByteBuffer b, int pixels) {
        if (a == null || b == null || pixels <= 0) {
            return 0f;
        }
        int step = Math.max(1, pixels / 4096) * 4;
        int limit = pixels * 4;
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 2 < limit; i += step) {
            sum += Math.abs((a.get(i) & 0xff) - (b.get(i) & 0xff))
                    + Math.abs((a.get(i + 1) & 0xff) - (b.get(i + 1) & 0xff))
                    + Math.abs((a.get(i + 2) & 0xff) - (b.get(i + 2) & 0xff));
            samples += 3;
        }
        return samples == 0 ? 0f : sum / (float) samples;
    }

    private void invalidateFrames() {
        for (int i = 0; i < RING; i++) {
            framePts[i] = 0;
            frameCut[i] = false;
            frameDuplicate[i] = false;
        }
    }

    private int newestFrame() {
        int newest = -1;
        for (int i = 0; i < RING; i++) {
            if (framePts[i] > 0 && (newest < 0 || framePts[i] > framePts[newest])) {
                newest = i;
            }
        }
        return newest;
    }

    private void render(long frameTimeNanos) {
        if (eglWindow == EGL14.EGL_NO_SURFACE || displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext);

        long minGap = 1_000_000_000L / Math.max(1, effectiveFps);
        if (nextPresentNs == 0) {
            nextPresentNs = frameTimeNanos;
        }
        if (frameTimeNanos < nextPresentNs) {
            return;
        }
        nextPresentNs += minGap;
        if (nextPresentNs <= frameTimeNanos) {
            nextPresentNs = frameTimeNanos + minGap;
        }

        int newest = newestFrame();
        if (newest < 0) {
            clearDisplay();
            present(frameTimeNanos);
            return;
        }

        long interval = intervalNs > 0 ? intervalNs : 33_333_333L;
        long desired = frameTimeNanos - interval;

        int before = -1;
        int after = -1;
        for (int i = 0; i < RING; i++) {
            long pts = framePts[i];
            if (pts <= 0) {
                continue;
            }
            if (pts <= desired && (before < 0 || pts > framePts[before])) {
                before = i;
            }
            if (pts >= desired && (after < 0 || pts < framePts[after])) {
                after = i;
            }
        }

        boolean drawn = false;
        if (interpolateEnabled && nativeReady && before >= 0 && after >= 0
                && before != after && !frameCut[after] && !frameDuplicate[after]) {
            long span = framePts[after] - framePts[before];
            if (span > 0) {
                float timestep = (desired - framePts[before]) / (float) span;
                if (timestep > 0.04f && timestep < 0.96f) {
                    drawn = interpolate(before, after, timestep);
                }
            }
        }
        if (!drawn) {
            int slot = newest;
            if (before >= 0 && after >= 0 && before != after) {
                slot = desired - framePts[before] < framePts[after] - desired
                        ? before : after;
            }
            drawFrameTexture(frameTexture[slot]);
            stats.interpolating = false;
        }
        present(frameTimeNanos);
        updateCounters();
    }

    private void renderCurrentFrame() {
        if (eglWindow == EGL14.EGL_NO_SURFACE || displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        int newest = newestFrame();
        if (newest < 0) {
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext);
        drawFrameTexture(frameTexture[newest]);
        stats.interpolating = false;
        present(System.nanoTime());
    }

    private boolean interpolate(int slot0, int slot1, float timestep) {
        long start = SystemClock.elapsedRealtimeNanos();
        boolean ok = FrameGenNative.nativeInterpolate(frameRgba[slot0], frameRgba[slot1],
                generatedRgb, processWidth, processHeight, timestep);
        long elapsed = SystemClock.elapsedRealtimeNanos() - start;
        float ms = elapsed / 1_000_000f;
        inferenceMs = inferenceMs == 0f ? ms : inferenceMs * 0.9f + ms * 0.1f;
        inferredInSample++;
        if (!ok) {
            return false;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generatedTexture);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, processWidth, processHeight,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, generatedRgb);
        drawFrameTexture(generatedTexture);
        stats.interpolating = true;
        return true;
    }

    private void present(long frameTimeNanos) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindow, frameTimeNanos);
        EGL14.eglSwapBuffers(eglDisplay, eglWindow);
        presentedInSample++;
    }

    private void updateCounters() {
        long now = SystemClock.elapsedRealtimeNanos();
        if (lastFpsSampleNs == 0) {
            lastFpsSampleNs = now;
            lastQualityCheckNs = now;
            return;
        }
        long elapsed = now - lastFpsSampleNs;
        if (elapsed >= 1_000_000_000L) {
            float fps = presentedInSample * 1_000_000_000f / elapsed;
            presentedInSample = 0;
            lastFpsSampleNs = now;
            stats.outputFps = Math.round(fps);
            stats.inferenceMs = inferenceMs;
        }
        if (inferredInSample > 0 && now - lastQualityCheckNs >= 2_000_000_000L) {
            float budget = 900f / Math.max(1, effectiveFps);
            if (inferenceMs > budget) {
                dropQuality();
            }
            inferredInSample = 0;
            lastQualityCheckNs = now;
        }
    }

    private void dropQuality() {
        if (quality < MAX_QUALITY) {
            quality++;
            recreateProcessBuffers();
            inferenceMs = 0f;
            notifyAdjusted();
            return;
        }
        int next = lowerFps(effectiveFps);
        if (next < effectiveFps) {
            effectiveFps = next;
            stats.targetFps = next;
            nextPresentNs = 0;
            inferenceMs = 0f;
            notifyAdjusted();
        }
    }

    private void notifyAdjusted() {
        final int fps = effectiveFps;
        final int tier = quality;
        mainHandler.post(() -> {
            if (!released) {
                listener.onAdjusted(fps, tier);
            }
        });
    }

    private int lowerFps(int fps) {
        if (fps > 90) {
            return displayHz >= 90 ? 90 : 60;
        }
        if (fps > 60) {
            return 60;
        }
        if (fps > 30) {
            return 30;
        }
        return fps;
    }

    private int maxOutputHeight() {
        return quality == 0 ? 720 : quality == 1 ? 540 : 360;
    }

    private int maxOutputWidth() {
        return quality == 0 ? 1280 : quality == 1 ? 960 : 640;
    }

    private void recreateProcessBuffers() {
        int width = videoWidth;
        int height = videoHeight;
        if (width <= 0 || height <= 0) {
            width = 1280;
            height = 720;
        }
        float scale = Math.min(1f, Math.min(maxOutputWidth() / (float) width,
                maxOutputHeight() / (float) height));
        int newWidth = Math.max(2, (int) (width * scale) & ~1);
        int newHeight = Math.max(2, (int) (height * scale) & ~1);
        stats.quality = quality;
        if (newWidth == processWidth && newHeight == processHeight
                && frameTexture[0] != 0) {
            return;
        }
        releaseProcessBuffers();
        processWidth = newWidth;
        processHeight = newHeight;
        stats.processWidth = processWidth;
        stats.processHeight = processHeight;
        int pixels = processWidth * processHeight;

        for (int i = 0; i < RING; i++) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            frameTexture[i] = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, processWidth,
                    processHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            int[] framebuffers = new int[1];
            GLES20.glGenFramebuffers(1, framebuffers, 0);
            frameFramebuffer[i] = framebuffers[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFramebuffer[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, frameTexture[i], 0);
            frameRgba[i] = ByteBuffer.allocateDirect(pixels * 4).order(ByteOrder.nativeOrder());
            framePts[i] = 0;
            frameCut[i] = false;
            frameDuplicate[i] = false;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        generatedTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generatedTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, processWidth,
                processHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        generatedRgb = ByteBuffer.allocateDirect(pixels * 3 + 16).order(ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        intervalNs = 0;
        lastFramePts = 0;
    }

    private void releaseProcessBuffers() {
        if (frameTexture[0] != 0) {
            GLES20.glDeleteTextures(RING, frameTexture, 0);
            GLES20.glDeleteFramebuffers(RING, frameFramebuffer, 0);
            for (int i = 0; i < RING; i++) {
                frameTexture[i] = 0;
                frameFramebuffer[i] = 0;
                frameRgba[i] = null;
                framePts[i] = 0;
                frameCut[i] = false;
                frameDuplicate[i] = false;
            }
        }
        if (generatedTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{generatedTexture}, 0);
            generatedTexture = 0;
        }
        generatedRgb = null;
        processWidth = 0;
        processHeight = 0;
    }

    private void drawOesToFrame(int slot) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFramebuffer[slot]);
        GLES20.glViewport(0, 0, processWidth, processHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(oesProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniform1i(oesSamplerHandle, 0);
        GLES20.glUniformMatrix4fv(oesMatrixHandle, 1, false, stMatrix, 0);
        drawQuad();
    }

    private void readFrame(int slot) {
        GLES20.glReadPixels(0, 0, processWidth, processHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, frameRgba[slot]);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void drawFrameTexture(int texture) {
        clearDisplay();
        int[] viewport = fitViewport();
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GLES20.glUseProgram(texProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(texSamplerHandle, 0);
        drawQuad();
    }

    private void clearDisplay() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, displayWidth, displayHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    private int[] fitViewport() {
        return new int[]{0, 0, displayWidth, displayHeight};
    }

    private boolean createQuad() {
        quad = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quad.put(new float[]{
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
        });
        quad.position(0);
        return true;
    }

    private void drawQuad() {
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glEnableVertexAttribArray(1);
        quad.position(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(0);
        GLES20.glDisableVertexAttribArray(1);
    }

    private boolean createPrograms() {
        String vertex = "attribute vec4 aPosition;\n"
                + "attribute vec4 aTextureCoord;\n"
                + "varying vec2 vTextureCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vTextureCoord = aTextureCoord.xy;\n"
                + "}\n";
        String oesFragment = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "varying vec2 vTextureCoord;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "uniform mat4 uMatrix;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(uTexture, (uMatrix * vec4(vTextureCoord, 0.0, 1.0)).xy);\n"
                + "}\n";
        String texFragment = "precision mediump float;\n"
                + "varying vec2 vTextureCoord;\n"
                + "uniform sampler2D uTexture;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(uTexture, vTextureCoord);\n"
                + "}\n";

        oesProgram = buildProgram(vertex, oesFragment);
        texProgram = buildProgram(vertex, texFragment);
        if (oesProgram == 0 || texProgram == 0) {
            return false;
        }
        oesSamplerHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uMatrix");
        texSamplerHandle = GLES20.glGetUniformLocation(texProgram, "uTexture");
        return oesSamplerHandle >= 0 && oesMatrixHandle >= 0 && texSamplerHandle >= 0;
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glBindAttribLocation(program, 0, "aPosition");
        GLES20.glBindAttribLocation(program, 1, "aTextureCoord");
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void releaseGl() {
        releaseProcessBuffers();
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram);
            oesProgram = 0;
        }
        if (texProgram != 0) {
            GLES20.glDeleteProgram(texProgram);
            texProgram = 0;
        }
        if (oesTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{oesTexture}, 0);
            oesTexture = 0;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (exoSurface != null) {
            exoSurface.release();
            exoSurface = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglWindow != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglWindow);
                eglWindow = EGL14.EGL_NO_SURFACE;
            }
            if (eglPbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglPbuffer);
                eglPbuffer = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }
}
