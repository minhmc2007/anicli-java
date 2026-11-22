package com.minhmc2077.aniclijava.framegen;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Frame generation on Vulkan 1.3. The decoder still hands frames over through a
 * SurfaceTexture; they are blitted into AHardwareBuffers that Vulkan samples
 * directly, so the GPU round trip never leaves the device.
 */
final class VulkanFrameGenController implements FrameGenController,
        Choreographer.FrameCallback, SurfaceHolder.Callback,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "framegen";

    private static final String OES_VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
                    + "}\n";

    private static final String OES_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX_COORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    private static final int RING = 2;

    private final SurfaceView view;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler handler;
    private final FloatBuffer quadBuffer = buffer(QUAD);
    private final FloatBuffer texCoordBuffer = buffer(TEX_COORDS);
    private final float[] texMatrix = new float[16];
    private final int[] slotTexture = new int[RING];
    private final int[] frameFbo = new int[1];

    private Choreographer choreographer;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglPbuffer = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int oesProgram;
    private int oesPosition;
    private int oesTexCoord;
    private int oesMatrix;
    private int oesTexture;
    private int oesTextureId;

    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private long handle;

    private int requestedWidth;
    private int requestedHeight;
    private int videoWidth;
    private int videoHeight;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean surfaceAttached;
    private int writeIndex = RING - 1;
    private int frameCount;
    private int flowPrev = -1;
    private int flowNext = -1;
    private boolean flowValid;
    private boolean ready;
    private boolean released;
    private boolean paused;
    private boolean hasFrame;
    private boolean newFrame;
    private boolean fboChecked;
    private int targetFps = 60;
    private long intervalNs = 41_666_667L;
    private long lastPtsNs;
    private long pairStartNs;
    private long nextDrawNs;

    VulkanFrameGenController(@NonNull SurfaceView view, @NonNull Listener listener) {
        this.view = view;
        this.listener = listener;
        thread = new HandlerThread("framegen-vk");
        thread.start();
        handler = new Handler(thread.getLooper());
        view.getHolder().addCallback(this);
    }

    @Override
    public void start() {
        handler.post(this::initGl);
    }

    @Override
    public void setPaused(boolean value) {
        handler.post(() -> paused = value);
    }

    @Override
    public void setTargetFps(int fps) {
        handler.post(() -> targetFps = fps >= 120 ? 120 : 60);
    }

    @Override
    public void release() {
        view.getHolder().removeCallback(this);
        CountDownLatch detached = new CountDownLatch(1);
        if (!handler.post(() -> {
            released = true;
            if (choreographer != null) {
                choreographer.removeFrameCallback(this);
            }
            destroyGl();
            detached.countDown();
            thread.quitSafely();
        })) {
            return;
        }
        try {
            detached.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        handler.post(() -> {
            android.graphics.Rect frame = holder.getSurfaceFrame();
            attachSurface(holder.getSurface(), frame.width(), frame.height());
        });
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        handler.post(() -> {
            if (!surfaceAttached || width != surfaceWidth || height != surfaceHeight) {
                attachSurface(holder.getSurface(), width, height);
            }
        });
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        CountDownLatch detached = new CountDownLatch(1);
        if (!handler.post(() -> {
            detachSurface();
            detached.countDown();
        })) {
            return;
        }
        try {
            detached.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void initGl() {
        if (released) {
            return;
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            fail("eglGetDisplay");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            fail("eglInitialize");
            return;
        }
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            fail("eglChooseConfig");
            return;
        }
        eglConfig = configs[0];
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        int[] pbufferAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT || eglPbuffer == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            fail("egl context");
            return;
        }

        oesProgram = linkProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER);
        if (oesProgram == 0) {
            fail("shader");
            return;
        }
        oesPosition = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoord = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesMatrix = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");
        oesTexture = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        GLES20.glGenFramebuffers(1, frameFbo, 0);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        inputTexture = new SurfaceTexture(oesTextureId);
        inputTexture.setOnFrameAvailableListener(this, handler);
        inputSurface = new Surface(inputTexture);
        Matrix.setIdentityM(texMatrix, 0);

        long started = android.os.SystemClock.elapsedRealtime();
        handle = VulkanFrameGen.open(view.getContext());
        if (handle == 0) {
            fail("vulkan init");
            return;
        }
        Log.i(TAG, "vulkan engine ready in "
                + (android.os.SystemClock.elapsedRealtime() - started) + " ms");

        ready = true;
        resizeVideo();
        if (view.getHolder().getSurface() != null
                && view.getHolder().getSurface().isValid()) {
            android.graphics.Rect frame = view.getHolder().getSurfaceFrame();
            attachSurface(view.getHolder().getSurface(), frame.width(), frame.height());
        }
        mainHandler.post(() -> {
            if (!released) {
                listener.onInputSurface(inputSurface);
            }
        });
        choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(this);
    }

    @Override
    public void setVideoSize(int width, int height) {
        handler.post(() -> {
            requestedWidth = width;
            requestedHeight = height;
            resizeVideo();
        });
    }

    private void resizeVideo() {
        if (!ready || handle == 0 || requestedWidth <= 0 || requestedHeight <= 0) {
            return;
        }
        if (requestedWidth == videoWidth && requestedHeight == videoHeight
                && slotTexture[0] != 0) {
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            return;
        }
        int slots = VulkanFrameGen.resize(handle, requestedWidth, requestedHeight);
        if (slots <= 0) {
            fail("vulkan resize");
            return;
        }
        for (int i = 0; i < RING && i < slots; i++) {
            slotTexture[i] = VulkanFrameGen.slotTexture(handle, i);
        }
        videoWidth = requestedWidth;
        videoHeight = requestedHeight;
        frameCount = 0;
        fboChecked = false;
        writeIndex = RING - 1;
        lastPtsNs = 0;
        pairStartNs = 0;
        flowValid = false;
        flowPrev = -1;
        flowNext = -1;
        Log.i(TAG, String.format(Locale.US, "vulkan video %dx%d", videoWidth, videoHeight));
    }

    private void attachSurface(Surface surface, int width, int height) {
        if (handle == 0 || surface == null || !surface.isValid()) {
            return;
        }
        surfaceWidth = width;
        surfaceHeight = height;
        VulkanFrameGen.setSurface(handle, surface, width, height);
        surfaceAttached = true;
    }

    private void detachSurface() {
        if (handle != 0) {
            VulkanFrameGen.setSurface(handle, null, 0, 0);
        }
        surfaceAttached = false;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture texture) {
        if (released || inputTexture == null) {
            return;
        }
        try {
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);
        } catch (RuntimeException error) {
            return;
        }
        long pts = inputTexture.getTimestamp();
        if (pts > 0) {
            trackTiming(pts);
        }
        if (pts > 0 && videoWidth > 0 && videoHeight > 0 && slotTexture[0] != 0) {
            writeIndex = (writeIndex + 1) % RING;
            VulkanFrameGen.sync(handle);
            copyToSlot(writeIndex);
            if (frameCount < RING) {
                frameCount++;
            }
            if (frameCount >= 2) {
                int prev = (writeIndex - 1 + RING) % RING;
                if (EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
                    VulkanFrameGen.flow(handle, prev, writeIndex);
                    flowPrev = prev;
                    flowNext = writeIndex;
                    flowValid = true;
                }
            }
            advancePair();
        }
        hasFrame = true;
        newFrame = true;
    }

    private void copyToSlot(int index) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, slotTexture[index], 0);
        if (!fboChecked) {
            fboChecked = true;
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "slot fbo incomplete 0x" + Integer.toHexString(status));
            }
        }
        GLES20.glViewport(0, 0, videoWidth, videoHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(oesProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesTexture, 0);
        GLES20.glUniformMatrix4fv(oesMatrix, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(oesPosition);
        GLES20.glVertexAttribPointer(oesPosition, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glEnableVertexAttribArray(oesTexCoord);
        GLES20.glVertexAttribPointer(oesTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(oesPosition);
        GLES20.glDisableVertexAttribArray(oesTexCoord);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glFinish();
    }

    private void advancePair() {
        long now = System.nanoTime();
        if (pairStartNs == 0 || now - pairStartNs > intervalNs + intervalNs / 2) {
            pairStartNs = now;
        } else {
            pairStartNs += intervalNs;
        }
    }

    private void trackTiming(long pts) {
        if (lastPtsNs > 0) {
            long delta = pts - lastPtsNs;
            if (delta < 0 || delta > 2_000_000_000L) {
                resetStream();
            } else if (delta > 1_000_000L && delta < 500_000_000L) {
                intervalNs += (delta - intervalNs) / 8;
            }
        }
        lastPtsNs = pts;
    }

    private void resetStream() {
        frameCount = 0;
        writeIndex = RING - 1;
        flowPrev = -1;
        flowNext = -1;
        flowValid = false;
        pairStartNs = 0;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (released) {
            return;
        }
        if (ready && surfaceAttached && hasFrame && !paused) {
            boolean live = newFrame || frameTimeNanos < pairStartNs + intervalNs;
            if (live && due(frameTimeNanos)) {
                newFrame = false;
                draw(frameTimeNanos);
            }
        }
        choreographer.postFrameCallback(this);
    }

    private boolean due(long frameTimeNanos) {
        if (nextDrawNs == 0 || frameTimeNanos - nextDrawNs > 1_000_000_000L) {
            nextDrawNs = frameTimeNanos;
        }
        long interval = 1_000_000_000L / targetFps;
        if (frameTimeNanos + 1_000_000L < nextDrawNs) {
            return false;
        }
        nextDrawNs += interval;
        if (nextDrawNs < frameTimeNanos) {
            nextDrawNs = frameTimeNanos + interval;
        }
        return true;
    }

    private void draw(long frameTimeNanos) {
        if (handle == 0 || frameCount == 0) {
            return;
        }
        if (flowValid) {
            float timestep = (frameTimeNanos - pairStartNs) / (float) intervalNs;
            VulkanFrameGen.warp(handle, flowPrev, flowNext,
                    Math.max(0f, Math.min(1f, timestep)));
        } else {
            VulkanFrameGen.raw(handle, writeIndex);
        }
    }

    private void destroyGl() {
        if (handle != 0) {
            VulkanFrameGen.destroy(handle);
            handle = 0;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (inputTexture != null) {
            inputTexture.release();
            inputTexture = null;
        }
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram);
            oesProgram = 0;
        }
        if (frameFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, frameFbo, 0);
            frameFbo[0] = 0;
        }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{oesTextureId}, 0);
            oesTextureId = 0;
        }
        if (eglPbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglPbuffer);
            eglPbuffer = EGL14.EGL_NO_SURFACE;
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        ready = false;
    }

    private static int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glBindAttribLocation(program, 0, "aPosition");
        GLES20.glBindAttribLocation(program, 1, "aTexCoord");
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
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

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void fail(String reason) {
        String message = String.format(Locale.US, "%s failed", reason);
        mainHandler.post(() -> {
            if (!released) {
                listener.onFailure(message);
            }
        });
    }
}
