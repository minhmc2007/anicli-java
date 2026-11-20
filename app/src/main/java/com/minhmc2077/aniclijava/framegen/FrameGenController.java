package com.minhmc2077.aniclijava.framegen;

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
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Video output surface and presentation clock. Stage 1: pass-through, one draw per vsync. */
public final class FrameGenController implements Choreographer.FrameCallback, SurfaceHolder.Callback {
    public interface Listener {
        void onInputSurface(@NonNull Surface surface);

        void onFailure(@NonNull String reason);
    }

    public static final class Stats {
        public volatile int outputFps;
        public volatile int sourceFps;
        public volatile int vsyncFps;
        public volatile int width;
        public volatile int height;
        public volatile float frameMs;
        public volatile long sourceFrames;
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX_COORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private final SurfaceView view;
    private final Listener listener;
    private final Stats stats = new Stats();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler handler;
    private final FloatBuffer quadBuffer = buffer(QUAD);
    private final FloatBuffer texCoordBuffer = buffer(TEX_COORDS);
    private final float[] texMatrix = new float[16];

    private Choreographer choreographer;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglPbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface eglWindow = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int program;
    private int positionHandle;
    private int texCoordHandle;
    private int texMatrixHandle;
    private int textureHandle;
    private int textureId;
    private int surfaceWidth;
    private int surfaceHeight;

    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private boolean glReady;
    private boolean released;
    private boolean hasFrame;
    private boolean newFrame;
    private boolean drawEveryVsync;
    private boolean paused;

    private long draws;
    private long vsyncs;
    private long sourceCount;
    private long lastStatsNs;
    private long lastDraws;
    private long lastVsyncs;
    private long lastSource;

    public FrameGenController(@NonNull SurfaceView view, @NonNull Listener listener) {
        this.view = view;
        this.listener = listener;
        thread = new HandlerThread("framegen");
        thread.start();
        handler = new Handler(thread.getLooper());
        view.getHolder().addCallback(this);
    }

    public void start() {
        handler.post(this::initGl);
    }

    public void setProcessingEnabled(boolean enabled) {
        handler.post(() -> drawEveryVsync = enabled);
    }

    public void setPaused(boolean value) {
        handler.post(() -> paused = value);
    }

    public Stats stats() {
        return stats;
    }

    public void release() {
        view.getHolder().removeCallback(this);
        handler.post(() -> {
            released = true;
            if (choreographer != null) {
                choreographer.removeFrameCallback(this);
            }
            destroyGl();
            thread.quitSafely();
        });
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        handler.post(this::createWindowSurface);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        handler.post(() -> {
            surfaceWidth = width;
            surfaceHeight = height;
            stats.width = width;
            stats.height = height;
        });
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        handler.post(this::destroyWindowSurface);
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
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
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
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            fail("eglCreateContext");
            return;
        }

        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (eglPbuffer == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
            fail("eglPbufferSurface");
            return;
        }

        if (!buildProgram()) {
            fail("shader");
            return;
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        inputTexture = new SurfaceTexture(textureId);
        inputTexture.setOnFrameAvailableListener(texture -> onFrameAvailable(), handler);
        inputSurface = new Surface(inputTexture);
        Matrix.setIdentityM(texMatrix, 0);
        glReady = true;

        mainHandler.post(() -> {
            if (!released) {
                listener.onInputSurface(inputSurface);
            }
        });

        if (view.getHolder().getSurface() != null
                && view.getHolder().getSurface().isValid()) {
            createWindowSurface();
        }

        choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(this);
    }

    private boolean buildProgram() {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) {
            return false;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
            return false;
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
        return true;
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

    private void createWindowSurface() {
        if (!glReady || eglWindow != EGL14.EGL_NO_SURFACE || released) {
            return;
        }
        Surface surface = view.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        int[] attribs = {EGL14.EGL_NONE};
        eglWindow = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            fail("eglCreateWindowSurface");
            return;
        }
        int[] value = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_WIDTH, value, 0);
        surfaceWidth = value[0];
        EGL14.eglQuerySurface(eglDisplay, eglWindow, EGL14.EGL_HEIGHT, value, 0);
        surfaceHeight = value[0];
        stats.width = surfaceWidth;
        stats.height = surfaceHeight;
        if (EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            EGL14.eglSwapInterval(eglDisplay, 0);
        }
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
    }

    private void destroyWindowSurface() {
        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
        EGL14.eglDestroySurface(eglDisplay, eglWindow);
        eglWindow = EGL14.EGL_NO_SURFACE;
    }

    private void destroyGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (inputTexture != null) {
            inputTexture.release();
            inputTexture = null;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        if (eglWindow != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglWindow);
            eglWindow = EGL14.EGL_NO_SURFACE;
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
        EGL14.eglTerminate(eglDisplay);
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        glReady = false;
    }

    private void onFrameAvailable() {
        if (released || inputTexture == null) {
            return;
        }
        try {
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);
        } catch (RuntimeException error) {
            return;
        }
        hasFrame = true;
        newFrame = true;
        sourceCount++;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (released) {
            return;
        }
        vsyncs++;
        if (eglWindow != EGL14.EGL_NO_SURFACE && hasFrame && !paused
                && (drawEveryVsync || newFrame)) {
            newFrame = false;
            draw(frameTimeNanos);
        }
        updateStats(frameTimeNanos);
        choreographer.postFrameCallback(this);
    }

    private void draw(long frameTimeNanos) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglWindow, eglWindow, eglContext)) {
            return;
        }
        long started = SystemClock.elapsedRealtimeNanos();
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(textureHandle, 0);
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0,
                quadBuffer);
        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0,
                texCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindow, frameTimeNanos);
        EGL14.eglSwapBuffers(eglDisplay, eglWindow);

        float ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f;
        stats.frameMs = stats.frameMs == 0f ? ms : stats.frameMs * 0.9f + ms * 0.1f;
        draws++;

        EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext);
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void updateStats(long frameTimeNanos) {
        if (lastStatsNs == 0) {
            lastStatsNs = frameTimeNanos;
            lastDraws = draws;
            lastVsyncs = vsyncs;
            lastSource = sourceCount;
            return;
        }
        long elapsed = frameTimeNanos - lastStatsNs;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        double seconds = elapsed / 1_000_000_000d;
        stats.outputFps = (int) Math.round((draws - lastDraws) / seconds);
        stats.vsyncFps = (int) Math.round((vsyncs - lastVsyncs) / seconds);
        stats.sourceFps = (int) Math.round((sourceCount - lastSource) / seconds);
        stats.sourceFrames = sourceCount;
        lastStatsNs = frameTimeNanos;
        lastDraws = draws;
        lastVsyncs = vsyncs;
        lastSource = sourceCount;
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
